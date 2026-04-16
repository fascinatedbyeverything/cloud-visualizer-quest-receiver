package com.meta.spatial.samples.mediaplayersample

import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.ActivityPanelRegistration
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature

data class Stream(val title: String, val url: String, val description: String)

@OptIn(UnstableApi::class)
class CloudPlayerActivity : AppSystemActivity() {

    var exoPlayer: ExoPlayer? = null
    private var hlsFactory: HlsMediaSource.Factory? = null
    private var skyVideoPanel: Entity? = null
    private var controlPanelEntity: Entity? = null
    private var transportPanelEntity: Entity? = null
    private lateinit var locomotionSystem: LocomotionSystem
    private val positionHandler = Handler(Looper.getMainLooper())
    private val retryHandler = Handler(Looper.getMainLooper())
    private val positionUpdater = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                PlayerState.currentPositionMs.value = player.currentPosition
                PlayerState.durationMs.value = player.duration.coerceAtLeast(0L)
            }
            positionHandler.postDelayed(this, 500L)
        }
    }

    override fun registerFeatures(): List<SpatialFeature> {
        val features = mutableListOf<SpatialFeature>(VRFeature(this), ComposeFeature())
        if (BuildConfig.DEBUG) {
            features.add(CastInputForwardFeature(this))
            features.add(HotReloadFeature(this))
            features.add(OVRMetricsFeature(this, OVRMetricsDataModel() { numberOfMeshes() }))
            features.add(DataModelInspectorFeature(spatial, this.componentManager))
        }
        return features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locomotionSystem = systemManager.findSystem<LocomotionSystem>()
        volumeControlStream = AudioManager.STREAM_MUSIC
        // Kiosk: never let the display sleep while the app has focus
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onSceneReady() {
        super.onSceneReady()

        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.setLightingEnvironment(
            ambientColor = Vector3(5.0f),
            sunColor = Vector3(0f, 0f, 0f),
            sunDirection = -Vector3(1f, 3f, 2f),
        )
        scene.updateIBLEnvironment("chromatic.env")

        // Kiosk: video sphere only, visible from the start. No menu, no transport.
        skyVideoPanel = Entity.create(Panel(R.id.video_panel_360), Transform(), Visible(true))

        // Right-thumbstick click = recenter view. No other input bindings — hand pinches,
        // A/B/X/Y, triggers, and grips all do nothing so accidental gestures can't stop the stream.
        systemManager.registerSystem(RecenterInputSystem())

        Log.i(TAG, "Kiosk scene ready — video panel visible, recenter-only input registered")
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(videoPanel360Registration())
    }

    private fun videoPanel360Registration(): PanelRegistration {
        return VideoSurfacePanelRegistration(
            R.id.video_panel_360,
            surfaceConsumer = { _, surface ->
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true)
                    .setUserAgent("CloudVisualizerReceiver/2.0 Quest")

                val trackSelector = DefaultTrackSelector(this).apply {
                    setParameters(
                        buildUponParameters()
                            .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                            .setForceHighestSupportedBitrate(true)
                            .build()
                    )
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_GAME)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

                hlsFactory = HlsMediaSource.Factory(httpFactory)
                    .setAllowChunklessPreparation(false)

                exoPlayer = ExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .build().apply {
                        setAudioAttributes(audioAttributes, false)
                        repeatMode = Player.REPEAT_MODE_ONE
                        setVideoSurface(surface)

                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                PlayerState.isBuffering.value = (state == Player.STATE_BUFFERING)
                                if (state == Player.STATE_READY) {
                                    Log.i(TAG, "Playback ready")
                                }
                            }

                            override fun onIsPlayingChanged(playing: Boolean) {
                                PlayerState.isPlaying.value = playing
                            }

                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                PlayerState.videoWidth.value = videoSize.width
                                PlayerState.videoHeight.value = videoSize.height
                                Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
                            }

                            override fun onTracksChanged(tracks: Tracks) {
                                for (group in tracks.groups) {
                                    if (group.isSelected) {
                                        for (i in 0 until group.length) {
                                            if (group.isTrackSelected(i)) {
                                                val format: Format = group.getTrackFormat(i)
                                                if (format.sampleMimeType?.startsWith("video/") == true) {
                                                    PlayerState.bitrate.value = format.bitrate.toLong()
                                                    if (format.width > 0) PlayerState.videoWidth.value = format.width
                                                    if (format.height > 0) PlayerState.videoHeight.value = format.height
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                PlayerState.errorMessage.value = error.message ?: "Playback error"
                                Log.e(TAG, "Player error — retrying stream in 3s: ${error.message}")
                                retryHandler.removeCallbacksAndMessages(null)
                                retryHandler.postDelayed({
                                    playStream(STREAM_URL, STREAM_TITLE)
                                }, 3000L)
                            }
                        })
                    }

                Log.i(TAG, "ExoPlayer ready — auto-playing Visionary stream")
                playStream(STREAM_URL, STREAM_TITLE)
            },
            settingsCreator = {
                MediaPanelSettings(
                    shape = Equirect360ShapeOptions(radius = 300.0f),
                    display = PixelDisplayOptions(width = 100, height = 100),
                    rendering = MediaPanelRenderOptions(
                        stereoMode = StereoMode.None,
                        zIndex = -1,
                    ),
                )
            },
        )
    }

    private fun controlPanelRegistration(): PanelRegistration {
        return ActivityPanelRegistration(
            R.id.control_panel,
            classIdCreator = { ControlPanel::class.java },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(
                        width = ControlPanel.WIDTH_IN_METERS,
                        height = ControlPanel.HEIGHT_IN_METERS,
                    ),
                    display = DpDisplayOptions(
                        width = ControlPanel.WIDTH_IN_DP,
                        height = ControlPanel.HEIGHT_IN_DP,
                        dpi = ControlPanel.DPI,
                    ),
                )
            },
        )
    }

    private fun transportPanelRegistration(): PanelRegistration {
        return ActivityPanelRegistration(
            R.id.transport_panel,
            classIdCreator = { TransportPanel::class.java },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(
                        width = TransportPanel.WIDTH_IN_METERS,
                        height = TransportPanel.HEIGHT_IN_METERS,
                    ),
                    display = DpDisplayOptions(
                        width = TransportPanel.WIDTH_IN_DP,
                        height = TransportPanel.HEIGHT_IN_DP,
                        dpi = TransportPanel.DPI,
                    ),
                )
            },
        )
    }

    fun playStream(url: String, title: String) {
        Log.i(TAG, "Playing: $title")
        PlayerState.currentTitle.value = title
        PlayerState.errorMessage.value = ""

        skyVideoPanel?.setComponent(Visible(true))
        controlPanelEntity?.setComponent(Visible(false))
        transportPanelEntity?.setComponent(Visible(true))
        PlayerState.controlPanelVisible.value = false
        PlayerState.transportVisible.value = true

        exoPlayer?.let { player ->
            val source = hlsFactory!!.createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            player.setMediaSource(source)
            player.prepare()
            player.play()
        }
        positionHandler.post(positionUpdater)
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun updateSphereView(tiltDeg: Float, panDeg: Float, zoomFraction: Float) {
        skyVideoPanel?.let { panel ->
            val zOffset = -(zoomFraction * 250f)
            val rotation = Quaternion(tiltDeg, panDeg, 0f)
            panel.setComponent(Transform(Pose(Vector3(0f, 0f, zOffset), rotation)))
        }
    }

    fun resetView() {
        PlayerState.tiltDeg.value = 0f
        PlayerState.panDeg.value = 0f
        PlayerState.zoomFraction.value = 0f
        skyVideoPanel?.setComponent(Transform(Pose()))
    }

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun stopPlayback() {
        positionHandler.removeCallbacks(positionUpdater)
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        skyVideoPanel?.setComponent(Visible(false))
        controlPanelEntity?.setComponent(Visible(true))
        transportPanelEntity?.setComponent(Visible(false))
        PlayerState.controlPanelVisible.value = true
        PlayerState.transportVisible.value = false
        PlayerState.currentTitle.value = ""
        PlayerState.isPlaying.value = false
        PlayerState.currentPositionMs.value = 0L
        PlayerState.durationMs.value = 0L
        resetView()
    }

    fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
    }

    fun toggleControlPanel() {
        val isVisible = PlayerState.controlPanelVisible.value
        controlPanelEntity?.setComponent(Visible(!isVisible))
        PlayerState.controlPanelVisible.value = !isVisible
    }

    fun hideTransport() {
        transportPanelEntity?.setComponent(Visible(false))
        PlayerState.transportVisible.value = false
        // Show control panel so user always has a way back
        if (PlayerState.currentTitle.value.isNotEmpty()) {
            controlPanelEntity?.setComponent(Visible(true))
            PlayerState.controlPanelVisible.value = true
        }
    }

    fun showTransport() {
        transportPanelEntity?.setComponent(Visible(true))
        PlayerState.transportVisible.value = true
        // Hide control panel when transport is visible during playback
        if (PlayerState.currentTitle.value.isNotEmpty()) {
            controlPanelEntity?.setComponent(Visible(false))
            PlayerState.controlPanelVisible.value = false
        }
    }

    fun toggleTransport() {
        val visible = PlayerState.transportVisible.value
        transportPanelEntity?.setComponent(Visible(!visible))
        PlayerState.transportVisible.value = !visible
    }

    /** Recenter the view by re-anchoring the LOCAL_FLOOR reference space to the
     *  headset's current position + orientation. */
    fun recenterView() {
        Log.i(TAG, "Recenter — re-anchoring reference space")
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    }

    /** Quit the app cleanly, returning the user to the Quest home. */
    fun quitToHome() {
        Log.i(TAG, "Quit — returning to Quest home")
        finishAndRemoveTask()
    }

    /** ECS System — minimal controller bindings for kiosk mode:
     *  Either thumbstick click = restart stream (reconnect if black/stalled)
     *  Hold EITHER thumbstick click ≥ 3 seconds = quit to Quest home
     *  Works with left-only (Psychedelic) or right-only (all others) controller. */
    inner class RecenterInputSystem : SystemBase() {
        private var leftHeldSinceMs: Long = 0L
        private var rightHeldSinceMs: Long = 0L
        private var quitFired: Boolean = false

        override fun execute() {
            val controllers = Query.where { has(Controller.id) }
            for (entity in controllers.eval()) {
                val c = entity.getComponent<Controller>()

                val leftDown = (c.buttonState and ButtonBits.ButtonThumbLClick) != 0
                val rightDown = (c.buttonState and ButtonBits.ButtonThumbRClick) != 0
                val leftChanged = (c.changedButtons and ButtonBits.ButtonThumbLClick) != 0
                val rightChanged = (c.changedButtons and ButtonBits.ButtonThumbRClick) != 0

                val now = System.currentTimeMillis()

                // Either thumbstick click — restart stream
                if (leftChanged && leftDown) {
                    leftHeldSinceMs = now
                    quitFired = false
                    Log.i(TAG, "Left thumbstick click — restarting stream")
                    playStream(STREAM_URL, STREAM_TITLE)
                }

                if (rightChanged && rightDown) {
                    rightHeldSinceMs = now
                    quitFired = false
                    Log.i(TAG, "Right thumbstick click — restarting stream")
                    playStream(STREAM_URL, STREAM_TITLE)
                }

                // Release edges — reset timers
                if (leftChanged && !leftDown) leftHeldSinceMs = 0L
                if (rightChanged && !rightDown) rightHeldSinceMs = 0L

                // Hold either thumbstick ≥ 3 seconds = quit
                if (!quitFired) {
                    val leftHeldMs = if (leftHeldSinceMs > 0L && leftDown) now - leftHeldSinceMs else 0L
                    val rightHeldMs = if (rightHeldSinceMs > 0L && rightDown) now - rightHeldSinceMs else 0L
                    if (leftHeldMs >= 3000L || rightHeldMs >= 3000L) {
                        quitFired = true
                        quitToHome()
                        return
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        // Kiosk: whatever interrupted us, just resume playing the stream
        exoPlayer?.let { player ->
            if (!player.isPlaying) {
                playStream(STREAM_URL, STREAM_TITLE)
            }
        }
    }

    override fun onSpatialShutdown() {
        positionHandler.removeCallbacks(positionUpdater)
        retryHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        super.onSpatialShutdown()
    }

    companion object {
        private const val TAG = "CloudReceiver"

        // Kiosk mode: one hard-wired stream, auto-played on app launch.
        const val STREAM_URL = "http://visionary.local:8888/live/stream/index.m3u8"
        const val STREAM_TITLE = "Visionary (M4 Pro 8K)"

        // Legacy preset list retained for ControlPanel/TransportPanel (unused in kiosk mode).
        // Three presets:
        // 1. Visionary (M4 Pro) — primary 8K source machine, sub-1s LAN latency
        // 2. MacBook Pro (M3) — fallback / dev box, sub-1s LAN latency
        // 3. Cloudflare Stream Live — global CDN, ~2s latency, $5/mo + delivery
        // All are HLS m3u8 URLs consumed by ExoPlayer's HlsMediaSource.
        val STREAMS = listOf(
            Stream(
                "Visionary (M4 Pro 8K)",
                "http://visionary.local:8888/live/stream/index.m3u8",
                "LAN — 8K live VJ mix from visionary",
            ),
            Stream(
                "MacBook Pro (M3)",
                "http://fascintated-2.local:8888/live/stream/index.m3u8",
                "LAN — sub-1s latency",
            ),
            Stream(
                "Global (Cloudflare)",
                "https://customer-uutaq63i96lrvqsr.cloudflarestream.com/197b8d9bc2aabd9f9b2f2dd9dca91b3d/manifest/video.m3u8?protocol=llhlsbeta",
                "Worldwide CDN — ~2s latency",
            ),
        )
    }
}

object PlayerState {
    val isPlaying = mutableStateOf(false)
    val isBuffering = mutableStateOf(false)
    val currentTitle = mutableStateOf("")
    val videoWidth = mutableStateOf(0)
    val videoHeight = mutableStateOf(0)
    val bitrate = mutableStateOf(0L)
    val errorMessage = mutableStateOf("")
    val controlPanelVisible = mutableStateOf(true)
    val currentPositionMs = mutableStateOf(0L)
    val durationMs = mutableStateOf(0L)
    val transportVisible = mutableStateOf(false)
    val tiltDeg = mutableStateOf(0f)
    val panDeg = mutableStateOf(0f)
    val zoomFraction = mutableStateOf(0f)
}

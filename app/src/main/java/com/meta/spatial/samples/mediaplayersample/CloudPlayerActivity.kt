package com.meta.spatial.samples.mediaplayersample

import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

        skyVideoPanel = Entity.create(Panel(R.id.video_panel_360), Transform(), Visible(false))

        controlPanelEntity = Entity.create(
            Panel(R.id.control_panel),
            Transform(Pose(Vector3(0f, 1.3f, 2.0f))),
            Grabbable(enabled = true, type = GrabbableType.FACE),
        )

        transportPanelEntity = Entity.create(
            Panel(R.id.transport_panel),
            Transform(Pose(Vector3(0f, 0.8f, 1.5f))),
            Visible(false),
            Grabbable(enabled = true, type = GrabbableType.FACE),
        )

        // Register B/Y button system (same pattern as SplatSample ControllerListenerSystem)
        systemManager.registerSystem(ButtonInputSystem())

        Log.i(TAG, "v3 Scene ready - panels created, ButtonInputSystem registered")
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            videoPanel360Registration(),
            controlPanelRegistration(),
            transportPanelRegistration(),
        )
    }

    private fun videoPanel360Registration(): PanelRegistration {
        return VideoSurfacePanelRegistration(
            R.id.video_panel_360,
            surfaceConsumer = { _, surface ->
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setAllowCrossProtocolRedirects(true)
                    .setUserAgent("CloudVisualizerReceiver/1.0 Quest")

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
                                Log.e(TAG, "Player error: ${error.message}")
                            }
                        })
                    }

                Log.i(TAG, "ExoPlayer created with HLS support")
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

    /** ECS System that polls controller button state each frame.
     *  Same pattern as SplatSample.ControllerListenerSystem and Focus GeneralSystem. */
    inner class ButtonInputSystem : SystemBase() {
        override fun execute() {
            val controllers = Query.where { has(Controller.id) }
            for (entity in controllers.eval()) {
                val c = entity.getComponent<Controller>()
                // B button: toggle transport on/off
                if ((c.changedButtons and ButtonBits.ButtonB) != 0 &&
                    (c.buttonState and ButtonBits.ButtonB) != 0
                ) {
                    Log.i(TAG, "B button - toggle transport")
                    toggleTransport()
                    return
                }
                // Y button: same as B (left controller)
                if ((c.changedButtons and ButtonBits.ButtonY) != 0 &&
                    (c.buttonState and ButtonBits.ButtonY) != 0
                ) {
                    Log.i(TAG, "Y button - toggle transport")
                    toggleTransport()
                    return
                }
                // A button: back to main menu (stop playback)
                if ((c.changedButtons and ButtonBits.ButtonA) != 0 &&
                    (c.buttonState and ButtonBits.ButtonA) != 0
                ) {
                    if (PlayerState.currentTitle.value.isNotEmpty()) {
                        Log.i(TAG, "A button - stop playback, back to menu")
                        stopPlayback()
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
        if (PlayerState.isPlaying.value) exoPlayer?.play()
    }

    override fun onSpatialShutdown() {
        positionHandler.removeCallbacks(positionUpdater)
        exoPlayer?.release()
        exoPlayer = null
        super.onSpatialShutdown()
    }

    companion object {
        private const val TAG = "CloudReceiver"

        // Single live preset — mirrors the Vision Pro receiver's "MacBook Pro (this Mac)"
        // one-tap entry. Edit the host here to add other Macs on the network. The path
        // /live/stream is MediaMTX's default for OBS publishing path "live" + stream key
        // "stream".
        val STREAMS = listOf(
            Stream(
                "MacBook Pro (this Mac)",
                "http://fascintated-2.local:8888/live/stream/index.m3u8",
                "Live VJ mix from Cloud Visualizer / OBS",
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

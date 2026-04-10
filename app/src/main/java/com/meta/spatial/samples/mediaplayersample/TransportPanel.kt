package com.meta.spatial.samples.mediaplayersample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.toolkit.SpatialActivityManager

private val BgColor = Color(0xCC0a0a0a)
private val TextPrimary = Color(0xFFe8e0d4)
private val TextDim = Color(0x99e8e0d4)
private val AccentColor = Color(0xFFc4a882)
private val ButtonBg = Color(0xFF1a1a1a)
private val ButtonHover = Color(0xFF2a2520)
private val SliderTrack = Color(0xFF333333)

class TransportPanel : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TransportBarUI() }
    }

    companion object {
        const val WIDTH_IN_DP = 520f
        const val HEIGHT_IN_DP = 300f
        const val WIDTH_IN_METERS = 1.1f
        const val HEIGHT_IN_METERS = 0.635f
        const val DPI = 260
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
fun TransportBarUI() {
    val isPlaying by PlayerState.isPlaying
    val currentTitle by PlayerState.currentTitle
    val positionMs by PlayerState.currentPositionMs
    val durationMs by PlayerState.durationMs
    val tiltDeg by PlayerState.tiltDeg
    val panDeg by PlayerState.panDeg
    val zoomFraction by PlayerState.zoomFraction
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableStateOf(0f) }

    val sliderValue = if (isSeeking) {
        seekPosition
    } else if (durationMs > 0) {
        positionMs.toFloat() / durationMs.toFloat()
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(BgColor),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Grab handle bar — tall non-interactive zone for grabbing the panel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TextDim),
            )
        }

        // Content area with padding
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // Live stream — no timeline scrubber. Just orientation + playback controls below.

        // Row 1: Zoom / Tilt / Pan
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Zoom", fontSize = 10.sp, color = TextDim, modifier = Modifier.width(36.dp))
            Slider(
                value = (zoomFraction + 1f) / 2f,
                onValueChange = { value ->
                    val newZoom = (value * 2f) - 1f
                    PlayerState.zoomFraction.value = newZoom
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> {
                        it.updateSphereView(tiltDeg, panDeg, newZoom)
                    }
                },
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor.copy(alpha = 0.6f),
                    inactiveTrackColor = SliderTrack,
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(text = "Tilt", fontSize = 10.sp, color = TextDim, modifier = Modifier.width(26.dp))
            Slider(
                value = (tiltDeg + 90f) / 180f,
                onValueChange = { value ->
                    val newTilt = (value * 180f) - 90f
                    PlayerState.tiltDeg.value = newTilt
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> {
                        it.updateSphereView(newTilt, panDeg, zoomFraction)
                    }
                },
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor.copy(alpha = 0.6f),
                    inactiveTrackColor = SliderTrack,
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(text = "Pan", fontSize = 10.sp, color = TextDim, modifier = Modifier.width(26.dp))
            Slider(
                value = (panDeg + 180f) / 360f,
                onValueChange = { value ->
                    val newPan = (value * 360f) - 180f
                    PlayerState.panDeg.value = newPan
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> {
                        it.updateSphereView(tiltDeg, newPan, zoomFraction)
                    }
                },
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AccentColor,
                    activeTrackColor = AccentColor.copy(alpha = 0.6f),
                    inactiveTrackColor = SliderTrack,
                ),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 3: Transport buttons with close on far right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentTitle.ifEmpty { "Ready" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AccentColor,
                maxLines = 1,
            )

            Spacer(modifier = Modifier.width(10.dp))

            SmallButton(
                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (isPlaying) "Pause" else "Play",
                onClick = {
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { it.togglePlayPause() }
                },
            )

            Spacer(modifier = Modifier.width(6.dp))

            SmallButton(
                icon = Icons.Rounded.Stop,
                description = "Stop",
                onClick = {
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { it.stopPlayback() }
                },
            )

            Spacer(modifier = Modifier.width(6.dp))

            SmallButton(
                icon = Icons.Rounded.VolumeDown,
                description = "Volume Down",
                onClick = {
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { it.adjustVolume(-1) }
                },
            )

            Spacer(modifier = Modifier.width(6.dp))

            SmallButton(
                icon = Icons.Rounded.VolumeUp,
                description = "Volume Up",
                onClick = {
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { it.adjustVolume(1) }
                },
            )

            Spacer(modifier = Modifier.width(6.dp))

            SmallButton(
                icon = Icons.Rounded.Refresh,
                description = "Reset View",
                onClick = {
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { it.resetView() }
                },
            )

            Spacer(modifier = Modifier.width(6.dp))

            SmallButton(
                icon = Icons.Rounded.Menu,
                description = "Menu",
                onClick = {
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { it.toggleControlPanel() }
                },
            )
        }
        } // end inner content Column
    }
}

@Composable
fun SmallButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (isHovered) ButtonHover else ButtonBg)
            .clickable(onClick = onClick)
            .hoverable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (isHovered) AccentColor else TextPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

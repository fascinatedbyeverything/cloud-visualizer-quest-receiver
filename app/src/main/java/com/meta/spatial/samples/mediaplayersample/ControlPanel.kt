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
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.toolkit.SpatialActivityManager

private val BgColor = Color(0xFF0a0a0a)
private val TextPrimary = Color(0xFFe8e0d4)
private val TextSecondary = Color(0xFFc4a882)
private val AccentColor = Color(0xFFc4a882)
private val CardBg = Color(0xFF1a1a1a)
private val CardHover = Color(0xFF252525)
private val CardSelected = Color(0xFF2a2520)
private val ErrorColor = Color(0xFFff6b6b)
private val BufferingColor = Color(0xFF88aacc)

class ControlPanel : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ControlPanelUI() }
    }

    companion object {
        const val WIDTH_IN_DP = 420f
        const val HEIGHT_IN_DP = 680f
        const val WIDTH_IN_METERS = 1.3f
        const val HEIGHT_IN_METERS = 2.1f
        const val DPI = 260
    }
}

@Composable
fun ControlPanelUI() {
    var selectedIndex by remember { mutableStateOf(-1) }
    val isPlaying by PlayerState.isPlaying
    val isBuffering by PlayerState.isBuffering
    val currentTitle by PlayerState.currentTitle
    val videoWidth by PlayerState.videoWidth
    val videoHeight by PlayerState.videoHeight
    val bitrate by PlayerState.bitrate
    val errorMessage by PlayerState.errorMessage
    val transportVisible by PlayerState.transportVisible

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(BgColor)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "A Cloud to Float On",
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            color = TextPrimary,
            letterSpacing = 1.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "fascinated by everything presents",
            fontSize = 13.sp,
            color = TextSecondary,
            letterSpacing = 0.5.sp,
        )
        Text(
            text = "immersive mixtapes v3",
            fontSize = 13.sp,
            color = TextSecondary,
            letterSpacing = 0.5.sp,
        )

        Spacer(modifier = Modifier.height(32.dp))

        CloudPlayerActivity.STREAMS.forEachIndexed { index, stream ->
            StreamCard(
                title = stream.title,
                description = stream.description,
                isSelected = selectedIndex == index,
                onClick = {
                    selectedIndex = index
                    SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { activity ->
                        activity.playStream(stream.url, stream.title)
                    }
                },
            )
            if (index < CloudPlayerActivity.STREAMS.size - 1) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        if (currentTitle.isNotEmpty()) {
            Text(
                text = if (isBuffering) "Buffering..." else "Now Playing",
                fontSize = 12.sp,
                color = if (isBuffering) BufferingColor else TextSecondary,
            )
            Text(
                text = currentTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    icon = { tint ->
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = tint,
                            modifier = Modifier.size(36.dp),
                        )
                    },
                    onClick = {
                        SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { activity ->
                            activity.togglePlayPause()
                        }
                    },
                )

                Spacer(modifier = Modifier.width(20.dp))

                TransportButton(
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop",
                            tint = tint,
                            modifier = Modifier.size(32.dp),
                        )
                    },
                    onClick = {
                        selectedIndex = -1
                        SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { activity ->
                            activity.stopPlayback()
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!transportVisible) {
                TransportButton(
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Show Transport",
                            tint = tint,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                    onClick = {
                        SpatialActivityManager.executeOnVrActivity<CloudPlayerActivity> { activity ->
                            activity.showTransport()
                        }
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Show Transport",
                    fontSize = 11.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (videoWidth > 0) {
                val qualityLabel = when {
                    videoWidth >= 7680 -> "8K"
                    videoWidth >= 3840 -> "4K"
                    videoWidth >= 2560 -> "1440p"
                    videoWidth >= 1920 -> "1080p"
                    videoWidth >= 1280 -> "720p"
                    else -> "${videoWidth}x${videoHeight}"
                }
                val bitrateLabel = if (bitrate > 0) {
                    "${bitrate / 1_000_000}Mbps"
                } else ""

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QualityBadge(qualityLabel)
                    if (bitrateLabel.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = bitrateLabel,
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                fontSize = 12.sp,
                color = ErrorColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun StreamCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor = when {
        isSelected -> CardSelected
        isHovered -> CardHover
        else -> CardBg
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .hoverable(interactionSource = interactionSource)
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AccentColor),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) AccentColor else TextPrimary,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = TextSecondary,
            )
        }
    }
}

@Composable
fun TransportButton(
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (isHovered) CardHover else CardBg)
            .clickable(onClick = onClick)
            .hoverable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (isHovered) AccentColor else TextPrimary)
    }
}

@Composable
fun QualityBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AccentColor.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = AccentColor,
        )
    }
}

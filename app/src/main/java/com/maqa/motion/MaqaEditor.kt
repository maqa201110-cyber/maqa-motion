package com.maqa.motion

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Bg = Color(0xFF0E0E12)
private val Panel = Color(0xFF17171D)

data class TextLayer(val id: Int, val text: String, val x: Float, val y: Float)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EditorScreen()
            }
        }
    }
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var layers by remember { mutableStateOf(listOf<TextLayer>()) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }

    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            delay(50)
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(1500)
            message = null
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            duration = 0L
            position = 0L
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = false
        }
    }

    val pickVideo = {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    val onTool: (String) -> Unit = { name ->
        when (name) {
            "+ Video" -> pickVideo()
            "Metin" -> {
                if (videoUri == null) {
                    message = "Önce video ekle"
                } else {
                    layers = layers + TextLayer(layers.size, "Maqa Motion", 150f, 250f)
                }
            }
            else -> message = "$name yakında"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
    ) {
        // Üst bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Maqa Motion",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { message = "Dışa aktarma yakında" }) {
                Text("Dışa aktar")
            }
        }

        // Önizleme
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (videoUri == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Projeye video ekle", color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pickVideo() }) { Text("+ Video ekle") }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { PlayerView(it).apply { useController = false } },
                    update = { it.player = player }
                )
                layers.forEach { layer ->
                    key(layer.id) {
                        DraggableText(layer) { dx, dy ->
                            layers = layers.map {
                                if (it.id == layer.id) it.copy(x = it.x + dx, y = it.y + dy) else it
                            }
                        }
                    }
                }
            }
            val msg = message
            if (msg != null) {
                Text(
                    msg,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Oynatma çubuğu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${fmt(position)} / ${fmt(duration)}",
                color = Color.White,
                fontSize = 13.sp
            )
            Spacer(Modifier.weight(1f))
            Button(
                enabled = videoUri != null,
                onClick = {
                    if (isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }
            ) { Text(if (isPlaying) "⏸" else "▶") }
        }

        // Zaman çizelgesi
        Timeline(
            position = position,
            duration = duration,
            hasVideo = videoUri != null,
            textCount = layers.size,
            onSeek = { ms ->
                player.seekTo(ms)
                position = ms
            }
        )

        // Araç çubuğu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("+ Video", "Kes", "Metin", "Katman", "Keyframe", "Efekt", "Ses", "Hız")
                .forEach { name -> ToolButton(name) { onTool(name) } }
        }
    }
}

@Composable
fun DraggableText(layer: TextLayer, onMove: (Float, Float) -> Unit) {
    Text(
        text = layer.text,
        color = Color.White,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .offset { IntOffset(layer.x.roundToInt(), layer.y.roundToInt()) }
            .pointerInput(layer.id) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onMove(dragAmount.x, dragAmount.y)
                }
            }
            .padding(8.dp)
    )
}

@Composable
fun Timeline(
    position: Long,
    duration: Long,
    hasVideo: Boolean,
    textCount: Int,
    onSeek: (Long) -> Unit
) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(vertical = 8.dp)
    ) {
        Slider(
            value = fraction,
            onValueChange = { onSeek((it * duration).toLong()) },
            enabled = hasVideo && duration > 0,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        TrackRow("Video", hasVideo, Color(0xFF7C4DFF))
        TrackRow("Metin", textCount > 0, Color(0xFFFFB300))
        TrackRow("Katman", false, Color(0xFF00BFA5))
    }
}

@Composable
fun TrackRow(label: String, active: Boolean, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(52.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (active) color else Color(0xFF2A2A33))
        )
    }
}

@Composable
fun ToolButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF26262F))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

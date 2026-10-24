package com.maqa.motion

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import android.view.View
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Bg = Color(0xFF0E0E12)
private val Panel = Color(0xFF17171D)
private val Accent = Color(0xFF7C4DFF)

/** Zaman çizelgesindeki bir klip: kaynak videodan [startMs, endMs) aralığı. */
data class Seg(
    val id: Int,
    val uri: Uri,
    val srcMs: Long,
    val startMs: Long,
    val endMs: Long,
    val speed: Float = 1f
) {
    val outMs: Long get() = ((endMs - startMs) / speed).toLong()
}

data class TextLayer(val id: Int, val text: String, val x: Float, val y: Float)

data class FilterPreset(
    val name: String,
    val sat: Float = 1f,
    val contrast: Float = 1f,
    val bright: Float = 0f,
    val warm: Float = 0f,
    val sepia: Boolean = false,
    val invert: Boolean = false
)

val FILTERS = listOf(
    FilterPreset("Orijinal"),
    FilterPreset("Siyah-Beyaz", sat = 0f),
    FilterPreset("Sepya", sepia = true),
    FilterPreset("Canlı", sat = 2f, contrast = 1.05f),
    FilterPreset("Soğuk", sat = 1.1f, warm = -0.2f),
    FilterPreset("Sıcak", sat = 1.1f, warm = 0.2f),
    FilterPreset("Vintage", sat = 0.7f, contrast = 0.9f, bright = 0.04f, warm = 0.15f),
    FilterPreset("Dramatik", sat = 1.2f, contrast = 1.35f, bright = -0.05f),
    FilterPreset("Color Grade", sat = 1.3f, contrast = 1.15f, warm = -0.08f),
    FilterPreset("Soluk", sat = 0.7f, contrast = 0.85f, bright = 0.08f),
    FilterPreset("Matte", sat = 0.9f, contrast = 0.8f, bright = 0.06f),
    FilterPreset("Negatif", invert = true),
    FilterPreset("Pop Art", sat = 2.4f, contrast = 1.4f),
    FilterPreset("Sinema", sat = 0.85f, contrast = 1.2f, bright = -0.03f, warm = -0.06f)
)

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

fun videoDuration(context: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        r.release()
    }
}

fun toItems(segs: List<Seg>): List<MediaItem> = segs.map { s ->
    MediaItem.Builder()
        .setUri(s.uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(s.startMs)
                .setEndPositionMs(s.endMs)
                .build()
        )
        .build()
}

/** Çıktı süresindeki konumu (klip sırası, klip içi medya konumu) çiftine çevirir. */
fun locate(segs: List<Seg>, globalMs: Long): Pair<Int, Long> {
    var acc = 0L
    for ((i, s) in segs.withIndex()) {
        if (globalMs < acc + s.outMs || i == segs.lastIndex) {
            val local = (globalMs - acc).coerceIn(0L, s.outMs)
            return Pair(i, (local * s.speed).toLong())
        }
        acc += s.outMs
    }
    return Pair(0, 0L)
}

fun buildMatrix(f: FilterPreset, bright: Float, contrast: Float, sat: Float, warm: Float): FloatArray {
    val m = ColorMatrix()
    m.setSaturation((f.sat * sat).coerceAtLeast(0f))
    if (f.sepia) {
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    val c = f.contrast * contrast
    val t = (1f - c) * 127.5f + (f.bright + bright) * 255f
    m.postConcat(
        ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    val w = f.warm + warm
    m.postConcat(
        ColorMatrix(
            floatArrayOf(
                1f + w, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f - w, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    if (f.invert) {
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    return m.array
}

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    var segs by remember { mutableStateOf(listOf<Seg>()) }
    var nextId by remember { mutableStateOf(1) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var tool by remember { mutableStateOf("") }
    var layers by remember { mutableStateOf(listOf<TextLayer>()) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var message by remember { mutableStateOf<String?>(null) }
    var filterIdx by remember { mutableStateOf(0) }
    var bright by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }
    var sat by remember { mutableStateOf(1f) }
    var warm by remember { mutableStateOf(0f) }
    var vignette by remember { mutableStateOf(0f) }

    val player = remember { ExoPlayer.Builder(context).build() }
    val segsNow = rememberUpdatedState(segs)
    val total = segs.sumOf { it.outMs }
    val sel = segs.firstOrNull { it.id == selectedId } ?: segs.firstOrNull()
    val cm = remember(filterIdx, bright, contrast, sat, warm) {
        buildMatrix(FILTERS[filterIdx], bright, contrast, sat, warm)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Oynatma konumunu çıktı süresine çevirir, klibe göre hızı ayarlar.
    LaunchedEffect(player) {
        while (true) {
            val list = segsNow.value
            if (list.isNotEmpty()) {
                val idx = player.currentMediaItemIndex.coerceIn(0, list.lastIndex)
                var acc = 0L
                for (i in 0 until idx) acc += list[i].outMs
                val sp = list[idx].speed
                if (player.playbackParameters.speed != sp) player.setPlaybackSpeed(sp)
                position = acc + (player.currentPosition / sp).toLong()
            }
            delay(50)
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(1600)
            message = null
        }
    }

    fun commit(newSegs: List<Seg>, keepMs: Long) {
        segs = newSegs
        val tot = newSegs.sumOf { it.outMs }
        val keep = keepMs.coerceIn(0L, (tot - 1).coerceAtLeast(0L))
        val loc = locate(newSegs, keep)
        player.setMediaItems(toItems(newSegs), loc.first, loc.second)
        player.prepare()
        position = keep
    }

    fun updateSel(transform: (Seg) -> Seg) {
        val id = sel?.id ?: return
        commit(segs.map { if (it.id == id) transform(it) else it }, position)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val d = videoDuration(context, uri)
            if (d <= 0L) {
                message = "Video okunamadı"
            } else {
                val seg = Seg(nextId, uri, d, 0L, d)
                nextId += 1
                val wasEmpty = segs.isEmpty()
                commit(segs + seg, if (wasEmpty) 0L else position)
                selectedId = seg.id
            }
        }
    }

    val pickVideo = {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    val doSplit: () -> Unit = {
        var acc = 0L
        var done = false
        val out = mutableListOf<Seg>()
        for (s in segs) {
            val rel = position - acc
            if (!done && rel > 300L && rel < s.outMs - 300L) {
                val mid = s.startMs + (rel * s.speed).toLong()
                out.add(s.copy(endMs = mid))
                out.add(s.copy(id = nextId, startMs = mid))
                nextId += 1
                done = true
            } else {
                out.add(s)
            }
            acc += s.outMs
        }
        if (done) commit(out, position) else message = "Çizgiyi klibin ortasına getir"
    }

    val doDelete: () -> Unit = {
        val id = sel?.id
        if (id != null && segs.size > 1) {
            commit(segs.filter { it.id != id }, 0L)
            selectedId = null
        } else {
            message = "En az bir klip kalmalı"
        }
    }

    val onSeek: (Long) -> Unit = { ms ->
        val loc = locate(segs, ms)
        player.seekTo(loc.first, loc.second)
        position = ms
    }

    val onTool: (String) -> Unit = { name ->
        when (name) {
            "+ Video" -> pickVideo()
            "Efekt", "Ses", "Keyframe" -> message = "$name yakında"
            "Metin" -> {
                if (segs.isEmpty()) {
                    message = "Önce video ekle"
                } else {
                    layers = layers + TextLayer(layers.size, "Maqa Motion", 150f + 30f * layers.size, 250f)
                }
            }
            else -> {
                if (segs.isEmpty()) message = "Önce video ekle"
                else tool = if (tool == name) "" else name
            }
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Maqa Motion", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { message = "Dışa aktarma yakında" }) { Text("Dışa aktar") }
        }

        // Önizleme
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (segs.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Projeye video ekle", color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pickVideo() }) { Text("+ Video ekle") }
                }
            } else {
                Box(modifier = Modifier.aspectRatio(aspect)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TextureView(ctx).also { player.setVideoTextureView(it) }
                        },
                        update = { tv ->
                            val paint = Paint()
                            paint.colorFilter = ColorMatrixColorFilter(cm)
                            tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                        }
                    )
                    if (vignette > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawBehind {
                                    val r = sqrt(size.width * size.width + size.height * size.height) / 2f
                                    drawRect(
                                        brush = Brush.radialGradient(
                                            colorStops = arrayOf(
                                                0.45f to Color.Transparent,
                                                1f to Color.Black.copy(alpha = vignette)
                                            ),
                                            center = Offset(size.width / 2f, size.height / 2f),
                                            radius = r
                                        )
                                    )
                                }
                        )
                    }
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
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${fmt(position)} / ${fmt(total)}", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Button(
                enabled = segs.isNotEmpty(),
                onClick = {
                    if (isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                        player.play()
                    }
                }
            ) { Text(if (isPlaying) "⏸" else "▶") }
        }

        // Zaman çizelgesi
        Timeline(
            segs = segs,
            selectedId = sel?.id,
            position = position,
            total = total,
            onSeek = onSeek,
            onSelect = { selectedId = it }
        )

        // Araç paneli
        if (tool.isNotEmpty() && sel != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .heightIn(max = 190.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                when (tool) {
                    "Kes" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = doSplit) { Text("✂ Böl") }
                            Button(onClick = doDelete) { Text("🗑 Klibi sil") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Çizgiyi bölmek istediğin yere getir, Böl'e bas.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    "Kırp" -> TrimPanel(sel) { f -> updateSel(f) }
                    "Hız" -> {
                        Text("Seçili klip hızı", color = Color.Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f).forEach { v ->
                                ToolButton("${v}x", sel.speed == v) { updateSel { it.copy(speed = v) } }
                            }
                        }
                    }
                    "Filtre" -> {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FILTERS.forEachIndexed { i, f ->
                                ToolButton(f.name, filterIdx == i) { filterIdx = i }
                            }
                        }
                    }
                    "Ayar" -> {
                        AdjustSlider("Parlaklık", bright, -0.5f..0.5f) { bright = it }
                        AdjustSlider("Kontrast", contrast, 0.5f..1.5f) { contrast = it }
                        AdjustSlider("Doygunluk", sat, 0f..2f) { sat = it }
                        AdjustSlider("Sıcaklık", warm, -0.3f..0.3f) { warm = it }
                        AdjustSlider("Vinyet", vignette, 0f..0.9f) { vignette = it }
                        Button(onClick = {
                            bright = 0f
                            contrast = 1f
                            sat = 1f
                            warm = 0f
                            vignette = 0f
                        }) { Text("Sıfırla") }
                    }
                }
            }
        }

        // Araç çubuğu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("+ Video", "Kes", "Kırp", "Hız", "Filtre", "Ayar", "Metin", "Efekt", "Ses", "Keyframe")
                .forEach { name -> ToolButton(name, tool == name) { onTool(name) } }
        }
    }
}

@Composable
fun TrimPanel(sel: Seg, onApply: ((Seg) -> Seg) -> Unit) {
    var a by remember(sel.id, sel.startMs) { mutableStateOf(sel.startMs.toFloat()) }
    var b by remember(sel.id, sel.endMs) { mutableStateOf(sel.endMs.toFloat()) }
    Text("Başlangıç  ${fmt(a.toLong())}", color = Color.LightGray, fontSize = 12.sp)
    Slider(
        value = a,
        onValueChange = { a = it.coerceAtMost(b - 300f) },
        onValueChangeFinished = { onApply { s -> s.copy(startMs = a.toLong()) } },
        valueRange = 0f..sel.srcMs.toFloat()
    )
    Text("Bitiş  ${fmt(b.toLong())}", color = Color.LightGray, fontSize = 12.sp)
    Slider(
        value = b,
        onValueChange = { b = it.coerceAtLeast(a + 300f) },
        onValueChangeFinished = { onApply { s -> s.copy(endMs = b.toLong()) } },
        valueRange = 0f..sel.srcMs.toFloat()
    )
}

@Composable
fun AdjustSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(78.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
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
    segs: List<Seg>,
    selectedId: Int?,
    position: Long,
    total: Long,
    onSeek: (Long) -> Unit,
    onSelect: (Int) -> Unit
) {
    val fraction = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(vertical = 6.dp)
    ) {
        Slider(
            value = fraction,
            onValueChange = { onSeek((it * total).toLong()) },
            enabled = segs.isNotEmpty() && total > 0,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                segs.forEach { s ->
                    val isSel = s.id == selectedId
                    Box(
                        modifier = Modifier
                            .weight(maxOf(1f, s.outMs / 1000f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Accent else Color(0xFF3A3A48))
                            .clickable { onSelect(s.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (s.speed != 1f) "${s.speed}x" else fmt(s.outMs),
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * fraction)
                    .width(2.dp)
                    .height(46.dp)
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun ToolButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else Color(0xFF26262F))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

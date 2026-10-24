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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

private val Bg = Color(0xFF111116)
private val Panel = Color(0xFF1C1C24)
private val Accent = Color(0xFF00E5A8)
private val Dim = Color(0xFF6B6B78)
private const val MIN_LEN = 200L
private val PALETTE = listOf(0xFFFFFFFFL, 0xFFFFD600L, 0xFF00E5A8L, 0xFFFF4D6DL, 0xFF7C4DFFL, 0xFF29B6F6L, 0xFF000000L)

enum class LayerKind { VIDEO, TEXT, SHAPE }
enum class Prop { X, Y, SCALE, ROT, OPACITY }

/** t: katmanın başlangıcına göre yerel zaman (ms). */
data class Keyframe(val t: Long, val v: Float, val smooth: Boolean)

val DEFAULT_BASE: Map<Prop, Float> = mapOf(
    Prop.X to 0f, Prop.Y to 0f, Prop.SCALE to 1f, Prop.ROT to 0f, Prop.OPACITY to 1f
)

data class Layer(
    val id: Int,
    val kind: LayerKind,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val visible: Boolean = true,
    val uri: Uri? = null,
    val srcDurMs: Long = 0L,
    val trimStart: Long = 0L,
    val speed: Float = 1f,
    val filter: Int = 0,
    val text: String = "Yazı",
    val color: Long = 0xFFFFFFFFL,
    val round: Boolean = false,
    val base: Map<Prop, Float> = DEFAULT_BASE,
    val keys: Map<Prop, List<Keyframe>> = emptyMap()
) {
    val len: Long get() = endMs - startMs

    /** Verilen zamanda özelliğin değeri: anahtar kare yoksa taban değer, varsa aralarında yumuşak geçiş. */
    fun value(p: Prop, t: Long): Float {
        val b = base[p] ?: 0f
        val list = keys[p] ?: return b
        if (list.isEmpty()) return b
        val lt = t - startMs
        if (lt <= list.first().t) return list.first().v
        if (lt >= list.last().t) return list.last().v
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val c = list[i + 1]
            if (lt >= a.t && lt <= c.t) {
                val f = (lt - a.t).toFloat() / (c.t - a.t).coerceAtLeast(1L)
                val e = if (a.smooth) f * f * (3f - 2f * f) else f
                return a.v + (c.v - a.v) * e
            }
        }
        return b
    }
}

data class Look(
    val name: String,
    val sat: Float = 1f,
    val contrast: Float = 1f,
    val bright: Float = 0f,
    val warm: Float = 0f,
    val sepia: Boolean = false,
    val invert: Boolean = false
)

val LOOKS = listOf(
    Look("Orijinal"),
    Look("Siyah-Beyaz", sat = 0f),
    Look("Sepya", sepia = true),
    Look("Canlı", sat = 1.8f, contrast = 1.08f),
    Look("Soğuk", warm = -0.2f),
    Look("Sıcak", warm = 0.2f),
    Look("Vintage", sat = 0.7f, contrast = 0.9f, bright = 0.04f, warm = 0.15f),
    Look("Negatif", invert = true)
)

fun lookMatrix(f: Look): ColorMatrix {
    val m = ColorMatrix()
    m.setSaturation(f.sat)
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
    val c = f.contrast
    val t = (1f - c) * 127.5f + f.bright * 255f
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
    val w = f.warm
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
    return m
}

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

fun tc(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 60, s % 60, (ms % 1000) / 10)
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

fun activeVideo(list: List<Layer>, t: Long): Layer? =
    list.lastOrNull { it.kind == LayerKind.VIDEO && it.visible && t >= it.startMs && t < it.endMs }

fun upsert(list: List<Keyframe>, lt: Long, v: Float, smooth: Boolean): List<Keyframe> {
    val m = list.toMutableList()
    val i = m.indexOfFirst { abs(it.t - lt) < 50L }
    if (i >= 0) {
        m[i] = m[i].copy(v = v)
    } else {
        m.add(Keyframe(lt, v, smooth))
    }
    m.sortBy { it.t }
    return m
}

fun barColor(l: Layer): Color = when (l.kind) {
    LayerKind.VIDEO -> Color(0xFF2A9D8F)
    LayerKind.TEXT -> Color(0xFFE9A800)
    LayerKind.SHAPE -> Color(0xFF7C4DFF)
}

@Composable
fun IconBtn(symbol: String, big: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (big) 50.dp else 38.dp)
            .clip(CircleShape)
            .background(if (big) Accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = if (big) Color.Black else Color.White,
            fontSize = if (big) 22.sp else 18.sp
        )
    }
}

@Composable
fun ToolBtn(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else Color(0xFF2A2A33))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 12.sp)
    }
}

@Composable
fun PropSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text("%.2f".format(value), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(44.dp))
    }
}

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current.density

    var layers by remember { mutableStateOf(listOf<Layer>()) }
    var past by remember { mutableStateOf(listOf<List<Layer>>()) }
    var future by remember { mutableStateOf(listOf<List<Layer>>()) }
    var nextId by remember { mutableStateOf(1) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var playhead by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var pxPerSec by remember { mutableStateOf(70f) }
    var panel by remember { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    var autoKey by remember { mutableStateOf(false) }
    var smooth by remember { mutableStateOf(true) }
    var compAspect by remember { mutableStateOf(9f / 16f) }
    var videoAspect by remember { mutableStateOf(16f / 9f) }
    var loadedId by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("Yeni Proje") }
    var message by remember { mutableStateOf<String?>(null) }
    val tlScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun totalMs(): Long = max(layers.maxOfOrNull { it.endMs } ?: 0L, 1000L)

    fun selLayer(): Layer? = layers.firstOrNull { it.id == selectedId }

    fun editLayer(id: Int, f: (Layer) -> Layer) {
        layers = layers.map { if (it.id == id) f(it) else it }
    }

    fun pushHistory() {
        past = past.takeLast(59) + listOf(layers)
        future = emptyList()
    }

    fun commit(newList: List<Layer>) {
        pushHistory()
        layers = newList
    }

    fun undo() {
        val prev = past.lastOrNull() ?: return
        future = future + listOf(layers)
        past = past.dropLast(1)
        layers = prev
    }

    fun redo() {
        val nxt = future.lastOrNull() ?: return
        past = past + listOf(layers)
        future = future.dropLast(1)
        layers = nxt
    }

    fun beginEdit() {
        if (!editing) {
            pushHistory()
            editing = true
        }
    }

    fun syncPlayer(t: Long, play: Boolean, force: Boolean) {
        val act = activeVideo(layers, t)
        if (act == null) {
            player.playWhenReady = false
            loadedId = -1
            return
        }
        val uri = act.uri ?: return
        val pos = act.trimStart + ((t - act.startMs) * act.speed).toLong()
        if (loadedId != act.id) {
            player.setMediaItem(MediaItem.fromUri(uri), pos)
            player.prepare()
            loadedId = act.id
        } else if (force) {
            player.seekTo(pos)
        }
        player.setPlaybackSpeed(act.speed)
        player.playWhenReady = play
    }

    fun scrubTo(t: Long) {
        playing = false
        playhead = t.coerceIn(0L, totalMs())
        syncPlayer(playhead, false, true)
    }

    fun togglePlay() {
        if (playing) {
            playing = false
            syncPlayer(playhead, false, false)
        } else {
            if (playhead >= totalMs()) playhead = 0L
            playing = true
            syncPlayer(playhead, true, true)
        }
    }

    fun addLayer(l: Layer) {
        commit(layers + l)
        selectedId = l.id
        nextId += 1
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val d = videoDuration(context, uri)
            if (d <= 0L) {
                message = "Video okunamadı"
            } else {
                addLayer(
                    Layer(
                        id = nextId,
                        kind = LayerKind.VIDEO,
                        name = "Video $nextId",
                        startMs = playhead,
                        endMs = playhead + d,
                        uri = uri,
                        srcDurMs = d
                    )
                )
                scrubTo(playhead)
            }
        }
    }

    fun pickVideo() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    fun addText() {
        addLayer(
            Layer(
                id = nextId,
                kind = LayerKind.TEXT,
                name = "Metin $nextId",
                startMs = playhead,
                endMs = playhead + 3000L,
                text = "Maqa Motion",
                color = PALETTE[nextId % PALETTE.size]
            )
        )
    }

    fun addShape(round: Boolean) {
        addLayer(
            Layer(
                id = nextId,
                kind = LayerKind.SHAPE,
                name = if (round) "Daire $nextId" else "Kare $nextId",
                startMs = playhead,
                endMs = playhead + 3000L,
                round = round,
                color = PALETTE[nextId % PALETTE.size]
            )
        )
    }

    fun moveBy(id: Int, ms: Long) {
        editLayer(id) { l ->
            val ns = max(0L, l.startMs + ms)
            l.copy(startMs = ns, endMs = l.endMs + (ns - l.startMs))
        }
    }

    fun trimLeft(id: Int, ms: Long) {
        editLayer(id) { l ->
            var d = ms
            if (l.startMs + d < 0L) d = -l.startMs
            if (l.len - d < MIN_LEN) d = l.len - MIN_LEN
            if (l.kind == LayerKind.VIDEO && l.trimStart + (d * l.speed).toLong() < 0L) {
                d = ((-l.trimStart) / l.speed).toLong()
            }
            l.copy(
                startMs = l.startMs + d,
                trimStart = if (l.kind == LayerKind.VIDEO) l.trimStart + (d * l.speed).toLong() else l.trimStart
            )
        }
    }

    fun trimRight(id: Int, ms: Long) {
        editLayer(id) { l ->
            var d = ms
            if (l.len + d < MIN_LEN) d = MIN_LEN - l.len
            if (l.kind == LayerKind.VIDEO) {
                val maxLen = ((l.srcDurMs - l.trimStart) / l.speed).toLong()
                if (l.len + d > maxLen) d = maxLen - l.len
            }
            l.copy(endMs = l.endMs + d)
        }
    }

    fun setProp(id: Int, p: Prop, v: Float) {
        editLayer(id) { l ->
            val animated = !(l.keys[p] ?: emptyList()).isEmpty()
            if (autoKey || animated) {
                val lt = (playhead - l.startMs).coerceIn(0L, l.len)
                l.copy(keys = l.keys + (p to upsert(l.keys[p] ?: emptyList(), lt, v, smooth)))
            } else {
                l.copy(base = l.base + (p to v))
            }
        }
    }

    fun addKeysNow() {
        val l = selLayer()
        if (l == null) {
            message = "Önce bir katman seç"
            return
        }
        val lt = (playhead - l.startMs).coerceIn(0L, l.len)
        editLayer(l.id) { x ->
            var nk = x.keys
            for (p in Prop.entries) {
                val v = x.value(p, playhead)
                nk = nk + (p to upsert(nk[p] ?: emptyList(), lt, v, smooth))
            }
            x.copy(keys = nk)
        }
    }

    fun clearKeys() {
        val l = selLayer() ?: return
        editLayer(l.id) { x ->
            x.copy(keys = emptyMap(), base = Prop.entries.associateWith { x.value(it, playhead) })
        }
    }

    fun splitSelected() {
        val l = selLayer()
        if (l == null) {
            message = "Önce bir katman seç"
            return
        }
        if (playhead <= l.startMs + MIN_LEN || playhead >= l.endMs - MIN_LEN) {
            message = "Kesim çizgisini katmanın ortasına getir"
            return
        }
        val cut = playhead - l.startMs
        val a = l.copy(
            endMs = playhead,
            keys = l.keys.mapValues { (_, ks) -> ks.filter { it.t < cut } }
        )
        val b = l.copy(
            id = nextId,
            name = l.name + " 2",
            startMs = playhead,
            trimStart = if (l.kind == LayerKind.VIDEO) l.trimStart + (cut * l.speed).toLong() else l.trimStart,
            keys = l.keys.mapValues { (_, ks) -> ks.filter { it.t >= cut }.map { it.copy(t = it.t - cut) } }
        )
        nextId += 1
        val list = layers.toMutableList()
        val idx = list.indexOfFirst { it.id == l.id }
        list[idx] = a
        list.add(idx + 1, b)
        commit(list)
        selectedId = a.id
    }

    fun duplicateSelected() {
        val l = selLayer()
        if (l == null) {
            message = "Önce bir katman seç"
            return
        }
        val d = l.copy(id = nextId, name = l.name + " kopya", startMs = l.endMs, endMs = l.endMs + l.len)
        nextId += 1
        val list = layers.toMutableList()
        list.add(list.indexOfFirst { it.id == l.id } + 1, d)
        commit(list)
        selectedId = d.id
    }

    fun deleteSelected() {
        val l = selLayer()
        if (l == null) {
            message = "Önce bir katman seç"
            return
        }
        commit(layers.filter { it.id != l.id })
        selectedId = null
    }

    fun moveZ(front: Boolean) {
        val l = selLayer()
        if (l == null) {
            message = "Önce bir katman seç"
            return
        }
        val rest = layers.filter { it.id != l.id }
        commit(if (front) rest + l else listOf(l) + rest)
    }

    fun setSpeed(sp: Float) {
        val l = selLayer() ?: return
        val newLen = (l.len * l.speed / sp).toLong()
        commit(layers.map { if (it.id == l.id) it.copy(speed = sp, endMs = it.startMs + newLen) else it })
    }

    fun setFilter(i: Int) {
        val l = selLayer() ?: return
        commit(layers.map { if (it.id == l.id) it.copy(filter = i) else it })
    }

    fun onTool(name: String) {
        when (name) {
            "Kes" -> splitSelected()
            "Kopyala" -> duplicateSelected()
            "Öne" -> moveZ(true)
            "Arkaya" -> moveZ(false)
            "Sil" -> deleteSelected()
            else -> panel = if (panel == name) "" else name
        }
    }

    LaunchedEffect(playing) {
        if (playing) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000L
                last = now
                val cur = playhead
                val actNow = activeVideo(layers, cur)
                var next = cur + dt
                if (actNow != null && loadedId == actNow.id && player.isPlaying) {
                    next = actNow.startMs + ((player.currentPosition - actNow.trimStart) / actNow.speed).toLong()
                }
                if (next >= totalMs()) {
                    playhead = totalMs()
                    playing = false
                    syncPlayer(playhead, false, false)
                    break
                }
                playhead = next
                val before = actNow?.id
                val after = activeVideo(layers, next)?.id
                if (before != after) syncPlayer(next, true, false)
            }
        }
    }

    LaunchedEffect(layers) {
        if (!playing) syncPlayer(playhead, false, true)
    }

    LaunchedEffect(selectedId, panel) {
        editing = false
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(1600)
            message = null
        }
    }

    val total = totalMs()
    val sel = selLayer()
    val tlHeight = (28 + 44 * layers.size).dp
    val contentW = ((total + 6000L) / 1000f * pxPerSec).dp
    val phX = (playhead / 1000f * pxPerSec).dp
    val act = activeVideo(layers, playhead)

    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {

            // Üst bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn("←") { message = "Çıkmak için geri tuşunu kullan" }
                BasicTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                IconBtn("↗") { message = "Dışa aktarma sonraki aşamada" }
            }

            // Önizleme
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0B0B0F)),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(
                    Modifier.aspectRatio(compAspect).background(Color.Black).clipToBounds()
                ) {
                    val wPx = with(LocalDensity.current) { maxWidth.toPx() }
                    val hPx = with(LocalDensity.current) { maxHeight.toPx() }

                    // Video (en altta, tek aktif video)
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val a = act
                            alpha = if (a == null) 0f else a.value(Prop.OPACITY, playhead)
                            if (a != null) {
                                translationX = a.value(Prop.X, playhead) * wPx / 2f
                                translationY = a.value(Prop.Y, playhead) * hPx / 2f
                                scaleX = a.value(Prop.SCALE, playhead)
                                scaleY = a.value(Prop.SCALE, playhead)
                                rotationZ = a.value(Prop.ROT, playhead)
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.aspectRatio(videoAspect)) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    TextureView(ctx).also { player.setVideoTextureView(it) }
                                },
                                update = { tv ->
                                    val f = act?.filter ?: 0
                                    if (tv.tag != f) {
                                        val paint = Paint()
                                        paint.colorFilter = ColorMatrixColorFilter(lookMatrix(LOOKS[f]))
                                        tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                                        tv.tag = f
                                    }
                                }
                            )
                        }
                    }

                    // Yazı ve şekiller (videonun üstünde)
                    layers
                        .filter { it.kind != LayerKind.VIDEO && it.visible && playhead >= it.startMs && playhead < it.endMs }
                        .forEach { l ->
                            key(l.id) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer {
                                        translationX = l.value(Prop.X, playhead) * wPx / 2f
                                        translationY = l.value(Prop.Y, playhead) * hPx / 2f
                                        scaleX = l.value(Prop.SCALE, playhead)
                                        scaleY = l.value(Prop.SCALE, playhead)
                                        rotationZ = l.value(Prop.ROT, playhead)
                                        alpha = l.value(Prop.OPACITY, playhead)
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val selMod = if (selectedId == l.id) Modifier.border(1.dp, Accent) else Modifier
                                    if (l.kind == LayerKind.TEXT) {
                                        Text(
                                            l.text,
                                            color = Color(l.color),
                                            fontSize = 30.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = selMod.padding(8.dp)
                                        )
                                    } else {
                                        Box(
                                            Modifier
                                                .size(140.dp)
                                                .clip(if (l.round) CircleShape else RoundedCornerShape(10.dp))
                                                .background(Color(l.color))
                                                .then(selMod)
                                        )
                                    }
                                }
                            }
                        }

                    if (layers.isEmpty()) {
                        Text(
                            "Alttaki + ile katman ekle",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            // Kontroller
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconBtn("↶") { undo() }
                IconBtn("↷") { redo() }
                IconBtn("⏮") { scrubTo(0L) }
                IconBtn(if (playing) "❚❚" else "▶", big = true) { togglePlay() }
                IconBtn("⏭") { scrubTo(total) }
                IconBtn("◆") { addKeysNow() }
                Text("${tc(playhead)} / ${tc(total)}", color = Color.White, fontSize = 11.sp)
            }

            // Zaman çizelgesi
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(vScroll)
            ) {
                if (layers.isEmpty()) {
                    Text(
                        "Henüz katman yok",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    // Sol: göz ikonu ve katman adları (sabit)
                    Column(Modifier.width(96.dp)) {
                        Spacer(Modifier.height(28.dp))
                        layers.asReversed().forEach { l ->
                            key(l.id) {
                                Row(
                                    Modifier
                                        .height(44.dp)
                                        .fillMaxWidth()
                                        .clickable { selectedId = l.id }
                                        .padding(start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (l.visible) "◉" else "○",
                                        color = Accent,
                                        fontSize = 16.sp,
                                        modifier = Modifier
                                            .clickable {
                                                commit(layers.map { if (it.id == l.id) it.copy(visible = !it.visible) else it })
                                            }
                                            .padding(6.dp)
                                    )
                                    Text(
                                        l.name,
                                        color = if (selectedId == l.id) Accent else Color.White,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Sağ: yatay kaydırılabilen cetvel ve parçalar
                    Box(Modifier.weight(1f).horizontalScroll(tlScroll)) {
                        Box(Modifier.width(contentW).height(tlHeight)) {
                            Column(Modifier.width(contentW)) {
                                // Cetvel: dokun veya sürükle, oynatma kafası taşınır
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(28.dp)
                                        .pointerInput(Unit) {
                                            detectTapGestures { o ->
                                                scrubTo(((o.x / density) / (pxPerSec / 1000f)).toLong())
                                            }
                                        }
                                        .pointerInput(Unit) {
                                            detectDragGestures { ch, _ ->
                                                ch.consume()
                                                scrubTo(((ch.position.x / density) / (pxPerSec / 1000f)).toLong())
                                            }
                                        }
                                ) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val step = pxPerSec * density
                                        var s = 0
                                        while (s * step <= size.width) {
                                            val x = s * step
                                            val h = if (s % 5 == 0) 14f * density else 7f * density
                                            drawLine(
                                                color = Dim,
                                                start = Offset(x, size.height),
                                                end = Offset(x, size.height - h),
                                                strokeWidth = 1f * density
                                            )
                                            s++
                                        }
                                    }
                                }

                                // Katman parçaları (her katman kendi satırında)
                                layers.asReversed().forEach { l ->
                                    key(l.id) {
                                        val barW = (l.len / 1000f * pxPerSec).dp
                                        val barX = (l.startMs / 1000f * pxPerSec).dp
                                        val barWpx = barW.value * density
                                        val wState = rememberUpdatedState(barWpx)
                                        Box(
                                            Modifier
                                                .width(contentW)
                                                .height(44.dp)
                                                .pointerInput(Unit) {
                                                    detectTapGestures { o ->
                                                        selectedId = null
                                                        scrubTo(((o.x / density) / (pxPerSec / 1000f)).toLong())
                                                    }
                                                }
                                        ) {
                                            // Parça: sol/sağ kenardan kırp, ortadan taşı
                                            Box(
                                                Modifier
                                                    .offset(x = barX, y = 5.dp)
                                                    .width(barW)
                                                    .height(34.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(barColor(l))
                                                    .border(
                                                        if (selectedId == l.id) 2.dp else 0.dp,
                                                        Color.White,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .pointerInput(l.id) {
                                                        var mode = 0
                                                        var carry = 0f
                                                        detectDragGestures(
                                                            onDragStart = { off ->
                                                                val edge = 14f * density
                                                                val w = wState.value
                                                                mode = when {
                                                                    off.x < edge -> 1
                                                                    off.x > w - edge -> 2
                                                                    else -> 0
                                                                }
                                                                selectedId = l.id
                                                                beginEdit()
                                                            },
                                                            onDrag = { ch, drag ->
                                                                ch.consume()
                                                                carry += (drag.x / density) / (pxPerSec / 1000f)
                                                                val whole = carry.toLong()
                                                                carry -= whole
                                                                if (whole != 0L) {
                                                                    when (mode) {
                                                                        0 -> moveBy(l.id, whole)
                                                                        1 -> trimLeft(l.id, whole)
                                                                        else -> trimRight(l.id, whole)
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                                    .pointerInput(l.id) {
                                                        detectTapGestures { selectedId = l.id }
                                                    },
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Text(
                                                    l.name,
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 8.dp)
                                                )
                                            }
                                            // Anahtar kare işaretleri (elmas)
                                            val times = l.keys.values.flatten().map { it.t }.distinct()
                                            times.forEach { kt ->
                                                Box(
                                                    Modifier
                                                        .offset(x = ((l.startMs + kt) / 1000f * pxPerSec).dp - 4.dp, y = 18.dp)
                                                        .size(8.dp)
                                                        .graphicsLayer { rotationZ = 45f }
                                                        .background(Color.White)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Oynatma kafası ve zaman balonu
                            Box(
                                Modifier
                                    .offset(x = phX)
                                    .width(2.dp)
                                    .height(tlHeight)
                                    .background(Color.White)
                            )
                            Box(
                                Modifier
                                    .offset(x = phX - 30.dp, y = 2.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF2A2A33))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(tc(playhead), color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            // Alt panel (araca göre açılır)
            if (panel.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp)
                        .background(Panel)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (panel == "Oran") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("9:16" to 9f / 16f, "1:1" to 1f, "16:9" to 16f / 9f, "4:5" to 4f / 5f)
                                .forEach { (name, v) ->
                                    ToolBtn(name, compAspect == v) { compAspect = v }
                                }
                        }
                    } else if (panel == "Zum") {
                        Text("Zaman çizelgesi yakınlığı", color = Color.Gray, fontSize = 12.sp)
                        Slider(
                            value = pxPerSec,
                            onValueChange = { pxPerSec = it },
                            valueRange = 20f..200f
                        )
                    } else if (panel == "Hız") {
                        if (sel != null && sel.kind == LayerKind.VIDEO) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.5f, 1f, 1.5f, 2f, 3f).forEach { sp ->
                                    ToolBtn("${sp}x", sel.speed == sp) { setSpeed(sp) }
                                }
                            }
                        } else {
                            Text("Hız için bir video katmanı seç", color = Color.Gray, fontSize = 12.sp)
                        }
                    } else if (panel == "Filtre") {
                        if (sel != null && sel.kind == LayerKind.VIDEO) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                LOOKS.forEachIndexed { i, f ->
                                    ToolBtn(f.name, sel.filter == i) { setFilter(i) }
                                }
                            }
                        } else {
                            Text("Filtre için bir video katmanı seç", color = Color.Gray, fontSize = 12.sp)
                        }
                    } else if (panel == "Özellik") {
                        if (sel == null) {
                            Text("Önce bir katman seç", color = Color.Gray, fontSize = 12.sp)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ToolBtn("◆ Anahtar", false) { addKeysNow() }
                                ToolBtn(if (autoKey) "Oto: Açık" else "Oto: Kapalı", autoKey) { autoKey = !autoKey }
                                ToolBtn(if (smooth) "Yumuşak" else "Düz", smooth) { smooth = !smooth }
                                ToolBtn("Anahtarları sil", false) { clearKeys() }
                            }
                            PropSlider(
                                "Konum X", sel.value(Prop.X, playhead), -1f..1f,
                                { v -> beginEdit(); setProp(sel.id, Prop.X, v) },
                                { editing = false }
                            )
                            PropSlider(
                                "Konum Y", sel.value(Prop.Y, playhead), -1f..1f,
                                { v -> beginEdit(); setProp(sel.id, Prop.Y, v) },
                                { editing = false }
                            )
                            PropSlider(
                                "Ölçek", sel.value(Prop.SCALE, playhead), 0.1f..3f,
                                { v -> beginEdit(); setProp(sel.id, Prop.SCALE, v) },
                                { editing = false }
                            )
                            PropSlider(
                                "Döndürme", sel.value(Prop.ROT, playhead), -180f..180f,
                                { v -> beginEdit(); setProp(sel.id, Prop.ROT, v) },
                                { editing = false }
                            )
                            PropSlider(
                                "Saydamlık", sel.value(Prop.OPACITY, playhead), 0f..1f,
                                { v -> beginEdit(); setProp(sel.id, Prop.OPACITY, v) },
                                { editing = false }
                            )
                            if (sel.kind == LayerKind.TEXT) {
                                OutlinedTextField(
                                    value = sel.text,
                                    onValueChange = { t ->
                                        beginEdit()
                                        editLayer(sel.id) { it.copy(text = t) }
                                    },
                                    label = { Text("Metin") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (sel.kind != LayerKind.VIDEO) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PALETTE.forEach { c ->
                                        Box(
                                            Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(Color(c))
                                                .clickable {
                                                    commit(layers.map { if (it.id == sel.id) it.copy(color = c) else it })
                                                }
                                        )
                                    }
                                }
                            }
                            if (sel.kind == LayerKind.SHAPE) {
                                ToolBtn(if (sel.round) "Dikdörtgen yap" else "Daire yap", false) {
                                    commit(layers.map { if (it.id == sel.id) it.copy(round = !it.round) else it })
                                }
                            }
                        }
                    }
                }
            }

            // Araç çubuğu
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .horizontalScroll(rememberScrollState())
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Kes", "Kopyala", "Öne", "Arkaya", "Sil", "Özellik", "Hız", "Filtre", "Oran", "Zum")
                    .forEach { name ->
                        ToolBtn(name, panel == name) { onTool(name) }
                    }
            }
        }

        // Katman ekle butonu (+)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 120.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable { addMenu = true },
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = Color.Black, fontSize = 30.sp)
        }

        // Katman ekle menüsü
        if (addMenu) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
                    .clickable { addMenu = false }
            ) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Panel)
                        .clickable { }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Katman ekle", color = Color.White, fontWeight = FontWeight.Bold)
                    Button(onClick = { addMenu = false; pickVideo() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Video")
                    }
                    Button(onClick = { addMenu = false; addText() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Metin")
                    }
                    Button(onClick = { addMenu = false; addShape(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Dikdörtgen")
                    }
                    Button(onClick = { addMenu = false; addShape(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Daire")
                    }
                }
            }
        }

        // Bildirim
        message?.let { m ->
            Text(
                m,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

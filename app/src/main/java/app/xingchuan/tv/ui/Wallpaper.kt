package app.xingchuan.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.xingchuan.tv.core.Presets
import app.xingchuan.tv.core.WallpaperSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 壁纸层。位于所有内容之下，永远不会接收焦点。
 *
 * - 内置渐变：直接用 Compose 画，零成本
 * - 图片：解码到屏幕尺寸后适度模糊，玻璃面板叠上去才有“磨砂”观感
 * - 视频：MediaPlayer 输出到 TextureView，静音循环播放
 */
@Composable
fun WallpaperLayer(spec: WallpaperSpec, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        when (spec) {
            is WallpaperSpec.Preset -> PresetWallpaper(spec.id)
            WallpaperSpec.Nature -> NatureWallpaper()
            is WallpaperSpec.Picture -> PictureWallpaper(Uri.parse(spec.uri))
            is WallpaperSpec.Video -> VideoWallpaper(Uri.parse(spec.uri))
        }
        // 统一压暗 + 上暗下更暗，保证任何壁纸上文字都可读
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x70040A14),
                            Color(0x4D040A14),
                            Color(0x9E040A14),
                        )
                    )
                )
        )
    }
}

@Composable
private fun PresetWallpaper(id: String) {
    val preset = remember(id) { Presets.byId(id) }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(preset.colors)
            )
    ) {
        // 顶部一团柔光，避免纯渐变的“塑料感”
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(preset.glow.copy(alpha = 0.30f), Color.Transparent),
                        radius = 1400f,
                    )
                )
        )
    }
}

@Composable
private fun PictureWallpaper(uri: Uri) {
    val ctx = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { decodeScaled(ctx, uri, 1920) }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(18.dp),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(Modifier.fillMaxSize().background(Ink.Bg))
    }
}

private fun decodeScaled(ctx: Context, uri: Uri, targetW: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
} catch (e: Exception) {
    null
}

/**
 * 内置风光轮播。
 *
 * 图片放在 `assets/wallpapers/`，按文件名排序播放，所以增删图片不用改代码。
 * 每张停留 24 秒，切换时 1.5 秒交叉淡入，同时做极慢的推近 / 平移（Ken Burns），
 * 静止的风景在电视上才不会显得"卡住了"。
 *
 * 内存上做了两件事：解码时就地降采样（3840 宽的原图按 1/2 取，落 1920×1080），
 * 配置用 RGB_565 省一半；LruCache 只留 3 张（上一张 / 当前 / 下一张），
 * 一百多兆的相册型桌面不是这里要的东西。
 */
private const val NATURE_INTERVAL_MS = 24_000L
private const val NATURE_FADE_MS = 1_500

private object NatureFrames {
    private val cache = object : LruCache<String, ImageBitmap>(3) {}

    fun peek(name: String): ImageBitmap? = cache.get(name)

    fun load(ctx: Context, name: String, targetW: Int): ImageBitmap? {
        cache.get(name)?.let { return it }
        val bmp = decodeAsset(ctx, name, targetW) ?: return null
        cache.put(name, bmp)
        return bmp
    }

    private fun decodeAsset(ctx: Context, name: String, targetW: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        ctx.assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, opts) }
            ?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun NatureWallpaper() {
    val ctx = LocalContext.current
    val files = remember {
        runCatching { ctx.assets.list("wallpapers")?.sorted() }.getOrNull().orEmpty()
    }

    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Ink.Bg))
        return
    }

    // 降采样目标跟着面板走：1080p 电视解 1920，4K 电视解 2560（再往上内存不划算）
    val targetW = remember {
        ctx.resources.displayMetrics.widthPixels.coerceIn(1280, 2560)
    }

    var index by remember { mutableIntStateOf(0) }
    // 首批解码完成前先铺底色，避免首帧闪白
    var ready by remember { mutableIntStateOf(0) }

    LaunchedEffect(files) {
        withContext(Dispatchers.IO) {
            NatureFrames.load(ctx, files[0], targetW)
            if (files.size > 1) NatureFrames.load(ctx, files[1], targetW)
        }
        ready = 1
        while (true) {
            delay(NATURE_INTERVAL_MS)
            val next = (index + 1) % files.size
            index = next
            // 展示新的一张的同时，把再下一张解好，轮到它时不用等 IO
            withContext(Dispatchers.IO) {
                NatureFrames.load(ctx, files[(next + 1) % files.size], targetW)
            }
        }
    }

    Crossfade(
        targetState = index,
        animationSpec = tween(NATURE_FADE_MS),
        label = "nature",
    ) { i ->
        val bmp = if (ready > 0) NatureFrames.peek(files[i]) else null
        if (bmp == null) {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
        } else {
            val progress = remember(i) { Animatable(0f) }
            LaunchedEffect(i) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(NATURE_INTERVAL_MS.toInt(), easing = LinearEasing),
                )
            }
            val drift = if (i % 2 == 0) 1f else -1f
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1.03f + 0.05f * progress.value
                        scaleX = scale
                        scaleY = scale
                        translationX = drift * 26f * progress.value
                        translationY = -16f * progress.value
                    }
                    // 6dp：留住风景的层次，又不至于让玻璃面板显脏
                    .blur(6.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 视频壁纸：MediaPlayer + TextureView，静音循环。
 *
 * 不用 ExoPlayer 是为了少一个依赖；本地 mp4/h264 这类家用场景 MediaPlayer 完全够。
 * Surface 就绪后立刻 setSurface，播放器在 prepareAsync 回调里 start，避免时序竞争。
 */
@Composable
private fun VideoWallpaper(uri: Uri) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceReady by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) { MediaPlayer() }

    DisposableEffect(uri, lifecycleOwner) {
        // 部分固件在后台会暂停渲染，回前台后重新 start；未 prepare 完成时 start 会抛异常，吞掉即可
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && surfaceReady) {
                runCatching { if (!player.isPlaying) player.start() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                player.setOnPreparedListener(null)
                player.reset()
                player.release()
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        st: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        runCatching {
                            player.setSurface(Surface(st))
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            player.setDataSource(ctx, uri)
                            player.setOnPreparedListener { it.start() }
                            player.prepareAsync()
                            surfaceReady = true
                        }.onFailure { surfaceReady = false }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching { player.setSurface(null) }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )

    if (!surfaceReady) {
        // 视频没起来时先铺一层深色，不会闪白
        Box(Modifier.fillMaxSize().background(Ink.Bg))
    }
}

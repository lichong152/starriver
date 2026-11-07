package app.xingchuan.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.LruCache
import android.view.Surface
import android.widget.Toast
import android.view.TextureView
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.xingchuan.tv.core.Presets
import app.xingchuan.tv.core.WallpaperGroups
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
fun WallpaperLayer(
    spec: WallpaperSpec,
    soft: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (spec) {
            is WallpaperSpec.Preset -> PresetWallpaper(spec.id)
            is WallpaperSpec.Group -> GroupWallpaper(spec.id, soft)
            is WallpaperSpec.Picture -> PictureWallpaper(Uri.parse(spec.uri), soft)
            is WallpaperSpec.Video -> VideoWallpaper(Uri.parse(spec.uri), soft)
        }
        // 统一压暗 + 上下更暗，保证任何壁纸上文字都可读；中间层保持轻薄，风光才通透
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x70040A14),
                            Color(0x38040A14),
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

/** 壁纸解码状态。Pending 只是还没读完，Failed 才是真的读不出来——后者需要回退，不能停在黑屏上。 */
private enum class LoadState { Pending, Ok, Failed }

@Composable
private fun PictureWallpaper(uri: Uri, soft: Boolean) {
    val ctx = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var state by remember(uri) { mutableStateOf(LoadState.Pending) }

    // 柔焦模式下按更小的尺寸解码，显示时再拉大，得到柔化背景（API 31 以下的 blur 是空实现）
    LaunchedEffect(uri, soft) {
        bitmap = withContext(Dispatchers.IO) {
            decodeScaled(ctx, uri, if (soft) 640 else 1920)
        }
        state = if (bitmap == null) LoadState.Failed else LoadState.Ok
    }

    // 用户选过的图片被删掉、或授权失效时，回退到内置壁纸而不是停在一整屏黑色上
    LaunchedEffect(state) {
        if (state == LoadState.Failed) {
            Toast.makeText(ctx, "这张图片读不到了，已换回内置壁纸", Toast.LENGTH_LONG).show()
        }
    }

    val bmp = bitmap
    when {
        bmp != null -> Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        state == LoadState.Failed -> GroupWallpaper(WallpaperGroups.DefaultId, soft)
        else -> Box(Modifier.fillMaxSize().background(Ink.Bg))
    }
}

private fun decodeScaled(ctx: Context, uri: Uri, targetW: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
    val decodedW = bounds.outWidth / sample
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
        if (decodedW > targetW && targetW > 0) {
            inDensity = decodedW
            inTargetDensity = targetW
        }
    }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
} catch (e: Exception) {
    null
}

/**
 * 内置分组轮播。
 *
 * 图片放在 `assets/wallpapers/`，以 `<分组id>-` 开头；同一组内按文件名排序播放，
 * 所以增删图片不用改代码。每组 10 张，每张停留 30 秒，切换时 1.5 秒交叉淡入；
 * 壁纸保持静止，不做推近 / 平移等缓动。
 *
 * 内存上做了两件事：解码时就地降采样（按屏幕宽度取 1920 或 2560），
 * 配置用 RGB_565 省一半；LruCache 只留 3 张（上一张 / 当前 / 下一张），
 * 一百多兆的相册型桌面不是这里要的东西。
 */
private const val GROUP_INTERVAL_MS = 30_000L
private const val GROUP_FADE_MS = 1_500

private object GroupFrames {
    private val cache = object : LruCache<String, ImageBitmap>(3) {}

    private fun key(name: String, w: Int) = "$name@$w"

    fun peek(name: String, w: Int): ImageBitmap? = cache.get(key(name, w))

    fun load(ctx: Context, name: String, targetW: Int): ImageBitmap? {
        cache.get(key(name, targetW))?.let { return it }
        val bmp = decodeAsset(ctx, name, targetW) ?: return null
        cache.put(key(name, targetW), bmp)
        return bmp
    }

    private fun decodeAsset(ctx: Context, name: String, targetW: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, bounds) }
        // inSampleSize 只能按 2 的幂取，各组原图宽度不同（1920 / 2560）会导致柔化程度不一致；
        // 先用 sample 粗降，再用 inDensity 精确缩到 targetW，保证四组观感统一
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val decodedW = bounds.outWidth / sample
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
            if (decodedW > targetW && targetW > 0) {
                inDensity = decodedW
                inTargetDensity = targetW
            }
        }
        ctx.assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, opts) }
            ?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun GroupWallpaper(prefix: String, soft: Boolean) {
    val ctx = LocalContext.current
    val files = remember(prefix) {
        runCatching {
            ctx.assets.list("wallpapers")
                ?.filter { it.startsWith("$prefix-") }
                ?.sorted()
        }.getOrNull().orEmpty()
    }

    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Ink.Bg))
        return
    }

    // 降采样目标跟着面板走：1080p 电视解 1920，4K 电视解 2560（再往上内存不划算）。
    // 柔焦模式下只解 1/3 宽度，显示时放大——这是 API 31 以下唯一能真正柔化背景的做法。
    val decodeW = remember(soft) {
        val full = ctx.resources.displayMetrics.widthPixels.coerceIn(1280, 2560)
        if (soft) (full / 4).coerceAtLeast(480) else full
    }

    var index by remember(prefix) { mutableIntStateOf(0) }
    // 首批解码完成前先铺底色，避免首帧闪白
    var ready by remember(prefix, decodeW) { mutableIntStateOf(0) }

    LaunchedEffect(files, decodeW) {
        withContext(Dispatchers.IO) {
            GroupFrames.load(ctx, files[0], decodeW)
            if (files.size > 1) GroupFrames.load(ctx, files[1], decodeW)
        }
        ready = 1
        while (true) {
            delay(GROUP_INTERVAL_MS)
            val next = (index + 1) % files.size
            index = next
            // 展示新的一张的同时，把再下一张解好，轮到它时不用等 IO
            withContext(Dispatchers.IO) {
                GroupFrames.load(ctx, files[(next + 1) % files.size], decodeW)
            }
        }
    }

    Crossfade(
        targetState = index,
        animationSpec = tween(GROUP_FADE_MS),
        label = "group-$prefix",
    ) { i ->
        val bmp = if (ready > 0) GroupFrames.peek(files[i], decodeW) else null
        if (bmp == null) {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
        } else {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
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
private fun VideoWallpaper(uri: Uri, soft: Boolean) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceReady by remember(uri) { mutableStateOf(false) }
    var state by remember(uri) { mutableStateOf(LoadState.Pending) }
    val player = remember(uri) { MediaPlayer() }

    // 视频损坏 / 编码不支持时回退到内置壁纸，避免桌面停在一整屏黑色
    LaunchedEffect(state) {
        if (state == LoadState.Failed) {
            Toast.makeText(ctx, "这段视频播放不了，已换回内置壁纸", Toast.LENGTH_LONG).show()
        }
    }

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
                            player.setOnPreparedListener {
                                state = LoadState.Ok
                                it.start()
                            }
                            player.setOnErrorListener { _, _, _ ->
                                state = LoadState.Failed
                                true
                            }
                            player.prepareAsync()
                            surfaceReady = true
                        }.onFailure {
                            surfaceReady = false
                            state = LoadState.Failed
                        }
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

    when {
        state == LoadState.Failed -> GroupWallpaper(WallpaperGroups.DefaultId, soft)
        // 视频还没起来时先铺一层深色，不会闪白
        !surfaceReady -> Box(Modifier.fillMaxSize().background(Ink.Bg))
    }
}

package app.xingchuan.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 待机屏保。系统空闲到设定时间后由 DreamManager 拉起，任意按键退出。
 *
 * 用原生 View 而不是 Compose：DreamService 没有现成的 LifecycleOwner，
 * 在 Android 9 上挂 ComposeView 要手工补 ViewTreeLifecycleOwner / SavedState，
 * 屏保又只是「一块会动的画」，原生 View + 每秒一次的文本更新足够，也少一层风险。
 */
enum class ScreenSaverStyle(val key: String, val label: String, val hint: String) {
    Clock("clock", "大时钟", "深色渐变 + 超大时间"),
    Wallpaper("wall", "壁纸幻灯", "内置壁纸轮播 + 时间"),
    Stars("stars", "星空", "缓慢漂移的星点"),
    Minimal("minimal", "极简", "纯黑背景，角落小字"),
    Off("off", "关闭", "交还系统原有屏保"),
    ;

    fun next(): ScreenSaverStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byKey(key: String?): ScreenSaverStyle = entries.firstOrNull { it.key == key } ?: Clock
    }
}

/** 屏保偏好与系统设置写入。写 secure settings 需要 WRITE_SECURE_SETTINGS（由 adb 授权）。 */
object ScreenSaver {
    const val COMPONENT = "app.xingchuan.tv/.ScreensaverService"
    private const val SP = "starriver"
    private const val KEY_STYLE = "screensaver_style"
    private const val KEY_PREV = "screensaver_prev_component"

    fun style(ctx: Context): ScreenSaverStyle =
        ScreenSaverStyle.byKey(ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_STYLE, null))

    fun setStyle(ctx: Context, style: ScreenSaverStyle) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, style.key).apply()
    }

    /** 当前系统里挂的是不是星川的屏保。 */
    fun active(ctx: Context): Boolean = runCatching {
        Settings.Secure.getString(ctx.contentResolver, "screensaver_components")
    }.getOrNull()?.contains("app.xingchuan.tv") == true

    /**
     * 把当前选中的样式写到系统 screensaver_components。
     * @return false 表示没有 WRITE_SECURE_SETTINGS，需要用户用 adb 授权
     */
    fun applyToSystem(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        // 第一次接管时记下原来的屏保，选「关闭」时还回去
        if (sp.getString(KEY_PREV, null) == null) {
            val prev = runCatching {
                Settings.Secure.getString(ctx.contentResolver, "screensaver_components")
            }.getOrNull().orEmpty()
            if (prev.isNotEmpty() && !prev.contains("app.xingchuan.tv")) {
                sp.edit().putString(KEY_PREV, prev).apply()
            }
        }
        val style = style(ctx)
        val target = if (style == ScreenSaverStyle.Off) {
            sp.getString(KEY_PREV, null).orEmpty()
        } else {
            COMPONENT
        }
        return runCatching {
            Settings.Secure.putString(ctx.contentResolver, "screensaver_components", target)
            Settings.Secure.putInt(
                ctx.contentResolver,
                "screensaver_enabled",
                if (style == ScreenSaverStyle.Off) 0 else 1,
            )
            // 电视出厂常常是「永不待机」（screen_off_timeout = Integer.MAX_VALUE），
            // 那样屏保永远不会被触发。启用屏保时顺手给个 10 分钟，关掉时不动它。
            if (style != ScreenSaverStyle.Off) {
                val t = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0)
                if (t <= 0 || t > 30 * 60_000) {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 10 * 60_000)
                }
            }
            true
        }.getOrDefault(false)
    }
}

class ScreensaverService : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private var style: ScreenSaverStyle = ScreenSaverStyle.Clock
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var wallView: ImageView? = null
    private var starView: StarField? = null
    private var slides: List<String> = emptyList()
    private var slideIndex = 0

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockSecFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("M月d日  EEEE", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockView?.text = if (style == ScreenSaverStyle.Minimal) {
                clockSecFmt.format(now)
            } else {
                clockFmt.format(now)
            }
            dateView?.text = dateFmt.format(now)
            handler.postDelayed(this, 1_000)
        }
    }

    private val slide = object : Runnable {
        override fun run() {
            if (slides.isNotEmpty()) {
                slideIndex = (slideIndex + 1) % slides.size
                loadWallpaper(slides[slideIndex])
            }
            handler.postDelayed(this, SLIDE_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        style = ScreenSaver.style(this).let {
            if (it == ScreenSaverStyle.Off) ScreenSaverStyle.Clock else it
        }
        setContentView(build(style))
        handler.post(tick)
        if (style == ScreenSaverStyle.Wallpaper) {
            slides = runCatching { assets.list("wallpapers")?.sorted().orEmpty() }
                .getOrDefault(emptyList())
            slides.firstOrNull()?.let { loadWallpaper(it) }
            handler.postDelayed(slide, SLIDE_MS)
        }
        if (style == ScreenSaverStyle.Stars) starView?.start()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        handler.removeCallbacksAndMessages(null)
        starView?.stop()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        starView?.stop()
        super.onDetachedFromWindow()
    }

    private fun px(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    private fun build(style: ScreenSaverStyle): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        when (style) {
            ScreenSaverStyle.Clock -> {
                root.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.parseColor("#0A1224"),
                        Color.parseColor("#121E3A"),
                        Color.parseColor("#05070D"),
                    ),
                )
                val c = clock(150f)
                val d = sub(20f)
                clockView = c
                dateView = d
                root.addView(stack(c, d, 18f), centered())
            }

            ScreenSaverStyle.Wallpaper -> {
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                wallView = img
                root.addView(img, full())
                // 压暗，保证白字可读（与桌面同一套思路）
                root.addView(
                    View(this).apply { setBackgroundColor(Color.parseColor("#66040A14")) },
                    full(),
                )
                val c = clock(92f)
                val d = sub(18f)
                clockView = c
                dateView = d
                root.addView(stack(c, d, 10f), corner(Gravity.BOTTOM or Gravity.START, 72f, 52f))
            }

            ScreenSaverStyle.Stars -> {
                root.setBackgroundColor(Color.parseColor("#03040A"))
                val sf = StarField(this)
                starView = sf
                root.addView(sf, full())
                val c = clock(108f)
                val d = sub(18f)
                clockView = c
                dateView = d
                root.addView(stack(c, d, 14f), centered())
            }

            ScreenSaverStyle.Minimal, ScreenSaverStyle.Off -> {
                root.setBackgroundColor(Color.BLACK)
                val c = clock(44f)
                val d = sub(14f)
                clockView = c
                dateView = d
                root.addView(stack(c, d, 8f), corner(Gravity.BOTTOM or Gravity.END, 52f, 40f))
            }
        }
        return root
    }

    /** 时间 + 日期竖排。宽高都随内容，再由 FrameLayout 的 gravity 定位——
     *  之前直接把 MATCH_PARENT 的 LinearLayout 塞进去，内部 gravity 在部分 ROM 上不生效，
     *  时间会贴到左边（真机上就是这么歪的）。 */
    private fun stack(c: View, d: View, gap: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(c)
        addView(
            d,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(gap) },
        )
    }

    private fun centered() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
    )

    private fun corner(gravity: Int, h: Float, v: Float) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        gravity,
    ).apply {
        leftMargin = px(h)
        rightMargin = px(h)
        topMargin = px(v)
        bottomMargin = px(v)
    }

    private fun clock(sp: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor("#F3F6FF"))
        letterSpacing = 0.02f
        includeFontPadding = false
    }

    private fun sub(sp: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor("#B3C9D6EE"))
        letterSpacing = 0.06f
        includeFontPadding = false
    }

    private fun full() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun loadWallpaper(name: String) {
        Thread {
            val bmp: Bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull() ?: return@Thread
            handler.post {
                wallView?.apply {
                    alpha = 0f
                    setImageBitmap(bmp)
                    animate().alpha(1f).setDuration(900).start()
                }
            }
        }.start()
    }

    private companion object {
        const val SLIDE_MS = 20_000L
    }
}

/** 星空：一百多个亮度不同的点，整体缓慢横向漂移，各自独立闪烁。 */
private class StarField(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = List(160) {
        Triple(Random.nextFloat(), Random.nextFloat(), 1.0f + Random.nextFloat() * 2.2f)
    }
    private var shift = 0f
    private var phase = 0f

    private val ticker = object : Runnable {
        override fun run() {
            shift = (shift + 0.0004f) % 1f
            phase += 0.05f
            invalidate()
            postDelayed(this, 40)
        }
    }

    fun start() {
        removeCallbacks(ticker)
        post(ticker)
    }

    fun stop() = removeCallbacks(ticker)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        stars.forEachIndexed { i, star ->
            val px = ((star.first + shift) % 1f) * w
            val twinkle = 0.55f + 0.45f * abs(sin(phase + i * 0.7f))
            paint.color = Color.argb((twinkle * 200).toInt(), 255, 255, 255)
            canvas.drawCircle(px, star.second * h, star.third, paint)
        }
    }
}

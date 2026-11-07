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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
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
    Web("web", "家居大屏", "联网显示智能家居总览"),
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

    /**
     * 「家居大屏」屏保打开的地址（智能家居后台的 /screen 概览页）。
     * sharecode 是发布链接的访问码，会种一个 30 天有效的 cookie；tv=1 让页面
     * 自动换取只读会话（屏保不可交互，不能弹登录框），kiosk=1 由 DashWeb.url 追加。
     * 分享码换过之后改这一行即可——屏保与顶栏「大屏」共用同一地址。
     */
    const val WEB_URL = "https://smart-home-hub-07039.app.workbuddy.host/screen?sharecode=OVUS5VJB42&tv=1"

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

    /** 日期字号相对时钟的比例，由各样式在 [clockView] 里设定。 */
    private var dateRatio = 0.18f
    private var wallView: ImageView? = null
    private var starView: StarField? = null
    private var dashWeb: WebView? = null
    private var slides: List<String> = emptyList()
    private var slideIndex = 0

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockSecFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("M月d日  EEEE", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            // 家居大屏样式下时钟是被网页盖住的回退画面，隐藏时不必每秒重建 Spannable
            clockView?.let { if (it.visibility == View.VISIBLE) it.text = styled(Date()) }
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
        releaseDash()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        starView?.stop()
        releaseDash()
        super.onDetachedFromWindow()
    }

    /** 大屏用的 WebView 必须显式销毁，否则屏保退出后它还在后台跑 JS、占内存。 */
    private fun releaseDash() {
        dashWeb?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        dashWeb = null
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
                val c = clockText(150f, 20f, Gravity.CENTER)
                clockView = c
                root.addView(c, centered())
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
                val c = clockText(92f, 18f, Gravity.START or Gravity.BOTTOM)
                clockView = c
                root.addView(c, corner(Gravity.BOTTOM or Gravity.START, 72f, 52f))
            }

            ScreenSaverStyle.Stars -> {
                root.setBackgroundColor(Color.parseColor("#03040A"))
                val sf = StarField(this)
                starView = sf
                root.addView(sf, full())
                val c = clockText(108f, 18f, Gravity.CENTER)
                clockView = c
                root.addView(c, centered())
            }

            ScreenSaverStyle.Minimal, ScreenSaverStyle.Off -> {
                root.setBackgroundColor(Color.BLACK)
                val c = clockText(44f, 14f, Gravity.END or Gravity.BOTTOM)
                clockView = c
                root.addView(c, corner(Gravity.BOTTOM or Gravity.END, 52f, 40f))
            }

            // 联网把智能家居后台的 /screen 概览铺满整屏。
            // 断网、分享码失效、后台没开——都退到大时钟，并且每分钟自己重试一次，
            // 网络恢复后画面会自己回来，不需要人管。
            ScreenSaverStyle.Web -> {
                root.setBackgroundColor(DashWeb.BG)

                val fallback = clockText(96f, 18f, Gravity.CENTER).apply {
                    visibility = View.GONE
                }
                clockView = fallback
                root.addView(fallback, centered())

                val why = TextView(this).apply {
                    text = "没能连上智能家居大屏 · 正在重试"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.parseColor("#8FA3C0"))
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                }
                root.addView(
                    why,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM,
                    ).apply { bottomMargin = px(56f) },
                )

                val wv = DashWeb.create(
                    this,
                    kiosk = true,
                    onFail = {
                        fallback.visibility = View.VISIBLE
                        why.visibility = View.VISIBLE
                        fallback.text = styled(Date())
                        handler.postDelayed({ dashWeb?.reload() }, RETRY_MS)
                    },
                    onOk = {
                        fallback.visibility = View.GONE
                        why.visibility = View.GONE
                    },
                )
                dashWeb = wv
                root.addView(wv, full())
            }
        }
        return root
    }

    /**
     * 时间与日期合成同一个 TextView，日期用 RelativeSizeSpan 缩小。
     *
     * 早先是两个 TextView 竖排在 LinearLayout 里、靠外层 WRAP_CONTENT + gravity 定位；
     * 在这台电视的 ROM 上宽度被算错，时钟只剩「14」、日期整体偏左（v1.2.2 实测）。
     * 单 TextView + 自身 gravity 不依赖父容器测量，最稳。
     */
    private fun clockText(sp: Float, dateSp: Float, align: Int) = TextView(this).apply {
        dateRatio = dateSp / sp
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor("#F3F6FF"))
        letterSpacing = 0.02f
        includeFontPadding = false
        gravity = align
    }

    /** 时间 \n 日期，日期行缩到 [dateRatio] 并用浅色。 */
    private fun styled(now: Date): CharSequence {
        val t = if (style == ScreenSaverStyle.Minimal) clockSecFmt.format(now) else clockFmt.format(now)
        val d = dateFmt.format(now)
        val sb = SpannableStringBuilder(t).append('\n').append(d)
        val start = t.length + 1
        sb.setSpan(RelativeSizeSpan(dateRatio), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ForegroundColorSpan(Color.parseColor("#B3C9D6EE")),
            start,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return sb
    }

    private fun centered() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
    )

    private fun corner(gravity: Int, h: Float, v: Float) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        gravity,
    ).apply {
        leftMargin = px(h)
        rightMargin = px(h)
        topMargin = px(v)
        bottomMargin = px(v)
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

        /** 大屏加载失败后多久再试一次：网络多半是临时断的，给一分钟。 */
        const val RETRY_MS = 60_000L
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

package app.xingchuan.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 智能家居大屏的公共配置。屏保和顶栏「大屏」页共用一份，
 * 两边渲染一致，localStorage 里的只读会话也互通（同进程、同 WebView 数据目录）。
 * 地址里的 tv=1 让页面自己换只读会话，屏保不可交互也不需要人工登录。
 *
 * 这台电视的系统 WebView 是 Chromium 66（Android 9 出厂版本，无法升级），
 * 所以大屏页面已按旧内核改写：不用 `??`、不用 `inset` 简写、不用 flex gap。
 */
object DashWeb {

    /** 与页面 <meta name="theme-color"> 一致的深色底，避免加载瞬间白闪。 */
    val BG: Int = Color.parseColor("#060A13")

    /**
     * 探测超时。局域网内 TCP 握手要么毫秒级成功、要么立刻被拒；
     * 1.2 秒足够覆盖 Wi-Fi 抖动。
     */
    private const val PROBE_MS = 1200

    private fun decorate(url: String, kiosk: Boolean): String =
        if (kiosk) "$url&kiosk=1" else url

    /**
     * 按优先级探测候选地址，返回第一个连得上的完整 URL。
     *
     * 为什么不直接 loadUrl 再靠 onReceivedError 兜底：电脑关机时 TCP 要等十几秒
     * 才超时，屏保会长时间停在黑屏上。先探测一层，失败就是立即可见的回退。
     *
     * 必须在主线程之外调用（内部会阻塞）。
     */
    fun pick(kiosk: Boolean): String? {
        for (u in ScreenSaver.WEB_URLS) {
            if (reachable(u)) {
                Log.d("StarRiverDash", "probe hit: ${u.take(48)}")
                return decorate(u, kiosk)
            }
        }
        Log.d("StarRiverDash", "probe miss: all ${ScreenSaver.WEB_URLS.size} candidates down")
        return null
    }

    private fun reachable(url: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host ?: return@runCatching false
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
        Socket().use {
            it.connect(InetSocketAddress(host, port), PROBE_MS)
            true
        }
    }.getOrDefault(false)
}

/**
 * 全屏大屏 WebView：建好之后调 [load]，它会先探测可用地址再真正加载。
 * 探测与加载分离，重试时再调一次 [load] 就能重新走一遍「本地 → 云端」的顺序。
 */
@SuppressLint("SetJavaScriptEnabled")
class DashWebView(
    ctx: Context,
    private val kiosk: Boolean,
    private val onOk: (() -> Unit)? = null,
    private val onFail: (() -> Unit)? = null,
) : WebView(ctx) {

    private val main = Handler(Looper.getMainLooper())

    /** 上一次加载的地址，供日志与排障使用。 */
    var lastUrl: String? = null
        private set

    init {
        setBackgroundColor(DashWeb.BG)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // 电视遥控器要靠焦点在输入框之间移动，WebView 自己得先能拿到焦点
        isFocusable = true
        isFocusableInTouchMode = true

        settings.apply {
            javaScriptEnabled = true
            // 只读会话的 token 存在 localStorage，必须开
            domStorageEnabled = true
            // 页面自己按 1920×1080 设计、用 transform 缩放到窗口宽度。
            // 这里必须把 loadWithOverviewMode 关掉：开着的话 WebView 会先按内容宽度
            // 缩一次，页面里的 fitStage 再缩一次，画面只剩屏幕中间六成（v1.3.0 实测）。
            loadWithOverviewMode = false
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webViewClient = object : WebViewClient() {
            // onReceivedHttpError 早于 onPageFinished，别让后者把刚显示的回退画面收掉
            private var httpFailed = false

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                httpFailed = false
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // 只有主文档失败才算「打不开」，子资源（图标/字体）失败不打扰
                if (request.isForMainFrame) fail(view)
            }

            @Deprecated("API < 23 的回调")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                fail(view)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                // 预览链接靠 sharecode 放行，码失效 / cookie 过期时主文档会回 403。
                // 这不算网络错误，onReceivedError 不会来，只能在这里兜到，
                // 否则屏保会停在一张「Access denied」的白页上。
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    httpFailed = true
                    fail(view)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (httpFailed) {
                    // 403 也会走完 onPageFinished，别把错误页盖在回退时钟上面
                    view.visibility = View.INVISIBLE
                    return
                }
                view.visibility = View.VISIBLE
                onOk?.invoke()
                // 旧内核上最容易踩的是「CSS 视口 ÷ 页面自缩放」两层缩放叠在一起，
                // 画面缩成屏幕中间一小块。留一行日志，出问题 adb logcat 就能看到。
                view.evaluateJavascript(
                    "JSON.stringify({iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio," +
                        "vw:" + view.width + ",vh:" + view.height + "," +
                        "tf:(document.getElementById('stage')?document.getElementById('stage').style.transform:'-')," +
                        "sw:document.getElementById('stage')?document.getElementById('stage').getBoundingClientRect().width:0})",
                ) { Log.d("StarRiverDash", "viewport=$it") }
            }
        }

        visibility = View.INVISIBLE
    }

    /** 探测候选地址并加载；全部不可达时立刻回调 [onFail]。 */
    fun load() {
        Thread {
            val url = DashWeb.pick(kiosk)
            main.post {
                if (url == null) {
                    visibility = View.INVISIBLE
                    onFail?.invoke()
                } else {
                    lastUrl = url
                    loadUrl(url)
                }
            }
        }.apply { name = "dash-probe" }.start()
    }

    private fun fail(view: WebView) {
        view.visibility = View.INVISIBLE
        onFail?.invoke()
    }
}

/**
 * 「大屏」页：可交互的大屏 WebView，平时从顶栏胶囊进入看实时数据。
 *
 * 屏保（DreamService）里放的是同一张页面，但不可交互；登录这一环由页面 tv=1 模式
 * 自动完成，这里只是给想手动看一眼、或者网络异常时排查用的人一个正常入口。
 */
class WebScreenActivity : Activity() {

    private var web: DashWebView? = null
    private var hint: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(DashWeb.BG) }
        val wv = DashWebView(
            this,
            kiosk = false,
            onFail = { toast("打不开大屏：检查电视网络，或分享码是否已更换") },
        )
        web = wv
        root.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // 顶部一行提示，10 秒后自己淡出，不挡数据
        val tip = TextView(this).apply {
            text = "返回键退出 · 大屏自动连接"
            setTextColor(Color.parseColor("#CCE6F0FF"))
            textSize = 13f
            setBackgroundColor(Color.parseColor("#66060A13"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            alpha = 0.9f
        }
        hint = tip
        root.addView(
            tip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        tip.postDelayed({ tip.animate().alpha(0f).setDuration(600).start() }, 10_000)

        setContentView(root)
        wv.load()
        wv.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            (it.parent as? FrameLayout)?.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        fun open(ctx: Context) {
            runCatching { ctx.startActivity(Intent(ctx, WebScreenActivity::class.java)) }
        }
    }
}

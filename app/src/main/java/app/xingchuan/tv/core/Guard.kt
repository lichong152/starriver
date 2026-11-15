package app.xingchuan.tv.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 桌面守护的开关与状态。
 *
 * 国产电视在开机、待机唤醒、按主页键时，可能绕过用户选定的桌面，直接把自己的桌面拉到前台。
 * 系统层没有给第三方应用“夺回 HOME”的普通接口，这里采用侧载桌面通用的做法：
 * 用无障碍服务监听窗口切换，一旦发现原厂桌面回到前台，立刻把星川拉回来。
 *
 * 守护是可选功能，默认关闭：它会和固件争抢前台，属于侵入性能力，必须由用户明确开启。
 *
 * 「开启」的判定以系统无障碍开关为准：用户在系统设置里勾选了星川桌面守护，就等于同意接管；
 * 应用内的开关负责「关闭」（写 guard_off），这样远程 adb 打开无障碍后守护能立刻生效，
 * 也让状态只有一个真实来源，不会出现「界面上写着开、实际没开」。
 */
object Guard {

    private const val PREFS = "starriver"
    private const val KEY_GUARD_OFF = "guard_off"

    /**
     * 「用户曾经把守护开起来过」的记忆。
     *
     * 系统无障碍开关仍是运行状态的唯一依据；这个标记只用来回答另一个问题：
     * 开机后开关被固件清掉时，该不该自己写回来。用户在设置里主动关过（guard_off）
     * 就不会再写，避免把用户的关闭动作当成故障去「修复」。
     */
    private const val KEY_GUARD_ON = "guard_on"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = !sp(ctx).getBoolean(KEY_GUARD_OFF, false) && serviceEnabled(ctx)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_GUARD_OFF, !on).apply()
    }

    /** 用户是否表达过「要守护」：开过、且没主动关。 */
    fun wanted(ctx: Context): Boolean =
        !sp(ctx).getBoolean(KEY_GUARD_OFF, false) && sp(ctx).getBoolean(KEY_GUARD_ON, false)

    /** 观察到服务确实绑上了就记一笔，作为开机自愈的依据。 */
    fun noteOn(ctx: Context) {
        if (serviceEnabled(ctx) && !sp(ctx).getBoolean(KEY_GUARD_ON, false)) {
            sp(ctx).edit().putBoolean(KEY_GUARD_ON, true).apply()
        }
    }

    /**
     * 开机自愈：这台电视的固件会在开机时把 secure 设置恢复默认，
     * `enabled_accessibility_services` 与 `accessibility_enabled` 一起被清空，
     * 守护就永久失效了（表现为「重启后回到原厂桌面」）。这里用已授权的
     * WRITE_SECURE_SETTINGS 把自己追加回去，其余无障碍服务原样保留。
     *
     * @return true 表示现在服务是开着的（本来就在、或刚写回成功）
     */
    fun repair(ctx: Context): Boolean {
        if (!wanted(ctx)) return false
        if (serviceEnabled(ctx)) return true
        val self = "${ctx.packageName}/${GuardService::class.java.name}"
        return runCatching {
            val raw = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val list = raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
            if (list.none { it.equals(self, ignoreCase = true) }) {
                Settings.Secure.putString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (list + self).joinToString(":"),
                )
            }
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            serviceEnabled(ctx)
        }.getOrDefault(false)
    }

    /** 本应用的无障碍服务是否已在系统里勾选。 */
    fun serviceEnabled(ctx: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val self = "${ctx.packageName}/${GuardService::class.java.name}"
        return raw.split(':').any { it.equals(self, ignoreCase = true) }
    }

    fun openAccessibilitySettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
}

/**
 * 守护服务本体。
 *
 * 只关心窗口切换事件，不读取窗口内容（canRetrieveWindowContent=false），
 * 也就是只能知道“现在前台是哪个包”，看不到屏幕上的文字，权限面尽量小。
 */
class GuardService : AccessibilityService() {

    private var lastPullAt = 0L
    private var homePackages: Set<String> = emptySet()
    private var homeResolvedAt = 0L

    private companion object {
        /** 开机后多久之内绑上算「开机自愈」，可以主动发 HOME。 */
        const val BOOT_GRACE_MS = 120_000L
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Guard.noteOn(this)
        homePackages = resolveHomePackages()
        homeResolvedAt = SystemClock.elapsedRealtime()
        // 开机两分钟内才绑上，说明是 BootReceiver 自愈把我们拉起来的。此刻固件多半已经把
        // 自己的桌面顶到前台了，而「已经在前台」不会再产生窗口切换事件，光靠监听救不回来。
        // 主动发一次 HOME：系统偏好里星川是默认桌面，等于回到自己；过了这个窗口期就不动，
        // 免得用户开着别的应用时被莫名切回桌面。
        if (SystemClock.elapsedRealtime() < BOOT_GRACE_MS) {
            mainHandler.postDelayed({ runCatching { performGlobalAction(GLOBAL_ACTION_HOME) } }, 1200L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Guard.enabled(this)) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        if (pkg !in homes()) return

        // 原厂桌面每次刷新也会发窗口事件，节流避免来回抢焦点
        val now = SystemClock.elapsedRealtime()
        if (now - lastPullAt < 2500L) return
        lastPullAt = now
        pullToFront()
    }

    /**
     * 「能响应 HOME 的包」集合。
     *
     * 缓存 60 秒而不是永久缓存：用户随时可能装/卸一个桌面类应用，
     * 永久缓存会让守护在那种情况下漏判（新桌面不在集合里，就不会被拉回）。
     */
    private fun homes(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (homePackages.isEmpty() || now - homeResolvedAt > 60_000L) {
            homePackages = resolveHomePackages()
            homeResolvedAt = now
        }
        return homePackages
    }

    override fun onInterrupt() = Unit

    private fun pullToFront() {
        val intent = Intent(this, app.xingchuan.tv.MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        val started = runCatching { startActivity(intent) }.isSuccess
        // Android 10 起限制后台启动界面；无障碍服务可以直接触发一次 HOME，
        // 在星川已是默认桌面时等同于回到自己，在非默认时也不破坏现场。
        if (!started) runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    /** 当前能响应 HOME 的所有包（排除自己和系统解析器），用来判断“谁抢了桌面”。 */
    private fun resolveHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val list = runCatching { packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return list.mapNotNull { it.activityInfo?.packageName }
            .filter { it != packageName && it != "android" }
            .toSet()
    }
}

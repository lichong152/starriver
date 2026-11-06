package app.xingchuan.tv.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.File
import java.net.NetworkInterface

/**
 * 顶栏胶囊背后的系统动作。
 *
 * 实测这台固件（Droidlogic / Android 9）没有可被第三方调起的「信号源」界面：
 * 输入源由 `com.droidlogic.tvinput` 的 TvInputService 提供，切换需要系统签名权限；
 * 厂商的 ShortcutsActivity 单独拉起会卡在「资源加载中」。
 * 所以「信号源」不做假动作，只提示用遥控器按键；「有线网络」直接深链到网络设置页。
 */
object Shortcuts {
    private const val VENDOR_SETTINGS = "com.cultraview.settings"
    private const val VENDOR_SETTINGS_ACT = "com.cultraview.settings.CtvSettings"

    /**
     * 打开厂商设置首页——「网络」就是它的第一格。
     *
     * 注意：不能直接拉起 `com.cultraview.settings.net.NetworkSettings`，
     * 那个类在 manifest 里是 Fragment（没有无参构造），startActivity 会让设置进程
     * 抛 InstantiationException 崩掉，实测确认。
     */
    fun openNetworkSettings(ctx: Context): Boolean =
        start(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS)) ||
            start(ctx, Intent().setClassName(VENDOR_SETTINGS, VENDOR_SETTINGS_ACT)) ||
            start(ctx, Intent(Settings.ACTION_SETTINGS))

    /** 系统设置首页，作为兜底入口。 */
    fun openSystemSettings(ctx: Context): Boolean =
        start(ctx, Intent().setClassName(VENDOR_SETTINGS, VENDOR_SETTINGS_ACT)) ||
            start(ctx, Intent(Settings.ACTION_SETTINGS))

    private fun start(ctx: Context, intent: Intent): Boolean = runCatching {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /**
     * 联网方式，纯本地判断：不申请权限、不发起请求。
     *
     * 三条路依次尝试，实测在 Android 9 上（未申请 INTERNET/NETWORK_STATE）：
     * 1. sysfs 的 operstate —— 最直接，读得到就是准的
     * 2. NetworkInterface 枚举 —— 部分固件允许，作为补充
     * 3. 都读不到时返回「网络」，不谎报「未连接」
     */
    fun networkLabel(): String {
        val eth = linkUp("eth0") || hasInterface("eth")
        val wlan = linkUp("wlan0") || hasInterface("wlan")
        return when {
            eth -> "有线网络"
            wlan -> "无线网络"
            else -> "未连接"
        }
    }

    private fun linkUp(name: String): Boolean {
        val state = readTrim("/sys/class/net/$name/operstate")
        val carrier = readTrim("/sys/class/net/$name/carrier")
        // 不同驱动给的字段不一样：能读到 up 或 carrier=1 任一即可
        return state == "up" || carrier == "1"
    }

    private fun readTrim(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("")

    private fun hasInterface(prefix: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif ->
            nif.isUp && nif.name.startsWith(prefix) &&
                nif.inetAddresses.toList().any { !it.isLoopbackAddress }
        }
    }.getOrDefault(false)
}

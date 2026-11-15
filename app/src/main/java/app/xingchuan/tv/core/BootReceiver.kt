package app.xingchuan.tv.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.xingchuan.tv.ScreenSaver

/**
 * 开机自启 + 开机自愈。
 *
 * 这台电视（Android 9 / `com.stv.*` 平台）开机时会把 **secure** 设置恢复默认：
 * `enabled_accessibility_services`、`accessibility_enabled`、`screensaver_enabled`
 * 全被清空，于是重启后守护失效、屏保也不出场，界面回到原厂桌面。系统命名空间里的
 * `screen_off_timeout` 反而保留——所以这不是「设置没写进去」，是固件开机重置。
 *
 * 对策：开机后立刻用已授权的 WRITE_SECURE_SETTINGS 把这几项写回去，并把桌面带到前台；
 * 监听窗口事件的守护服务随后接管（固件过一会儿再抢，也能被拉回来）。
 *
 * Android 10 起后台启动界面受限，`startActivity` 属于「尽力而为」；本机是 SDK 28，直接有效。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val ctx = context.applicationContext

        // 1. 守护：用户开过且没主动关，就把无障碍开关自己写回来
        Guard.repair(ctx)
        // 2. 屏保：样式不是「关闭」，就把组件与开关写回
        ScreenSaver.ensureApplied(ctx)
        // 3. 待机时间：固件若把 3 分钟改掉，恢复
        ScreenSaver.ensureIdleTimeout(ctx)

        // 4. 把自己带到前台。standard 形态本来就是系统桌面，直接拉；
        //    guard 形态只有用户要守护时才拉，免得变成开机弹窗。
        if (!isDefaultHome(ctx) && !Guard.wanted(ctx)) return
        val go = Intent(ctx, app.xingchuan.tv.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(go) }
    }

    /** 系统的默认桌面是不是星川（用户按过「始终」才为 true）。 */
    private fun isDefaultHome(ctx: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        return resolved?.activityInfo?.packageName == ctx.packageName
    }
}

package app.xingchuan.tv.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启。
 *
 * standard 形态本身就是桌面，系统开机会直接拉起，这个接收器是冗余的；
 * guard 形态没有 HOME 声明，只能靠它尽力在开机后把自己带到前台。
 *
 * Android 10 起限制后台启动界面，这一步属于「尽力而为」：被系统拦下时不会报错，
 * 用户按一次主页键、或守护服务检测到原厂桌面回前台时仍会回到星川。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Guard.enabled(context)) return
        val go = Intent(context, app.xingchuan.tv.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(go) }
    }
}

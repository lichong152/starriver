package app.xingchuan.tv.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import java.text.Collator
import java.util.Locale

/**
 * 桌面上的一个可启动项。key 是 pkg + activity，跨版本稳定，用于 Dock / 隐藏 / 使用频次存储。
 * 图标在枚举时一并解码，界面层直接拿现成的 ImageBitmap，滚动不会触发磁盘或 PM 调用。
 */
data class AppItem(
    val pkg: String,
    val activity: String,
    val label: String,
    val leanback: Boolean,
    val icon: ImageBitmap?,
) {
    val key: String get() = "$pkg/$activity"
}

/**
 * 应用枚举与启动。
 *
 * 只依赖 PackageManager，不引入 leanback 库：国产电视上 LEANBACK_LAUNCHER 与 LAUNCHER
 * 混用，两者都要查，LEANBACK 优先（同一包优先取电视入口），重复项按 pkg/activity 去重。
 */
object Apps {

    /** 枚举全部可启动应用，按中文拼音排序，并解码图标。阻塞调用，需在 IO 线程执行。 */
    fun load(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppItem>(96)

        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in query(pm, intent)) {
                val ai = ri.activityInfo ?: continue
                if (!ai.enabled) continue
                if (ai.packageName == ctx.packageName) continue
                val key = ai.packageName + "/" + ai.name
                if (!seen.add(key)) continue

                val label = runCatching { ri.loadLabel(pm).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?: ai.packageName

                out += AppItem(
                    pkg = ai.packageName,
                    activity = ai.name,
                    label = label,
                    leanback = cat == Intent.CATEGORY_LEANBACK_LAUNCHER,
                    icon = Icons.of(ctx, ri, ai.packageName),
                )
            }
        }

        val collator = Collator.getInstance(Locale.CHINA)
        return out.sortedWith { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.pkg.compareTo(b.pkg)
        }
    }

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** 启动应用。失败时退回系统给的首选入口，仍失败返回 false。 */
    fun launch(ctx: Context, app: AppItem): Boolean {
        val cat = if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val direct = Intent(Intent.ACTION_MAIN).apply {
            addCategory(cat)
            component = ComponentName(app.pkg, app.activity)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (runCatching { ctx.startActivity(direct) }.isSuccess) return true

        val fallback = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { ctx.startActivity(fallback) }.isSuccess
    }
}

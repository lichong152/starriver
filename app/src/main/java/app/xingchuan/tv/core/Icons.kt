package app.xingchuan.tv.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 应用图标的解码与缓存。
 *
 * 电视上图标来源比手机上杂：有的应用只在 Leanback 入口上挂图标，有的把方形图标重复声明成
 * banner，也有完全不提供图标的老固件自带应用。因此这里按「入口图标 → 包图标」的顺序取，
 * 取到后统一画进正方形画布：接近方形的铺满，宽/竖的按比例居中留边，避免被拉扁。
 *
 * 解码在 IO 线程做，结果按组件名缓存；内存超出上限时按最近最少使用淘汰，避免低内存电视上
 * 一百多个图标把堆撑爆。
 */
object Icons {

    /** 缓存按字节计上限。1080p 电视上约合 60~90 个图标。 */
    private const val MAX_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun clear() = cache.evictAll()

    /**
     * 取一个应用的图标。取不到返回 null，由界面层回退成首字卡片。
     * @param ri 查询启动项时拿到的 ResolveInfo，用来取「这个入口自己的」图标。
     */
    fun of(ctx: Context, ri: ResolveInfo?, pkg: String): ImageBitmap? {
        val key = (ri?.activityInfo?.name ?: "") + "@" + pkg
        cache.get(key)?.let { return it }

        val pick = pickDrawable(ctx, ri, pkg) ?: return null
        val bitmap = runCatching { render(pick, targetPx(ctx)) }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * 图标解码边长。电视 1080p 下 density≈2，Dock 格子 52dp≈104px，取 1.5 倍留出聚焦放大和
     * 4K 面板的余量；上限压在 192px，一百个应用也就十几兆。
     */
    private fun targetPx(ctx: Context): Int {
        val density = ctx.resources.displayMetrics.density
        return (64 * density).toInt().coerceIn(144, 192)
    }

    /** 选中的图源。[cover] 为真时按中心裁切铺满（banner 用），否则按比例留边。 */
    private class Pick(val drawable: Drawable, val cover: Boolean)

    private fun pickDrawable(ctx: Context, ri: ResolveInfo?, pkg: String): Pick? {
        val pm = ctx.packageManager

        // 电视应用常见的两种「没有方形图标」：只声明 banner，或什么都不声明。
        // 前者用 banner 裁切，避免一百个应用长得一模一样的系统默认机器人。
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        if (appInfo != null && appInfo.icon == 0 && appInfo.banner != 0) {
            val banner = runCatching { appInfo.loadBanner(pm) }.getOrNull()
            if (banner != null) return Pick(banner, cover = true)
        }

        val attempts = ArrayList<() -> Drawable?>(4)
        if (ri != null) {
            attempts += { ri.loadIcon(pm) }
            val ai = ri.activityInfo
            if (ai != null) {
                attempts += { pm.getActivityIcon(ComponentName(ai.packageName, ai.name)) }
            }
        }
        attempts += { pm.getApplicationIcon(pkg) }

        for (attempt in attempts) {
            val d = runCatching(attempt).getOrNull() ?: continue
            if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) return Pick(d, cover = false)
        }
        return null
    }

    private fun render(pick: Pick, px: Int): ImageBitmap {
        val d = pick.drawable
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight

        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, px, px)
        } else {
            val ratio = iw.toFloat() / ih
            when {
                // 方形到 4:3：直接铺满。自适应图标的本体是 108×108，也落在这里。
                ratio in 0.72f..1.38f -> d.setBounds(0, 0, px, px)
                // 宽图（TV banner）：等比缩放后上下留黑边，绝不横向拉伸
                ratio > 1.38f -> {
                    if (pick.cover) {
                        // 居中裁切：正方形画布上放一条 16:9 banner 只会剩一条细线，宁可切掉两侧。
                        // 高度撑满画布，宽度溢出后左右各挂出去一半。
                        val w = (px * ratio).toInt().coerceAtLeast(px)
                        val left = (px - w) / 2
                        d.setBounds(left, 0, left + w, px)
                    } else {
                        val h = (px / ratio).toInt().coerceAtLeast(1)
                        val top = (px - h) / 2
                        d.setBounds(0, top, px, top + h)
                    }
                }
                else -> {
                    val w = (px * ratio).toInt().coerceAtLeast(1)
                    val left = (px - w) / 2
                    d.setBounds(left, 0, left + w, px)
                }
            }
        }
        d.draw(canvas)
        return bmp.asImageBitmap()
    }
}

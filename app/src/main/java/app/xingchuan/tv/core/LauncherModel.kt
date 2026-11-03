package app.xingchuan.tv.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 壁纸来源。四种形态：内置渐变、内置风光轮播、本地图片、本地视频。
 * 序列化进 SharedPreferences，格式 `<type>:<value>`。
 */
sealed interface WallpaperSpec {
    data class Preset(val id: String) : WallpaperSpec
    data class Picture(val uri: String) : WallpaperSpec
    data class Video(val uri: String) : WallpaperSpec

    /** 内置风光轮播：assets/wallpapers 下的超清自然风光图，定时缓慢轮换。 */
    data object Nature : WallpaperSpec

    fun encode(): String = when (this) {
        is Preset -> "preset:$id"
        is Picture -> "image:$uri"
        is Video -> "video:$uri"
        Nature -> "nature:auto"
    }

    companion object {
        val Default: WallpaperSpec = Nature

        fun decode(raw: String?): WallpaperSpec {
            if (raw.isNullOrBlank()) return Default
            val cut = raw.indexOf(':')
            if (cut <= 0) return Default
            val type = raw.substring(0, cut)
            val value = raw.substring(cut + 1).takeIf { it.isNotBlank() } ?: return Default
            return when (type) {
                "nature" -> Nature
                "preset" -> Preset(value)
                "image" -> Picture(value)
                "video" -> Video(value)
                else -> Default
            }
        }
    }
}

/** 内置渐变壁纸。颜色自下而上过渡，电视上比照片更耐看、也更省内存。 */
data class WallpaperPreset(
    val id: String,
    val name: String,
    val colors: List<Color>,
    val glow: Color,
)

object Presets {
    val all: List<WallpaperPreset> = listOf(
        WallpaperPreset(
            id = "nightfall",
            name = "极夜",
            colors = listOf(Color(0xFF05070D), Color(0xFF0C1830), Color(0xFF1B3663)),
            glow = Color(0xFF4C7DF0),
        ),
        WallpaperPreset(
            id = "river",
            name = "星川",
            colors = listOf(Color(0xFF03101A), Color(0xFF0A3340), Color(0xFF12707E)),
            glow = Color(0xFF7FE7E0),
        ),
        WallpaperPreset(
            id = "ember",
            name = "赤霞",
            colors = listOf(Color(0xFF160509), Color(0xFF3A1119), Color(0xFF70202C)),
            glow = Color(0xFFFF8A6B),
        ),
        WallpaperPreset(
            id = "moss",
            name = "林雾",
            colors = listOf(Color(0xFF050F0B), Color(0xFF102C1D), Color(0xFF2A5340)),
            glow = Color(0xFF8FE6A8),
        ),
        WallpaperPreset(
            id = "slate",
            name = "玄石灰",
            colors = listOf(Color(0xFF08090C), Color(0xFF16181F), Color(0xFF2A2E38)),
            glow = Color(0xFF9AA6C4),
        ),
    )

    fun byId(id: String): WallpaperPreset = all.firstOrNull { it.id == id } ?: all.first()
}

/** 简约设置存储 + 桌面状态。用 Compose 快照状态承载，改动即时反映到界面。 */
class LauncherModel(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences("starriver", Context.MODE_PRIVATE)

    /** 可显示的应用（已枚举、已合并、已排序） */
    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set

    /** 是否仍在首次枚举 */
    var loading by mutableStateOf(true)
        private set

    /** Dock 内的应用 key，按顺序 */
    var dock by mutableStateOf<List<String>>(emptyList())
        private set

    /** 被用户隐藏的应用 key */
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 启动次数，用于「常用」推荐位 */
    var usage by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    var wallpaper by mutableStateOf(WallpaperSpec.decode(sp.getString(KEY_WALLPAPER, null)))
        private set

    /** 界面状态 */
    var drawerOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)

    val visibleApps: List<AppItem> get() = apps.filter { it.key !in hidden }

    /** Dock 位：按存储顺序还原成真实应用，过滤掉已卸载的 */
    val dockApps: List<AppItem>
        get() {
            val byKey = apps.associateBy { it.key }
            return dock.mapNotNull { byKey[it] }
        }

    /** 推荐位：先按使用次数，再用字母序补齐，避开已在 Dock 里的 */
    fun shelfApps(count: Int): List<AppItem> {
        val inDock = dock.toSet()
        val pool = visibleApps.filter { it.key !in inDock }
        val ranked = pool.sortedWith(
            compareByDescending<AppItem> { usage[it.key] ?: 0 }.thenBy { it.label }
        )
        return ranked.take(count)
    }

    fun applyLoaded(list: List<AppItem>) {
        apps = list
        loading = false
        // 首次运行：Dock 自动填入前 6 个应用，避免空桌面
        if (!sp.getBoolean(KEY_DOCK_SEEDED, false) && dock.isEmpty()) {
            val seed = list.filter { it.key !in hidden }.take(6).map { it.key }
            dock = seed
            persistDock(seed)
            sp.edit().putBoolean(KEY_DOCK_SEEDED, true).apply()
        } else {
            // 清理已卸载项
            val alive = list.map { it.key }.toSet()
            val pruned = dock.filter { it in alive }
            if (pruned.size != dock.size) {
                dock = pruned
                persistDock(pruned)
            }
        }
    }

    fun bump(app: AppItem) {
        val next = usage.toMutableMap()
        next[app.key] = (next[app.key] ?: 0) + 1
        usage = next
        sp.edit()
            .putString(KEY_USAGE, next.entries.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
    }

    fun toggleDock(app: AppItem) {
        val next = if (app.key in dock) {
            dock - app.key
        } else {
            if (dock.size >= MAX_DOCK) return // 已满，先移出一个再加
            dock + app.key
        }
        dock = next
        persistDock(next)
    }

    fun hide(app: AppItem) {
        hidden = hidden + app.key
        val nextDock = dock - app.key
        if (nextDock.size != dock.size) {
            dock = nextDock
            persistDock(nextDock)
        }
        sp.edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }

    fun unhideAll() {
        hidden = emptySet()
        sp.edit().remove(KEY_HIDDEN).apply()
    }

    fun changeWallpaper(spec: WallpaperSpec) {
        wallpaper = spec
        sp.edit().putString(KEY_WALLPAPER, spec.encode()).apply()
    }

    fun restoreDefaults() {
        hidden = emptySet()
        val seed = apps.take(6).map { it.key }
        dock = seed
        usage = emptyMap()
        wallpaper = WallpaperSpec.Default
        sp.edit()
            .remove(KEY_HIDDEN)
            .remove(KEY_USAGE)
            .putString(KEY_DOCK, seed.joinToString("\u001f"))
            .putString(KEY_WALLPAPER, WallpaperSpec.Default.encode())
            .apply()
    }

    private fun persistDock(list: List<String>) {
        sp.edit().putString(KEY_DOCK, list.joinToString("\u001f")).apply()
    }

    init {
        dock = sp.getString(KEY_DOCK, null)
            ?.split("\u001f")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        hidden = sp.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()
        usage = sp.getString(KEY_USAGE, null)
            ?.split(";")
            ?.mapNotNull { seg ->
                val i = seg.lastIndexOf('=')
                if (i <= 0) null else seg.substring(0, i) to (seg.substring(i + 1).toIntOrNull() ?: 0)
            }
            ?.toMap()
            ?: emptyMap()
    }

    private companion object {
        /** Dock 位上限：7 个应用 + 「全部应用」= 8 个格子，960dp 宽下刚好不溢出 */
        const val MAX_DOCK = 7
        const val KEY_DOCK = "dock"
        const val KEY_DOCK_SEEDED = "dock_seeded"
        const val KEY_HIDDEN = "hidden"
        const val KEY_USAGE = "usage"
        const val KEY_WALLPAPER = "wallpaper"
    }
}

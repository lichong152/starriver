package app.xingchuan.tv.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.xingchuan.tv.core.AppItem
import app.xingchuan.tv.core.Apps
import app.xingchuan.tv.core.Guard
import app.xingchuan.tv.core.LauncherModel
import app.xingchuan.tv.core.WallpaperSpec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 推荐位数量。960dp 宽的电视上，左右留白 56dp 后可用 848dp：
 * 5 × 156 + 4 × 16 = 844dp，刚好放下；改成 6 张会溢出屏幕。
 */
private const val SHELF_COUNT = 5
private val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/** 桌面上的三个焦点区。视频壁纸、抽屉、设置都是覆盖层，不参与这里的上下切换。 */
private enum class Zone { Shelf, Dock }

@Composable
fun HomeScreen(model: LauncherModel) {
    val ctx = LocalContext.current
    val root = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var zone by remember { mutableStateOf(Zone.Dock) }
    var shelfIndex by remember { mutableStateOf(0) }
    var dockIndex by remember { mutableStateOf(0) }
    var gridIndex by remember { mutableStateOf(0) }
    var settingIndex by remember { mutableStateOf(0) }

    // 「菜单键 设置」提示：进桌面 6 秒后淡出，任何按键都让它重新亮起，避免常驻打扰
    var hintTick by remember { mutableIntStateOf(0) }

    // 守护要「已开启」且「系统里确实勾选了无障碍服务」才算真正生效，两者缺一都不能显示为开
    var guardOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { guardOn = Guard.enabled(ctx) }

    val dockApps = model.dockApps
    val shelfApps = model.shelfApps(SHELF_COUNT)
    val visible = model.visibleApps
    val dockSlots = dockApps.size + 1 // 末位是「全部应用」入口

    // 时钟：每 10 秒刷新一次，够用且不浪费重组
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            delay(10_000)
        }
    }

    // 从其它应用返回时重新夺回焦点，否则遥控器事件会落到窗口外的空处
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.drawerOpen = false
                model.settingsOpen = false
                // 用户可能刚在系统设置里勾掉/勾上无障碍服务，回来时同步一次
                guardOn = Guard.enabled(ctx)
                runCatching { root.requestFocus() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }
    LaunchedEffect(visible.size) {
        if (gridIndex > visible.lastIndex) gridIndex = visible.lastIndex.coerceAtLeast(0)
    }
    // 启动应用后使用频次会变，推荐位顺序可能重排，索引要跟着收敛
    LaunchedEffect(shelfApps.size) {
        if (shelfIndex > shelfApps.lastIndex) shelfIndex = shelfApps.lastIndex.coerceAtLeast(0)
    }

    fun open(app: AppItem) {
        model.bump(app)
        model.drawerOpen = false
        model.settingsOpen = false
        if (!Apps.launch(ctx, app)) {
            Toast.makeText(ctx, "无法启动 ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val s = (maxWidth.value / 960f).coerceIn(0.8f, 2.4f)
        val cols = if (s > 1.35f) 6 else 5

        val picker = rememberWallpaperPicker(model)

        fun activateSetting(i: Int) {
            when (val item = SettingsItems.all.getOrNull(i) ?: return) {
                is SettingItem.Preset -> model.changeWallpaper(WallpaperSpec.Preset(item.preset.id))
                is SettingItem.GroupWall -> model.changeWallpaper(WallpaperSpec.Group(item.group.id))
                SettingItem.PickImage -> picker.image()
                SettingItem.PickVideo -> picker.video()
                SettingItem.GuardToggle -> {
                    val turnOn = !Guard.enabled(ctx)
                    Guard.setEnabled(ctx, turnOn)
                    guardOn = turnOn && Guard.serviceEnabled(ctx)
                    if (turnOn && !Guard.serviceEnabled(ctx)) {
                        Toast.makeText(
                            ctx, "请在「无障碍」里勾选「星川桌面守护」", Toast.LENGTH_LONG,
                        ).show()
                        Guard.openAccessibilitySettings(ctx)
                    }
                }
                SettingItem.Unhide -> model.unhideAll()
                SettingItem.Reset -> model.restoreDefaults()
            }
        }

        fun handleKey(e: KeyEvent): Boolean {
            if (e.type != KeyEventType.KeyDown) return false
            // 一有操作就把设置提示重新点亮，6 秒后再次淡出
            hintTick++

            // 设置面板：两行网格导航（左右同行走动，上下跨行）
            if (model.settingsOpen) {
                val n = SettingsItems.total
                val col = SettingsItems.PER_ROW
                return when (e.key) {
                    Key.DirectionLeft -> {
                        if (settingIndex % col != 0) settingIndex--; true
                    }
                    Key.DirectionRight -> {
                        if (settingIndex % col != col - 1 && settingIndex + 1 < n) settingIndex++; true
                    }
                    Key.DirectionUp -> {
                        if (settingIndex >= col) settingIndex -= col; true
                    }
                    Key.DirectionDown -> {
                        val next = settingIndex + col
                        if (next < n) settingIndex = next; true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        activateSetting(settingIndex); true
                    }
                    Key.Back, Key.Menu -> {
                        model.settingsOpen = false; true
                    }
                    else -> false
                }
            }

            // 抽屉：网格导航
            if (model.drawerOpen) {
                val n = visible.size
                return when (e.key) {
                    Key.Back -> {
                        model.drawerOpen = false; true
                    }
                    Key.Menu -> {
                        visible.getOrNull(gridIndex)?.let { model.toggleDock(it) }; true
                    }
                    Key.DirectionLeft -> {
                        if (gridIndex % cols != 0) gridIndex--; true
                    }
                    Key.DirectionRight -> {
                        if (gridIndex % cols != cols - 1 && gridIndex + 1 < n) gridIndex++; true
                    }
                    Key.DirectionUp -> {
                        if (gridIndex - cols >= 0) gridIndex -= cols
                        else {
                            // 首行再往上就收起抽屉，回到 Dock，避免只能靠返回键
                            model.drawerOpen = false
                            zone = Zone.Dock
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (gridIndex + cols < n) gridIndex += cols; true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        visible.getOrNull(gridIndex)?.let { open(it) }; true
                    }
                    else -> false
                }
            }

            // 主屏：推荐位 <-> Dock，下键进入抽屉
            return when (e.key) {
                Key.DirectionLeft -> {
                    if (zone == Zone.Dock) dockIndex = (dockIndex - 1).coerceAtLeast(0)
                    else shelfIndex = (shelfIndex - 1).coerceAtLeast(0)
                    true
                }
                Key.DirectionRight -> {
                    if (zone == Zone.Dock) dockIndex = (dockIndex + 1).coerceAtMost(dockSlots - 1)
                    else shelfIndex = (shelfIndex + 1).coerceAtMost(shelfApps.lastIndex.coerceAtLeast(0))
                    true
                }
                Key.DirectionUp -> {
                    // 应用很少时推荐位可能为空，此时上键不移动焦点，避免“焦点消失”
                    if (zone == Zone.Dock && shelfApps.isNotEmpty()) zone = Zone.Shelf
                    true
                }
                Key.DirectionDown -> {
                    if (zone == Zone.Shelf) {
                        zone = Zone.Dock
                    } else {
                        model.drawerOpen = true
                        gridIndex = 0
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (zone == Zone.Dock) {
                        if (dockIndex < dockApps.size) open(dockApps[dockIndex])
                        else {
                            model.drawerOpen = true
                            gridIndex = 0
                        }
                    } else {
                        shelfApps.getOrNull(shelfIndex)?.let { open(it) }
                    }
                    true
                }
                Key.Menu -> {
                    model.settingsOpen = true
                    settingIndex = 0
                    true
                }
                // 桌面根部的返回键什么都不做，避免把桌面弹掉露出厂商原生桌面
                Key.Back -> true
                else -> false
            }
        }

        WallpaperLayer(model.wallpaper)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = (56 * s).dp, vertical = (36 * s).dp),
        ) {
            TopBar(now = now, s = s, hintTick = hintTick)
            Spacer(Modifier.weight(1f))
            if (shelfApps.isNotEmpty()) {
                ShelfRow(
                    apps = shelfApps,
                    focusedIndex = if (zone == Zone.Shelf) shelfIndex else -1,
                    s = s,
                )
                Spacer(Modifier.height((42 * s).dp))
            }
            DockRow(
                apps = dockApps,
                slots = dockSlots,
                focusedIndex = if (zone == Zone.Dock) dockIndex else -1,
                s = s,
            )
        }

        if (model.drawerOpen) {
            AppDrawer(
                apps = visible,
                focusedIndex = gridIndex,
                cols = cols,
                s = s,
            )
        }

        if (model.settingsOpen) {
            SettingsOverlay(
                model = model,
                focusedIndex = settingIndex,
                guardOn = guardOn,
                s = s,
            )
        }

        if (model.loading && visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt("正在读取应用…", Type.Title, color = Ink.TextDim)
            }
        }

        // 唯一的焦点锚点：所有遥控器事件都在这里集中分发，不依赖 Compose 的几何焦点搜索
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(root)
                .focusable()
                .onKeyEvent { handleKey(it) }
        )
    }
}

@Composable
private fun TopBar(now: Calendar, s: Float, hintTick: Int) {
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time) }
    val date = remember(now) {
        val w = WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1]
        "${now.get(Calendar.MONTH) + 1}月${now.get(Calendar.DAY_OF_MONTH)}日  $w"
    }

    // 提示只在刚进桌面 / 刚按键时可见，静置 6 秒后淡到几乎透明
    var hintOn by remember { mutableStateOf(true) }
    LaunchedEffect(hintTick) {
        hintOn = true
        delay(6_000)
        hintOn = false
    }
    val hintAlpha by animateFloatAsState(
        targetValue = if (hintOn) 1f else 0.18f,
        animationSpec = tween(600),
        label = "hint",
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column {
            Txt(
                text = time,
                style = Type.Clock.copy(fontSize = (64 * s).sp, letterSpacing = 2.sp),
            )
            Spacer(Modifier.height((6 * s).dp))
            Txt(text = date, style = Type.Date.copy(fontSize = (19 * s).sp))
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Wordmark(s)
            Spacer(Modifier.height((4 * s).dp))
            Txt(text = "STAR RIVER TV", style = Type.Badge.copy(fontSize = (11 * s).sp))
            Spacer(Modifier.height((14 * s).dp))
            Box(Modifier.alpha(hintAlpha)) { HintChip("菜单键 设置", s) }
        }
    }
}

@Composable
private fun Wordmark(s: Float) {
    Txt(
        text = "星川",
        style = Type.Title.copy(fontSize = (22 * s).sp),
        color = Color(0xFFE9F0FF),
    )
}

@Composable
private fun HintChip(text: String, s: Float) {
    Box(
        Modifier
            .clip(RoundedCornerShape((30 * s).dp))
            .background(Color(0x1FFFFFFF))
            .border(1.dp, Ink.GlassStroke, RoundedCornerShape((30 * s).dp))
            .padding(horizontal = (14 * s).dp, vertical = (7 * s).dp)
    ) {
        Txt(text = text, style = Type.Hint.copy(fontSize = (12 * s).sp), color = Ink.Text)
    }
}

/** 推荐位：横向大卡片，显示使用频次最高的应用。 */
@Composable
private fun ShelfRow(apps: List<AppItem>, focusedIndex: Int, s: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
        apps.forEachIndexed { i, app ->
            val focused = i == focusedIndex
            GlassPanel(
                modifier = Modifier
                    .width((156 * s).dp)
                    .height((110 * s).dp)
                    .focusScale(focused, to = 1.05f),
                shape = RoundedCornerShape((24 * s).dp),
                focused = focused,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding((15 * s).dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    AppIcon(
                        icon = app.icon,
                        fallbackText = app.label,
                        size = (44 * s).dp,
                        corner = (12 * s).dp,
                    )
                    Txt(
                        text = app.label,
                        style = Type.AppLabel.copy(
                            fontSize = (16 * s).sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Ink.Text,
                    )
                }
            }
        }
    }
}

/** 底部玻璃 Dock。末位固定是「全部应用」。 */
@Composable
private fun DockRow(apps: List<AppItem>, slots: Int, focusedIndex: Int, s: Float) {
    GlassPanel(
        modifier = Modifier.height((104 * s).dp),
        shape = RoundedCornerShape((30 * s).dp),
        strong = true,
        dark = true,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = (14 * s).dp, vertical = (10 * s).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((6 * s).dp),
        ) {
            apps.forEachIndexed { i, app ->
                DockTile(
                    focused = i == focusedIndex,
                    label = app.label,
                    s = s,
                    modifier = Modifier.weight(1f),
                    icon = { AppIcon(app.icon, app.label, (52 * s).dp, corner = (12 * s).dp) },
                )
            }
            DockTile(
                focused = focusedIndex == apps.size,
                label = "全部",
                s = s,
                modifier = Modifier.weight(1f),
                icon = { AllAppsGlyph((52 * s).dp, focusedIndex == apps.size, s) },
            )
        }
    }
}

@Composable
private fun DockTile(
    focused: Boolean,
    label: String,
    s: Float,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape((18 * s).dp))
            .background(if (focused) Color(0x24FFFFFF) else Color.Transparent)
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = if (focused) Ink.GlassStrokeOn else Color.Transparent,
                shape = RoundedCornerShape((18 * s).dp),
            )
            .focusScale(focused, to = 1.08f)
            .padding(vertical = (8 * s).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height((6 * s).dp))
        Txt(
            text = label,
            style = Type.AppLabel.copy(fontSize = (12 * s).sp),
            color = if (focused) Ink.Text else Color(0xFFD6E0F2),
        )
    }
}

/** 「全部应用」图标：3×3 圆点。 */
@Composable
private fun AllAppsGlyph(size: Dp, focused: Boolean, s: Float) {
    val color = if (focused) Ink.Accent else Color(0xCCE6EEFF)
    val animated by animateFloatAsState(if (focused) 1f else 0.82f, label = "dots")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension * 0.075f
        val gap = this.size.minDimension * 0.26f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        for (row in -1..1) {
            for (col in -1..1) {
                drawCircle(
                    color = color.copy(alpha = animated),
                    radius = r,
                    center = Offset(center.x + col * gap, center.y + row * gap),
                )
            }
        }
    }
}

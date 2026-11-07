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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.xingchuan.tv.ScreenSaver
import app.xingchuan.tv.ScreenSaverStyle
import app.xingchuan.tv.WebScreenActivity
import app.xingchuan.tv.core.AppItem
import app.xingchuan.tv.core.Apps
import app.xingchuan.tv.core.Guard
import app.xingchuan.tv.core.LauncherModel
import app.xingchuan.tv.core.Shortcuts
import app.xingchuan.tv.core.WallpaperGroups
import app.xingchuan.tv.core.WallpaperSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 推荐位数量。960dp 宽的电视上，左右留白 56dp 后可用 848dp：
 * 5 × 156 + 4 × 16 = 844dp，刚好放下；改成 6 张会溢出屏幕。
 */
private const val SHELF_COUNT = 5

/** 桌面上的焦点区。视频壁纸、抽屉、设置都是覆盖层，不参与这里的上下切换。 */
private enum class Zone { Top, Shelf, Dock }

/** 抽屉里对单个应用可执行的动作，顺序即菜单顺序。 */
private enum class AppAction(val label: String) {
    AddDock("加入 Dock"),
    RemoveDock("从 Dock 移除"),
    MoveLeft("向左移一位"),
    MoveRight("向右移一位"),
    Hide("在桌面隐藏"),
    Cancel("取消"),
}

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

    // 顶栏胶囊：焦点索引（不在顶栏时不使用）
    var topIndex by remember { mutableStateOf(0) }
    var aboutOpen by remember { mutableStateOf(false) }
    // 抽屉里的单应用操作菜单（加入/移出 Dock、隐藏）
    var actionOpen by remember { mutableStateOf(false) }
    var actionIndex by remember { mutableStateOf(0) }
    // 联网方式（有线网络 / 无线网络 / 未连接），只读网卡状态，不发请求。
    // 初值在主线程取一次（两次小文件读），之后的轮询全部走 IO，且只在前台时进行。
    var netLabel by remember { mutableStateOf(Shortcuts.networkLabel()) }
    var resumed by remember { mutableStateOf(true) }
    LaunchedEffect(resumed) {
        while (resumed) {
            netLabel = withContext(Dispatchers.IO) { Shortcuts.networkLabel() }
            delay(20_000)
        }
    }

    // 守护要「已开启」且「系统里确实勾选了无障碍服务」才算真正生效，两者缺一都不能显示为开
    var guardOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { guardOn = Guard.enabled(ctx) }

    val dockApps = model.dockApps
    val shelfApps = model.shelfApps(SHELF_COUNT)
    val visible = model.visibleApps
    val dockSlots = dockApps.size + 1 // 末位是「全部应用」入口

    // 时钟交给 TopBar 自己维护，这里不再持有 now，避免每 10 秒重组整棵树

    // 从其它应用返回时重新夺回焦点，否则遥控器事件会落到窗口外的空处
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    model.drawerOpen = false
                    model.settingsOpen = false
                    aboutOpen = false
                    // 用户可能刚在系统设置里勾掉/勾上无障碍服务，回来时同步一次
                    guardOn = Guard.enabled(ctx)
                    runCatching { root.requestFocus() }
                }
                // 进入后台就停掉网络轮询，不让桌面在别的应用前面空转
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
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
                SettingItem.SoftFocus -> model.changeSoftBackdrop(!model.softBackdrop)
                SettingItem.Saver -> {
                    val next = model.saverStyle.next()
                    model.changeSaver(next)
                    val ok = ScreenSaver.applyToSystem(ctx)
                    val msg = when {
                        !ok -> "屏保需要授权：adb shell pm grant app.xingchuan.tv android.permission.WRITE_SECURE_SETTINGS"
                        next == ScreenSaverStyle.Off -> "屏保已交还系统"
                        next == ScreenSaverStyle.Web -> "屏保 · 家居大屏（联网自动连接，无操作 10 分钟后出现）"
                        else -> "屏保 · ${next.label}（无操作 10 分钟后出现）"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
                SettingItem.Reset -> model.restoreDefaults()
            }
        }

        fun activateTop(i: Int) {
            when (TopSlot.entries[i]) {
                // 原来这一格是「信号源」，但本机没有可被第三方调起的输入源界面（切换需系统签名），
                // 点了只会弹提示。改成真正能用的动作：切到下一组内置壁纸。
                TopSlot.Wallpaper -> {
                    val ids = WallpaperGroups.all.map { it.id }
                    val cur = (model.wallpaper as? WallpaperSpec.Group)?.id
                    val next = WallpaperGroups.all[
                        (ids.indexOf(cur).let { if (it < 0) 0 else it + 1 }) % ids.size
                    ]
                    model.changeWallpaper(WallpaperSpec.Group(next.id))
                    Toast.makeText(ctx, "壁纸 · ${next.name}", Toast.LENGTH_SHORT).show()
                }
                TopSlot.Network -> if (!Shortcuts.openNetworkSettings(ctx)) {
                    Toast.makeText(ctx, "打不开网络设置", Toast.LENGTH_SHORT).show()
                }
                // 大屏页同时是大屏屏保的登录入口：先在这里登录一次，待机就省掉登录框
                TopSlot.DashBoard -> WebScreenActivity.open(ctx)
                TopSlot.Settings -> {
                    model.settingsOpen = true
                    settingIndex = 0
                }
                TopSlot.About -> aboutOpen = true
            }
        }

        /** 抽屉里对单个应用可做的事。文案随当前状态变，用户一眼知道按了会发生什么。 */
        fun drawerActions(app: AppItem): List<AppAction> =
            if (app.key in model.dock) {
                listOf(AppAction.MoveLeft, AppAction.MoveRight, AppAction.RemoveDock, AppAction.Hide, AppAction.Cancel)
            } else {
                listOf(AppAction.AddDock, AppAction.Hide, AppAction.Cancel)
            }

        fun handleKey(e: KeyEvent): Boolean {
            if (e.type != KeyEventType.KeyDown) return false

            // 关于面板：任意键关闭。忽略长按产生的重复事件，
            // 否则按住确认键打开时会立刻被自己的重复键关掉。
            if (aboutOpen) {
                if (e.nativeKeyEvent.repeatCount == 0) aboutOpen = false
                return true
            }

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

            // 抽屉里应用的操作菜单：上下选、确认执行、返回取消
            if (model.drawerOpen && actionOpen) {
                val app = visible.getOrNull(gridIndex)
                if (app == null) {
                    actionOpen = false
                    return true
                }
                val actions = drawerActions(app)
                return when (e.key) {
                    Key.DirectionUp -> {
                        actionIndex = (actionIndex - 1).coerceAtLeast(0); true
                    }
                    Key.DirectionDown -> {
                        actionIndex = (actionIndex + 1).coerceAtMost(actions.lastIndex); true
                    }
                    Key.Back, Key.Menu -> {
                        actionOpen = false; true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when (actions[actionIndex]) {
                            AppAction.AddDock -> if (!model.toggleDock(app)) {
                                Toast.makeText(
                                    ctx,
                                    "Dock 已满（最多 7 个），先移出一个再添加",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            AppAction.RemoveDock -> model.toggleDock(app)
                            AppAction.MoveLeft -> if (!model.moveDock(app, -1)) {
                                Toast.makeText(ctx, "已经在最左边了", Toast.LENGTH_SHORT).show()
                            }
                            AppAction.MoveRight -> if (!model.moveDock(app, 1)) {
                                Toast.makeText(ctx, "已经在最右边了", Toast.LENGTH_SHORT).show()
                            }
                            AppAction.Hide -> {
                                model.hide(app)
                                // 隐藏后该项从列表里消失，索引要收回到有效范围
                                gridIndex = gridIndex.coerceAtMost((visible.size - 1).coerceAtLeast(0))
                                Toast.makeText(
                                    ctx,
                                    "已隐藏「${app.label}」，在设置 → 恢复隐藏里可取回",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            AppAction.Cancel -> Unit
                        }
                        // 排序类动作不关菜单，方便连续按左右挪几格
                        val keepOpen = actions[actionIndex] == AppAction.MoveLeft ||
                            actions[actionIndex] == AppAction.MoveRight
                        if (!keepOpen) actionOpen = false
                        true
                    }
                    // 菜单打开时吞掉左右键，避免背后的网格跟着乱动
                    else -> true
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
                        if (visible.isNotEmpty()) {
                            actionOpen = true
                            actionIndex = 0
                        }
                        true
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

            // 主屏：顶栏 <-> 推荐位 <-> Dock，Dock 下键进入抽屉
            return when (e.key) {
                Key.DirectionLeft -> {
                    when (zone) {
                        Zone.Top -> if (topIndex > 0) topIndex--
                        Zone.Dock -> dockIndex = (dockIndex - 1).coerceAtLeast(0)
                        Zone.Shelf -> shelfIndex = (shelfIndex - 1).coerceAtLeast(0)
                    }
                    true
                }
                Key.DirectionRight -> {
                    when (zone) {
                        Zone.Top -> if (topIndex < TOP_SLOT_COUNT - 1) topIndex++
                        Zone.Dock -> dockIndex = (dockIndex + 1).coerceAtMost(dockSlots - 1)
                        Zone.Shelf ->
                            shelfIndex = (shelfIndex + 1).coerceAtMost(shelfApps.lastIndex.coerceAtLeast(0))
                    }
                    true
                }
                Key.DirectionUp -> {
                    // 推荐位为空时也不能让焦点消失，Dock 上键直接落到顶栏
                    when (zone) {
                        Zone.Top -> Unit
                        Zone.Shelf -> zone = Zone.Top
                        Zone.Dock -> zone = if (shelfApps.isNotEmpty()) Zone.Shelf else Zone.Top
                    }
                    true
                }
                Key.DirectionDown -> {
                    when (zone) {
                        Zone.Top -> zone = if (shelfApps.isNotEmpty()) Zone.Shelf else Zone.Dock
                        Zone.Shelf -> zone = Zone.Dock
                        Zone.Dock -> {
                            model.drawerOpen = true
                            actionOpen = false
                            gridIndex = 0
                        }
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    when (zone) {
                        Zone.Top -> activateTop(topIndex)
                        Zone.Dock -> if (dockIndex < dockApps.size) open(dockApps[dockIndex]) else {
                            model.drawerOpen = true
                            actionOpen = false
                            gridIndex = 0
                        }
                        Zone.Shelf -> shelfApps.getOrNull(shelfIndex)?.let { open(it) }
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

        WallpaperLayer(model.wallpaper, model.softBackdrop)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = (56 * s).dp, vertical = (36 * s).dp),
        ) {
            TopBar(
                s = s,
                focusedIndex = if (zone == Zone.Top) topIndex else -1,
                networkLabel = netLabel,
            )
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
            if (actionOpen) {
                visible.getOrNull(gridIndex)?.let { app ->
                    DrawerActionSheet(
                        app = app,
                        actions = drawerActions(app).map { it.label },
                        focusedIndex = actionIndex,
                        s = s,
                    )
                }
            }
        }

        if (model.settingsOpen) {
            SettingsOverlay(
                model = model,
                focusedIndex = settingIndex,
                guardOn = guardOn,
                s = s,
            )
        }

        if (aboutOpen) {
            AboutOverlay(guardOn = guardOn, s = s)
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
                        .padding(horizontal = (12 * s).dp, vertical = (13 * s).dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppIcon(
                        icon = app.icon,
                        fallbackText = app.label,
                        size = (44 * s).dp,
                        corner = (12 * s).dp,
                    )
                    Spacer(Modifier.height((10 * s).dp))
                    Txt(
                        text = app.label,
                        style = Type.AppLabel.copy(
                            fontSize = (16 * s).sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
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
            .padding(vertical = (6 * s).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height((4 * s).dp))
        // 应用名允许折两行并按字数缩字号：电视上应用名普遍偏长（「超级电视账号」「银河奇异果 TV」），
        // 单行省略会变成「超级电视…」，用户看不出是哪个应用
        val nameSize = when {
            label.length <= 4 -> 12f
            label.length <= 6 -> 11f
            else -> 10f
        }
        Txt(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            style = Type.AppLabel.copy(
                fontSize = (nameSize * s).sp,
                lineHeight = (nameSize * 1.15f * s).sp,
                textAlign = TextAlign.Center,
            ),
            color = if (focused) Ink.Text else Color(0xFFD6E0F2),
            maxLines = 2,
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

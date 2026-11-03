package app.xingchuan.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xingchuan.tv.core.AppItem

/**
 * 应用抽屉：全屏覆盖，网格排列。
 *
 * 焦点不落在网格项上，而是由外层统一按 index 计算，滚动通过 animateScrollToItem 跟随，
 * 这样 D-pad 行为完全可控，也不依赖 LazyGrid 自身的焦点滚动策略。
 */
@Composable
fun AppDrawer(
    apps: List<AppItem>,
    focusedIndex: Int,
    cols: Int,
    s: Float,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(focusedIndex, cols) {
        val row = focusedIndex / cols.coerceAtLeast(1)
        val target = ((row - 1).coerceAtLeast(0)) * cols
        runCatching { gridState.animateScrollToItem(target) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050A14))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = (56 * s).dp, vertical = (34 * s).dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(text = "全部应用", style = Type.Title.copy(fontSize = (26 * s).sp))
                Spacer(Modifier.padding(horizontal = (6 * s).dp))
                Txt(
                    text = "${apps.size} 个",
                    style = Type.Section.copy(fontSize = (14 * s).sp),
                )
                Spacer(Modifier.weight(1f))
                Txt(
                    text = "菜单键 加入/移出 Dock   ·   返回键 关闭",
                    style = Type.Hint.copy(fontSize = (12 * s).sp),
                )
            }
            Spacer(Modifier.height((16 * s).dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy((8 * s).dp),
                verticalArrangement = Arrangement.spacedBy((8 * s).dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(apps, key = { _, app -> app.key }) { i, app ->
                    DrawerTile(app = app, focused = i == focusedIndex, s = s)
                }
            }
        }
    }
}

@Composable
private fun DrawerTile(app: AppItem, focused: Boolean, s: Float) {
    val shape = RoundedCornerShape((16 * s).dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Color(0x26FFFFFF) else Color(0x08FFFFFF))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Ink.GlassStrokeOn else Color(0x1AFFFFFF),
                shape = shape,
            )
            .focusScale(focused, to = 1.04f)
            .padding(horizontal = (10 * s).dp, vertical = (9 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            icon = app.icon,
            fallbackText = app.label,
            size = (46 * s).dp,
            corner = (11 * s).dp,
        )
        Spacer(Modifier.padding(horizontal = (4 * s).dp))
        Txt(
            text = app.label,
            style = Type.AppLabel.copy(fontSize = (14 * s).sp),
            color = if (focused) Ink.Text else Color(0xFFCBD5E8),
        )
    }
}

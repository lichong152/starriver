package app.xingchuan.tv.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xingchuan.tv.core.LauncherModel
import app.xingchuan.tv.core.Presets
import app.xingchuan.tv.core.WallpaperGroup
import app.xingchuan.tv.core.WallpaperGroups
import app.xingchuan.tv.core.WallpaperPreset
import app.xingchuan.tv.core.WallpaperSpec

sealed interface SettingItem {
    data class Preset(val preset: WallpaperPreset) : SettingItem
    data class GroupWall(val group: WallpaperGroup) : SettingItem
    data object PickImage : SettingItem
    data object PickVideo : SettingItem
    data object GuardToggle : SettingItem
    data object SoftFocus : SettingItem
    data object Saver : SettingItem
    data object Unhide : SettingItem
    data object Reset : SettingItem
}

object SettingsItems {
    /**
     * 16 格，按四行排，每行 4 个——16 正好整除，四行等宽不会出现「末行只剩一个」。
     * 顺序即导航顺序，第 5 格是第二行第一格。
     */
    const val PER_ROW = 4

    val all: List<SettingItem> =
        WallpaperGroups.all.map { SettingItem.GroupWall(it) } +
            listOf(
                SettingItem.PickImage,
                SettingItem.PickVideo,
                SettingItem.GuardToggle,
                SettingItem.SoftFocus,
                SettingItem.Saver,
                SettingItem.Unhide,
                SettingItem.Reset,
            ) +
            Presets.all.map { SettingItem.Preset(it) }

    val total: Int get() = all.size

    val rows: List<List<SettingItem>> get() = all.chunked(PER_ROW)
}

/** 壁纸选择器。电视上多数固件没有 DocumentsUI，SAF 打不开时给出提示而不是崩掉。 */
class WallpaperPicker(
    private val onImage: () -> Unit,
    private val onVideo: () -> Unit,
) {
    fun image() = onImage()
    fun video() = onVideo()
}

@Composable
fun rememberWallpaperPicker(model: LauncherModel): WallpaperPicker {
    val ctx = LocalContext.current

    fun persistAndSet(uri: android.net.Uri, isVideo: Boolean) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        model.changeWallpaper(
            if (isVideo) WallpaperSpec.Video(uri.toString()) else WallpaperSpec.Picture(uri.toString())
        )
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistAndSet(it, isVideo = false) }
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistAndSet(it, isVideo = true) }
    }

    return remember(imageLauncher, videoLauncher) {
        WallpaperPicker(
            onImage = {
                runCatching { imageLauncher.launch(arrayOf("image/*")) }
                    .onFailure { noPicker(ctx) }
            },
            onVideo = {
                runCatching { videoLauncher.launch(arrayOf("video/*")) }
                    .onFailure { noPicker(ctx) }
            },
        )
    }
}

private fun noPicker(ctx: Context) {
    Toast.makeText(ctx, "本机没有文件选择器，请改用内置壁纸", Toast.LENGTH_LONG).show()
}

/** 设置面板：底部弹出的玻璃抽屉，横向线性导航。 */
@Composable
fun SettingsOverlay(
    model: LauncherModel,
    focusedIndex: Int,
    guardOn: Boolean,
    s: Float,
) {
    val softOn = model.softBackdrop
    val saver = model.saverStyle
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD9050812)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = (34 * s).dp, topEnd = (34 * s).dp),
            strong = true,
            solid = true,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (48 * s).dp, vertical = (30 * s).dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        text = "设置",
                        style = Type.Title.copy(fontSize = (26 * s).sp),
                    )
                    Spacer(Modifier.width((14 * s).dp))
                    Txt(
                        text = "壁纸 / 应用",
                        style = Type.Section.copy(fontSize = (14 * s).sp),
                    )
                    Spacer(Modifier.weight(1f))
                    Txt(
                        text = "返回键 关闭",
                        style = Type.Hint.copy(fontSize = (12 * s).sp),
                    )
                }
                Spacer(Modifier.height((20 * s).dp))
                SettingsItems.rows.forEachIndexed { rowIdx, row ->
                    if (rowIdx > 0) Spacer(Modifier.height((10 * s).dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy((8 * s).dp),
                    ) {
                        row.forEachIndexed { colIdx, item ->
                            SettingTile(
                                item = item,
                                modifier = Modifier.weight(1f),
                                focused = rowIdx * SettingsItems.PER_ROW + colIdx == focusedIndex,
                                selected = when (item) {
                                    is SettingItem.Preset ->
                                        (model.wallpaper as? WallpaperSpec.Preset)?.id == item.preset.id
                                    is SettingItem.GroupWall ->
                                        (model.wallpaper as? WallpaperSpec.Group)?.id == item.group.id
                                    else -> false
                                },
                                guardOn = guardOn,
                                softOn = softOn,
                                saver = saver,
                                hiddenCount = model.hidden.size,
                                s = s,
                            )
                        }
                    }
                }
                Spacer(Modifier.height((18 * s).dp))
                Txt(
                    text = "壁纸即时生效。四组内置轮播各 10 张，每 10 秒换一张；「柔焦」让背景像隔着毛玻璃，关掉则显示锐利原图；「屏保」切换待机画面（关闭即交还系统原屏保）；「守护·开」需在系统无障碍里勾选星川桌面守护。",
                    style = Type.Hint.copy(fontSize = (12 * s).sp),
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SettingTile(
    item: SettingItem,
    modifier: Modifier = Modifier,
    focused: Boolean,
    selected: Boolean,
    hiddenCount: Int,
    guardOn: Boolean,
    softOn: Boolean,
    saver: app.xingchuan.tv.ScreenSaverStyle,
    s: Float,
) {
    val shape = RoundedCornerShape((18 * s).dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (focused) Color(0x26FFFFFF) else Color(0x0DFFFFFF))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Ink.GlassStrokeOn
                    selected -> Ink.Accent.copy(alpha = 0.7f)
                    item is SettingItem.GuardToggle && guardOn -> Ink.Accent.copy(alpha = 0.7f)
                    item is SettingItem.SoftFocus && softOn -> Ink.Accent.copy(alpha = 0.7f)
                    item is SettingItem.Saver && saver != app.xingchuan.tv.ScreenSaverStyle.Off ->
                        Ink.Accent.copy(alpha = 0.7f)
                    else -> Ink.GlassStroke
                },
                shape = shape,
            )
            .focusScale(focused, to = 1.07f)
            .padding(vertical = (12 * s).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (item) {
            is SettingItem.Preset -> {
                Box(
                    Modifier
                        .size(width = (56 * s).dp, height = (40 * s).dp)
                        .clip(RoundedCornerShape((10 * s).dp))
                        .background(Brush.verticalGradient(item.preset.colors))
                )
                Spacer(Modifier.height((8 * s).dp))
                Txt(
                    text = item.preset.name,
                    style = Type.Badge.copy(fontSize = (11 * s).sp),
                    color = if (focused || selected) Ink.Text else Ink.TextDim,
                )
            }

            else -> {
                val (glyph, label) = when (item) {
                    is SettingItem.GroupWall -> item.group.glyph to item.group.name
                    SettingItem.PickImage -> "图" to "选择图片"
                    SettingItem.PickVideo -> "影" to "选择视频"
                    SettingItem.GuardToggle -> "守" to if (guardOn) "守护·开" else "守护·关"
                    SettingItem.SoftFocus -> "柔" to if (softOn) "柔焦·开" else "柔焦·关"
                    SettingItem.Saver -> "屏" to if (saver == app.xingchuan.tv.ScreenSaverStyle.Off) {
                        "屏保·关"
                    } else {
                        "屏保·${saver.label}"
                    }
                    SettingItem.Unhide -> "隐" to "恢复隐藏"
                    SettingItem.Reset -> "复" to "恢复默认"
                    is SettingItem.Preset -> "" to ""
                }
                Box(
                    Modifier
                        .size((40 * s).dp)
                        .clip(CircleShape)
                        .background(Color(0x1FFFFFFF))
                        .border(1.dp, Ink.GlassStroke, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(
                        text = glyph,
                        style = Type.AppLabel.copy(fontSize = (18 * s).sp),
                        color = if (focused ||
                            (item is SettingItem.GuardToggle && guardOn) ||
                            (item is SettingItem.SoftFocus && softOn) ||
                            (item is SettingItem.Saver && saver != app.xingchuan.tv.ScreenSaverStyle.Off)
                        ) {
                            Ink.Accent
                        } else {
                            Ink.TextDim
                        },
                    )
                }
                Spacer(Modifier.height((8 * s).dp))
                val text = if (item == SettingItem.Unhide && hiddenCount > 0) "$label $hiddenCount" else label
                Txt(
                    text = text,
                    style = Type.Badge.copy(fontSize = (11 * s).sp),
                    color = if (focused) Ink.Text else Ink.TextDim,
                )
            }
        }
    }
}

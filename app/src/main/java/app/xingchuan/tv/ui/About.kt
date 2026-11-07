package app.xingchuan.tv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xingchuan.tv.BuildConfig

/** 关于：版本、包名、设备、守护状态、壁纸授权。任意键关闭。 */
@Composable
fun AboutOverlay(guardOn: Boolean, s: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3050812)),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            modifier = Modifier.width((620 * s).dp),
            shape = RoundedCornerShape((28 * s).dp),
            strong = true,
            solid = true,
        ) {
            Column(Modifier.fillMaxWidth().padding((32 * s).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("关于星川", Type.Title.copy(fontSize = (26 * s).sp))
                    Spacer(Modifier.width((12 * s).dp))
                    Txt("STAR RIVER TV", Type.Badge.copy(fontSize = (12 * s).sp))
                    Spacer(Modifier.weight(1f))
                    Txt("任意键 关闭", Type.Hint.copy(fontSize = (12 * s).sp))
                }
                Spacer(Modifier.height((20 * s).dp))
                InfoRow("版本", "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", s)
                InfoRow("包名", "app.xingchuan.tv", s)
                InfoRow(
                    "设备",
                    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                    s,
                )
                InfoRow(
                    "桌面守护",
                    if (guardOn) "已开启（无障碍）" else "未开启，主页键可能被原厂桌面接管",
                    s,
                )
                InfoRow("壁纸", "40 张 · Unsplash License", s)
                InfoRow("源码", "github.com/lichong152/starriver", s)
                Spacer(Modifier.height((14 * s).dp))
                Txt(
                    "星川不联网、不上传任何数据；网络类型只读本机网卡状态。顶栏「壁纸」一键切到下一组，" +
                        "因为切换输入要系统权限。",
                    Type.Hint.copy(fontSize = (12 * s).sp),
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, s: Float) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = (7 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(label, Type.Section.copy(fontSize = (14 * s).sp), color = Ink.TextDim)
        Spacer(Modifier.width((16 * s).dp))
        Txt(value, Type.Body.copy(fontSize = (14 * s).sp), color = Ink.Text)
    }
}

package app.xingchuan.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 顶栏四个快捷动作，顺序即遥控器左右导航顺序。 */
enum class TopSlot { Source, Network, Settings, About }

private val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

val TOP_SLOT_COUNT = TopSlot.entries.size

/**
 * 顶栏：左侧问候语 + 大时钟 + 行内日期，右侧一排功能胶囊。
 *
 * 胶囊是真实可操作项（信号源 / 有线网络 / 设置 / 关于），焦点落在顶栏时用左右键切换、
 * 确认键执行；不在顶栏时 [focusedIndex] 传 -1，只作为状态显示。
 *
 * 时钟状态刻意留在本组件内部：时间每 10 秒变一次，若把 now 提到 HomeScreen，
 * 每次跳动都会让整棵树重组（包括全屏壁纸与所有卡片）。放在这里就只重组这一块。
 */
@Composable
fun TopBar(
    s: Float,
    focusedIndex: Int,
    networkLabel: String,
) {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            delay(10_000)
        }
    }

    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time) }
    val date = remember(now) {
        "${now.get(Calendar.MONTH) + 1}月${now.get(Calendar.DAY_OF_MONTH)}日  " +
            WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1]
    }
    val greeting = remember(now) { greetingOf(now.get(Calendar.HOUR_OF_DAY)) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Txt(
                text = greeting,
                style = Type.Badge.copy(fontSize = (13 * s).sp, letterSpacing = 3.sp),
                color = Ink.TextFaint,
            )
            Spacer(Modifier.height((8 * s).dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(
                    text = time,
                    style = Type.Clock.copy(fontSize = (68 * s).sp, letterSpacing = 2.sp),
                )
                Spacer(Modifier.width((16 * s).dp))
                // 时间与日期之间一道细分隔，和参考稿一致
                Box(
                    Modifier
                        .padding(bottom = (10 * s).dp)
                        .width(1.dp)
                        .height((22 * s).dp)
                        .background(Color(0x3DFFFFFF))
                )
                Spacer(Modifier.width((16 * s).dp))
                Box(Modifier.padding(bottom = (12 * s).dp)) {
                    Txt(text = date, style = Type.Date.copy(fontSize = (18 * s).sp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy((10 * s).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopSlot.entries.forEachIndexed { i, slot ->
                Chip(
                    slot = slot,
                    label = when (slot) {
                        TopSlot.Source -> "信号源"
                        TopSlot.Network -> networkLabel
                        TopSlot.Settings -> "设置"
                        TopSlot.About -> "关于"
                    },
                    focused = i == focusedIndex,
                    s = s,
                )
            }
        }
    }
}

private fun greetingOf(hour: Int): String = when (hour) {
    in 5..7 -> "早上好"
    in 8..10 -> "上午好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

@Composable
private fun Chip(slot: TopSlot, label: String, focused: Boolean, s: Float) {
    val shape = RoundedCornerShape((24 * s).dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (focused) Color(0x33FFFFFF) else Color(0x1FFFFFFF))
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) Ink.GlassStrokeOn else Ink.GlassStroke,
                shape = shape,
            )
            .focusScale(focused, to = 1.06f)
            .padding(horizontal = (16 * s).dp, vertical = (9 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(
            slot = slot,
            size = (15 * s).dp,
            color = if (focused) Ink.Accent else Ink.TextFaint,
        )
        Spacer(Modifier.width((8 * s).dp))
        Txt(
            text = label,
            style = Type.Badge.copy(fontSize = (13 * s).sp, letterSpacing = 0.4.sp),
            color = if (focused) Ink.Text else Ink.TextFaint,
        )
    }
}

/**
 * 胶囊图标用 Canvas 画，避免为了四个小图标引入整套 material-icons（会让 APK 变大）。
 * 统一线宽、圆头，视觉上是一套。
 */
@Composable
private fun Glyph(slot: TopSlot, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.10f
        val c = Offset(w / 2f, h / 2f)
        when (slot) {
            // 信号：底部圆点 + 三道同心弧
            TopSlot.Source -> {
                val base = Offset(w / 2f, h * 0.80f)
                drawCircle(color, radius = w * 0.075f, center = base)
                for (i in 1..3) {
                    val r = w * 0.17f * i
                    drawArc(
                        color = color,
                        startAngle = 205f,
                        sweepAngle = 130f,
                        useCenter = false,
                        topLeft = Offset(base.x - r, base.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                    )
                }
            }
            // 网络：一个中心节点连到两个子节点
            TopSlot.Network -> {
                val top = Offset(w / 2f, h * 0.26f)
                val left = Offset(w * 0.22f, h * 0.78f)
                val right = Offset(w * 0.78f, h * 0.78f)
                drawLine(color, top, left, strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color, top, right, strokeWidth = sw, cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.12f, center = top)
                drawCircle(color, radius = w * 0.12f, center = left)
                drawCircle(color, radius = w * 0.12f, center = right)
            }
            // 设置：圆环 + 八根短齿 + 中心轴
            TopSlot.Settings -> {
                drawCircle(
                    color = color,
                    radius = w * 0.24f,
                    center = c,
                    style = Stroke(width = sw),
                )
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    val r1 = w * 0.32f
                    val r2 = w * 0.45f
                    drawLine(
                        color = color,
                        start = Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
                        end = Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
            }
            // 关于：信息圆
            TopSlot.About -> {
                drawCircle(
                    color = color,
                    radius = w * 0.40f,
                    center = c,
                    style = Stroke(width = sw),
                )
                drawCircle(color, radius = w * 0.055f, center = Offset(w / 2f, h * 0.34f))
                drawLine(
                    color = color,
                    start = Offset(w / 2f, h * 0.48f),
                    end = Offset(w / 2f, h * 0.70f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

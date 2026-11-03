package app.xingchuan.tv.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** 配色：深色为底、白色半透明为玻璃、青蓝为高亮。 */
object Ink {
    val Bg = Color(0xFF05070D)
    val Text = Color(0xFFF3F6FF)
    val TextDim = Color(0xFF9FAAC6)
    val TextFaint = Color(0xB3C9D6EE)
    val Accent = Color(0xFF7FE7E0)
    val AccentDeep = Color(0xFF4C7DF0)
    val GlassFill = Color(0x14FFFFFF)
    val GlassFillStrong = Color(0x24FFFFFF)
    val GlassStroke = Color(0x2EFFFFFF)
    val GlassStrokeOn = Color(0xB3FFFFFF)
}

/** 字号在此集中，电视观看距离下整体偏大。 */
object Type {
    val Clock = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 62.sp,
        letterSpacing = 1.sp,
        color = Ink.Text,
        // 风光壁纸亮部很亮，纯白字容易糊；描一层柔和投影把字从背景里拔出来
        shadow = Shadow(Color(0x8C000000), Offset(0f, 2f), 14f),
    )
    val Date = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        letterSpacing = 0.5.sp,
        color = Color(0xFFE7EFFF),
        shadow = Shadow(Color(0x99000000), Offset(0f, 1f), 10f),
    )
    val AppLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        color = Ink.Text,
    )
    val AppLabelDim = AppLabel.copy(color = Ink.TextDim)
    val Title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        color = Ink.Text,
    )
    val Section = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 1.sp,
        color = Ink.TextDim,
    )
    val Body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        color = Ink.TextDim,
    )
    val Hint = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = Ink.TextFaint,
    )
    val Badge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        color = Ink.TextDim,
    )
}

/** 统一文本入口：默认单行省略，避免 BasicText 的默认无限行把版式撑坏。 */
@Composable
fun Txt(
    text: String,
    style: TextStyle = Type.Body,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    color: Color? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color != null) style.copy(color = color) else style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
    )
}

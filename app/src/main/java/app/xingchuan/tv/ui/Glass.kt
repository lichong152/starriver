package app.xingchuan.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃面板：竖直双色渐变的半透明白 + 细描边。
 *
 * 真正的背景模糊（backdrop blur）在 Compose 里没有系统支持，所以效果靠
 * 面板半透明叠加 + 壁纸上的压暗渐变来实现。
 *
 * 注意：`Modifier.blur` 在 API 31 以下（含本机 Android 9）是空实现，
 * 别指望它给面板做磨砂底——那只是看起来像，实际什么都没画。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    focused: Boolean = false,
    strong: Boolean = false,
    /**
     * 实底面板。抽屉、设置这类“盖在内容上”的大面积面板必须用实底，
     * 否则背后的图标会透出来和按钮混在一起（真机上验证过）。
     */
    solid: Boolean = false,
    /** 深色玻璃：叠在亮照片上的条状面板用它，浅玻璃配白字会糊成一片。 */
    dark: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    // 数值是照着实机截图调的：0x16 在亮风光照上等于没有，卡片会失去轮廓
    val top = when {
        solid -> Color(0xF00E1524)
        dark -> Color(0x57101826)
        focused -> Color(0x44FFFFFF)
        strong -> Color(0x3AFFFFFF)
        else -> Color(0x2EFFFFFF)
    }
    val bottom = when {
        solid -> Color(0xE8090E18)
        dark -> Color(0x6B0C1220)
        focused -> Color(0x24FFFFFF)
        strong -> Color(0x22FFFFFF)
        else -> Color(0x16FFFFFF)
    }
    val stroke = when {
        solid -> Color(0x33FFFFFF)
        dark -> Color(0x38FFFFFF)
        focused -> Ink.GlassStrokeOn
        strong -> Color(0x45FFFFFF)
        else -> Color(0x3DFFFFFF)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)), shape)
            .border(if (focused) 2.dp else 1.dp, stroke, shape),
        content = content,
    )
}

/** 焦点缩放。电视上这是最主要的“我选中了”的反馈，弹簧参数偏快，避免连按时光标跟不上手感。 */
fun Modifier.focusScale(focused: Boolean, from: Float = 1f, to: Float = 1.07f): Modifier =
    composed {
        val scale by animateFloatAsState(
            targetValue = if (focused) to else from,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = 900f),
            label = "focusScale",
        )
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }

/**
 * 应用图标。正常情况直接画 [app.xingchuan.tv.core.Icons] 解出的真实图标；
 * 只有应用确实没提供可解码的图标时才回退成首字卡片——颜色由名称哈希决定，
 * 同一个应用每次都是同一个颜色，不会在重扫后变色。
 */
@Composable
fun AppIcon(
    icon: ImageBitmap?,
    fallbackText: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = size * 0.24f,
) {
    val shape = RoundedCornerShape(corner)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier.size(size).clip(shape),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
        )
    } else {
        val hue = ((fallbackText.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.hsv(hue, 0.52f, 0.62f),
                            Color.hsv((hue + 26f) % 360f, 0.62f, 0.38f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Txt(
                text = fallbackText.take(1),
                style = Type.AppLabel.copy(fontSize = Type.AppLabel.fontSize * 1.6f),
                color = Color.White,
            )
        }
    }
}

/** 一段极细的分隔线，用于面板内部分区。 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 1.dp, height = 1.dp)
            .background(Ink.GlassStroke)
    )
}

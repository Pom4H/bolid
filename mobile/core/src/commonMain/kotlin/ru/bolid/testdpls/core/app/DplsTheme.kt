package ru.bolid.testdpls.core.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import ru.bolid.testdpls.core.domain.UiTheme

/** Official Bolid palette from bolid.ru, tuned for a dark field instrument. */
internal object Bolid {
    val Navy = Color(0xFF0B0E10)
    val Surface = Color(0xFF141A1E)
    val SurfaceRaised = Color(0xFF1C242A)
    val Line = Color(0xFF2A343C)
    val Blue = Color(0xFF0072BC)
    val BlueDeep = Color(0xFF0073FF)
    val Cyan = Color(0xFF00CCFF)
    val Text = Color(0xFFF4F6F7)
    val Muted = Color(0xFF8E9396)
    val Ok = Color(0xFF34C759)
    val IdentifyLed = Color(0xFF00F540)
    val Warn = Color(0xFFFF8A3D)
    val Fault = Color(0xFFD45252)
    val Mark = Brush.verticalGradient(listOf(Cyan, BlueDeep))
    val Glow = Brush.verticalGradient(
        colors = listOf(Blue.copy(alpha = 0.28f), Color.Transparent),
    )
    val CardShape = RoundedCornerShape(20.dp)
    val ControlShape = RoundedCornerShape(16.dp)
    val PadHeight = 64.dp
    val PadGap = 10.dp
}

@Composable
internal fun Modifier.bolidFeel(
    pressed: Boolean,
    hovered: Boolean = false,
    enabled: Boolean = true,
    dimmed: Boolean = false,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            pressed -> 0.96f
            hovered -> 1.03f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 720f),
        label = "bolidFeelScale",
    )
    val alpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.42f
            dimmed -> 0.52f
            else -> 1f
        },
        animationSpec = tween(160),
        label = "bolidFeelAlpha",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

internal data class BolidColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val ok: Color,
    val onOk: Color,
    val warn: Color,
    val fault: Color,
    val glow: Brush,
    val isDark: Boolean,
) {
    companion object {
        fun dark(): BolidColors = BolidColors(
            background = Bolid.Navy,
            surface = Bolid.Surface,
            surfaceRaised = Bolid.SurfaceRaised,
            line = Bolid.Line,
            text = Bolid.Text,
            muted = Bolid.Muted,
            ok = Color(0xFF30D158),
            onOk = Bolid.Navy,
            warn = Color(0xFFFF8A3D),
            fault = Color(0xFFD45252),
            glow = Brush.verticalGradient(listOf(Bolid.Blue.copy(alpha = 0.28f), Color.Transparent)),
            isDark = true,
        )

        fun light(): BolidColors = BolidColors(
            background = Color(0xFFF3F4F5),
            surface = Color.White,
            surfaceRaised = Color(0xFFEEF1F3),
            line = Color(0xFFDCE2E6),
            text = Color(0xFF252C30),
            muted = Color(0xFF5E656A),
            ok = Color(0xFF34C759),
            onOk = Color.White,
            warn = Color(0xFFC56A12),
            fault = Color(0xFFD45252),
            glow = Brush.verticalGradient(listOf(Bolid.Blue.copy(alpha = 0.10f), Color.Transparent)),
            isDark = false,
        )
    }
}

internal val LocalBolidColors = staticCompositionLocalOf { BolidColors.dark() }

internal data class BolidLayout(
    val gutter: Dp,
    val compact: Boolean,
    val padHeight: Dp,
    val deviceIcon: Dp,
    val cardPad: Dp,
)

internal val LocalBolidLayout = staticCompositionLocalOf {
    BolidLayout(gutter = 20.dp, compact = false, padHeight = 64.dp, deviceIcon = 36.dp, cardPad = 16.dp)
}

@Composable
internal fun rememberBolidLayout(): BolidLayout {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val compact = width < 400.dp
    return BolidLayout(
        gutter = when {
            width < 360.dp -> 14.dp
            width < 400.dp -> 16.dp
            width < 430.dp -> 20.dp
            else -> 24.dp
        },
        compact = compact,
        padHeight = if (compact) 56.dp else 64.dp,
        deviceIcon = if (compact) 36.dp else 40.dp,
        cardPad = if (compact) 12.dp else 16.dp,
    )
}

@Composable
internal fun BolidTheme(theme: UiTheme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        UiTheme.DARK -> true
        UiTheme.LIGHT -> false
        UiTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) BolidColors.dark() else BolidColors.light()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Bolid.Blue,
            onPrimary = Color.White,
            secondary = Bolid.Cyan,
            onSecondary = Bolid.Navy,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.muted,
            outline = colors.line,
            error = colors.fault,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = Bolid.Blue,
            onPrimary = Color.White,
            secondary = Bolid.Blue,
            onSecondary = Color.White,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.muted,
            outline = colors.line,
            error = colors.fault,
            onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalBolidColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
internal fun Modifier.bolidCard(): Modifier {
    val colors = LocalBolidColors.current
    return clip(Bolid.CardShape).background(colors.surface).border(1.dp, colors.line, Bolid.CardShape)
}

@Composable
internal fun BolidMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(Bolid.Mark)
            .padding(size * 0.1f),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val path = bolidBPath()
            val scale = minOf(this.size.width / 110.1f, this.size.height / 110.1f)
            path.transform(Matrix().apply { scale(scale, scale) })
            drawPath(path, Color.White)
        }
    }
}

@Composable
internal fun BrizTDeviceIcon(
    size: Dp,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    lit: Boolean = false,
    ledColor: Color = Bolid.IdentifyLed,
    animationMs: Int = 45,
) {
    val colors = LocalBolidColors.current
    val amount by animateFloatAsState(
        if (lit) 1f else 0f,
        tween(durationMillis = animationMs, easing = LinearEasing),
        label = "deviceLed",
    )
    val tint by animateColorAsState(ledColor, tween(animationMs), label = "deviceLedTint")
    val painter = painterResource(Res.drawable.briz_t)
    val ledLight = painterResource(Res.drawable.briz_t_led)
    val offFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    val imagePad = if (framed) size * 0.06f else 0.dp
    val shadowAlpha = if (framed) 0.16f else 0.18f
    Box(
        modifier
            .size(size)
            .then(
                if (framed) {
                    Modifier
                        .clip(RoundedCornerShape(size * 0.22f))
                        .background(if (colors.isDark) colors.surfaceRaised else Color(0xFFE6EAED))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val pivot = Offset(w * 0.5f, h * 0.88f)
            scale(scaleX = 1.45f, scaleY = 0.42f, pivot = pivot) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Black.copy(alpha = shadowAlpha * 0.50f),
                        0.55f to Color.Black.copy(alpha = shadowAlpha * 0.18f),
                        1f to Color.Transparent,
                        center = pivot,
                        radius = w * 0.52f,
                    ),
                    radius = w * 0.52f,
                    center = pivot,
                )
            }
            scale(scaleX = 1.10f, scaleY = 0.28f, pivot = pivot) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Black.copy(alpha = shadowAlpha * 0.32f),
                        0.50f to Color.Black.copy(alpha = shadowAlpha * 0.12f),
                        1f to Color.Transparent,
                        center = pivot,
                        radius = w * 0.34f,
                    ),
                    radius = w * 0.34f,
                    center = pivot,
                )
            }
        }
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(imagePad),
            contentScale = ContentScale.Fit,
            colorFilter = offFilter,
        )
        if (amount > 0.04f) {
            Image(
                painter = ledLight,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(imagePad)
                    .graphicsLayer { alpha = amount },
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }
}

internal val BolidNavTest: ImageVector = navIcon("test") {
    moveTo(11f, 2f)
    lineTo(4f, 13.5f)
    horizontalLineTo(10.2f)
    lineTo(9.1f, 22f)
    lineTo(20f, 9.2f)
    horizontalLineTo(13.4f)
    close()
}

internal val BolidNavLog: ImageVector = navIcon("log") {
    moveTo(5f, 4f)
    horizontalLineTo(19f)
    verticalLineTo(6.2f)
    horizontalLineTo(5f)
    close()
    moveTo(5f, 10.9f)
    horizontalLineTo(19f)
    verticalLineTo(13.1f)
    horizontalLineTo(5f)
    close()
    moveTo(5f, 17.8f)
    horizontalLineTo(14.5f)
    verticalLineTo(20f)
    horizontalLineTo(5f)
    close()
}

internal val BolidBack: ImageVector = navIcon("back") {
    moveTo(14.8f, 4.2f)
    lineTo(6.1f, 12f)
    lineTo(14.8f, 19.8f)
    lineTo(16.4f, 18.1f)
    lineTo(9.5f, 12f)
    lineTo(16.4f, 5.9f)
    close()
}

internal val BolidDisconnect: ImageVector = navIcon("disconnect") {
    moveTo(6.4f, 4.8f)
    lineTo(12f, 10.4f)
    lineTo(17.6f, 4.8f)
    lineTo(19.2f, 6.4f)
    lineTo(13.6f, 12f)
    lineTo(19.2f, 17.6f)
    lineTo(17.6f, 19.2f)
    lineTo(12f, 13.6f)
    lineTo(6.4f, 19.2f)
    lineTo(4.8f, 17.6f)
    lineTo(10.4f, 12f)
    lineTo(4.8f, 6.4f)
    close()
}

internal val BolidNavSettings: ImageVector = navIcon("settings") {
    moveTo(10.2f, 2.4f)
    horizontalLineTo(13.8f)
    lineTo(14.6f, 5.2f)
    lineTo(17.1f, 4.2f)
    lineTo(19.8f, 6.9f)
    lineTo(18.8f, 9.4f)
    lineTo(21.6f, 10.2f)
    verticalLineTo(13.8f)
    lineTo(18.8f, 14.6f)
    lineTo(19.8f, 17.1f)
    lineTo(17.1f, 19.8f)
    lineTo(14.6f, 18.8f)
    lineTo(13.8f, 21.6f)
    horizontalLineTo(10.2f)
    lineTo(9.4f, 18.8f)
    lineTo(6.9f, 19.8f)
    lineTo(4.2f, 17.1f)
    lineTo(5.2f, 14.6f)
    lineTo(2.4f, 13.8f)
    verticalLineTo(10.2f)
    lineTo(5.2f, 9.4f)
    lineTo(4.2f, 6.9f)
    lineTo(6.9f, 4.2f)
    lineTo(9.4f, 5.2f)
    close()
    moveTo(12f, 8.4f)
    curveTo(10.01f, 8.4f, 8.4f, 10.01f, 8.4f, 12f)
    curveTo(8.4f, 13.99f, 10.01f, 15.6f, 12f, 15.6f)
    curveTo(13.99f, 15.6f, 15.6f, 13.99f, 15.6f, 12f)
    curveTo(15.6f, 10.01f, 13.99f, 8.4f, 12f, 8.4f)
    close()
}

private fun navIcon(name: String, builder: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White), pathBuilder = builder)
    }.build()

private fun bolidBPath(): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    moveTo(99.66f, 38.86f)
    relativeCubicTo(0f, -19.67f, -11.86f, -21.03f, -22.9f, -21.03f)
    relativeLineTo(-66.52f, 0f)
    relativeLineTo(0f, 74.44f)
    relativeLineTo(69.23f, 0f)
    relativeCubicTo(16.23f, 0f, 22.9f, -5.41f, 22.9f, -20.62f)
    relativeCubicTo(0f, -9.05f, -3.44f, -14.57f, -10.31f, -17.59f)
    relativeCubicTo(4.06f, -1.88f, 7.6f, -6.97f, 7.6f, -15.2f)
    close()
    moveTo(33.14f, 35.84f)
    relativeLineTo(40.6f, 0f)
    relativeCubicTo(3.44f, 0f, 5.73f, 1.25f, 5.73f, 5f)
    relativeCubicTo(0f, 3.64f, -2.29f, 5f, -5.73f, 5f)
    relativeLineTo(-40.6f, 0f)
    relativeLineTo(0f, -10f)
    close()
    moveTo(33.14f, 62.49f)
    relativeLineTo(39.66f, 0f)
    relativeCubicTo(3.96f, 0f, 6.67f, 1.05f, 6.67f, 6.15f)
    relativeCubicTo(0f, 3.64f, -2.71f, 5.61f, -6.67f, 5.61f)
    relativeLineTo(-39.66f, 0f)
    relativeLineTo(0f, -11.76f)
    close()
}

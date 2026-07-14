package ru.bolid.testdpls.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.bolid.testdpls.ble.DplsMode

/**
 * Единый набор линейных иконок в стиле рисованных «лампочки»/«Bluetooth».
 * Иконки режимов — схематические: показывают топологию БРИЗ-Т (магистраль
 * +1↔+2 и ответвление +Т) и точку неисправности, чтобы наладчик отличал
 * режимы боковым зрением, не читая мелкий текст.
 */

// Палитра неисправностей: обрыв — янтарный, КЗ — красно-оранжевый.
val BreakAmber = Color(0xFFF2B33C)
val ShortRed = Color(0xFFF2542D)

private fun DrawScope.line(color: Color, a: Offset, b: Offset, w: Float) =
    drawLine(color, a, b, w, StrokeCap.Round)

private fun DrawScope.node(color: Color, c: Offset, r: Float) =
    drawCircle(color, r, c)

/** Зигзаг-«молния» короткого замыкания в точке (cx, cy). */
private fun DrawScope.bolt(color: Color, cx: Float, cy: Float, h: Float, w: Float) {
    val p = Path().apply {
        moveTo(cx + w * .35f, cy - h)
        lineTo(cx - w * .30f, cy + h * .12f)
        lineTo(cx + w * .12f, cy + h * .12f)
        lineTo(cx - w * .35f, cy + h)
    }
    drawPath(p, color, style = Stroke(width = w * .55f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** «Ножницы»-разрыв: две короткие поперечные засечки по краям зазора. */
private fun DrawScope.breakTicks(color: Color, cx: Float, cy: Float, along: Boolean, gap: Float, tick: Float, w: Float) {
    if (along) { // разрыв на горизонтальной линии — вертикальные засечки
        line(color, Offset(cx - gap, cy - tick), Offset(cx - gap, cy + tick), w)
        line(color, Offset(cx + gap, cy - tick), Offset(cx + gap, cy + tick), w)
    } else { // разрыв на вертикальной линии — горизонтальные засечки
        line(color, Offset(cx - tick, cy - gap), Offset(cx + tick, cy - gap), w)
        line(color, Offset(cx - tick, cy + gap), Offset(cx + tick, cy + gap), w)
    }
}

/**
 * Схематическая иконка режима. base — цвет топологии (приглушённый),
 * accent — цвет неисправности.
 */
@Composable
fun ModeSchematic(mode: DplsMode, size: Dp = 26.dp, base: Color, modifier: Modifier = Modifier) {
    val accent = when {
        mode == DplsMode.NORMAL -> base
        mode.title.startsWith("КЗ") -> ShortRed
        else -> BreakAmber
    }
    Canvas(modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .085f
        val lx = w * .16f
        val rx = w * .84f
        val mx = w * .50f
        val ly = w * .40f
        val by = w * .84f
        val nodeR = w * .055f
        val faint = base.copy(alpha = .40f)

        fun mainLine(color: Color, gapAt: Float? = null) {
            if (gapAt == null) {
                line(color, Offset(lx, ly), Offset(rx, ly), sw)
            } else {
                line(color, Offset(lx, ly), Offset(gapAt - w * .09f, ly), sw)
                line(color, Offset(gapAt + w * .09f, ly), Offset(rx, ly), sw)
            }
        }
        fun stub(color: Color, gap: Boolean = false) {
            if (!gap) {
                line(color, Offset(mx, ly), Offset(mx, by), sw)
            } else {
                line(color, Offset(mx, ly), Offset(mx, ly + w * .10f), sw)
                line(color, Offset(mx, by - w * .10f), Offset(mx, by), sw)
            }
        }

        when (mode) {
            DplsMode.NORMAL -> {
                mainLine(base); stub(base)
                node(base, Offset(lx, ly), nodeR)
                node(base, Offset(rx, ly), nodeR)
                node(base, Offset(mx, by), nodeR)
            }
            DplsMode.OPEN_MAIN -> {
                stub(faint)
                mainLine(accent, gapAt = w * .34f)
                breakTicks(accent, w * .34f, ly, along = true, gap = w * .05f, tick = w * .11f, w = sw)
                node(faint, Offset(mx, by), nodeR)
            }
            DplsMode.OPEN_T -> {
                mainLine(faint); stub(accent, gap = true)
                breakTicks(accent, mx, (ly + by) / 2f, along = false, gap = w * .05f, tick = w * .11f, w = sw)
                node(faint, Offset(lx, ly), nodeR)
                node(faint, Offset(rx, ly), nodeR)
                node(accent, Offset(mx, by), nodeR)
            }
            DplsMode.SHORT_1 -> {
                mainLine(faint); stub(faint)
                node(faint, Offset(rx, ly), nodeR); node(faint, Offset(mx, by), nodeR)
                bolt(accent, lx + w * .04f, ly, w * .20f, w * .30f)
            }
            DplsMode.SHORT_2 -> {
                mainLine(faint); stub(faint)
                node(faint, Offset(lx, ly), nodeR); node(faint, Offset(mx, by), nodeR)
                bolt(accent, rx - w * .04f, ly, w * .20f, w * .30f)
            }
            DplsMode.SHORT_T -> {
                mainLine(faint); stub(faint)
                node(faint, Offset(lx, ly), nodeR); node(faint, Offset(rx, ly), nodeR)
                bolt(accent, mx, by - w * .02f, w * .20f, w * .30f)
            }
        }
    }
}

/** Модуль «умный БРИЗ-Т»: корпус с тремя выводами (+1, +2, +Т). */
@Composable
fun DeviceModuleIcon(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .08f
        val left = w * .26f
        val right = w * .74f
        val top = w * .30f
        val bot = w * .70f
        // корпус
        drawRoundRect(
            color,
            topLeft = Offset(left, top),
            size = Size(right - left, bot - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .06f),
            style = Stroke(sw),
        )
        // выводы: два магистральных по бокам и ответвление снизу
        line(color, Offset(w * .08f, w * .50f), Offset(left, w * .50f), sw)
        line(color, Offset(right, w * .50f), Offset(w * .92f, w * .50f), sw)
        line(color, Offset(w * .50f, bot), Offset(w * .50f, w * .92f), sw)
    }
}

/** Вкладка «Испытание»: измерительный прибор (шкала + стрелка). */
@Composable
fun TestTabIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .09f
        val cx = w * .5f
        val cy = w * .66f
        val r = w * .34f
        // дуга шкалы
        drawArc(
            color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        // стрелка
        line(color, Offset(cx, cy), Offset(cx + r * .62f, cy - r * .62f), sw)
        drawCircle(color, w * .05f, Offset(cx, cy))
    }
}

/** Вкладка «Журнал»: строки списка с маркерами. */
@Composable
fun LogTabIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .085f
        val dot = w * .055f
        val xDot = w * .20f
        val xLine0 = w * .36f
        val xLine1 = w * .84f
        listOf(w * .28f, w * .5f, w * .72f).forEach { y ->
            drawCircle(color, dot, Offset(xDot, y))
            line(color, Offset(xLine0, y), Offset(xLine1, y), sw)
        }
    }
}

/** Вкладка «Настройки»: ползунки. */
@Composable
fun SettingsTabIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .085f
        val x0 = w * .16f
        val x1 = w * .84f
        val knobR = w * .085f
        val rows = listOf(w * .30f to w * .62f, w * .70f to w * .38f)
        rows.forEach { (y, knobX) ->
            line(color, Offset(x0, y), Offset(x1, y), sw)
            drawCircle(color.copy(alpha = 1f), knobR + sw * .5f, Offset(knobX, y))
            drawCircle(Color(0xFF071923), knobR, Offset(knobX, y))
            drawCircle(color, knobR, Offset(knobX, y), style = Stroke(sw))
        }
    }
}

/** Галочка в круге — режим «Норма». */
@Composable
fun CheckCircleIcon(color: Color, size: Dp = 26.dp) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .09f
        drawCircle(color, w * .44f, Offset(w * .5f, w * .5f), style = Stroke(sw))
        val p = Path().apply {
            moveTo(w * .30f, w * .52f); lineTo(w * .45f, w * .66f); lineTo(w * .72f, w * .35f)
        }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Предупреждающий треугольник с восклицательным знаком. */
@Composable
fun WarningTriangleIcon(color: Color, size: Dp = 64.dp) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .06f
        val p = Path().apply {
            moveTo(w * .5f, w * .12f)
            lineTo(w * .92f, w * .84f)
            lineTo(w * .08f, w * .84f)
            close()
        }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        line(color, Offset(w * .5f, w * .38f), Offset(w * .5f, w * .62f), sw)
        drawCircle(color, sw * .7f, Offset(w * .5f, w * .74f))
    }
}

/** Документ со строками — формат экспорта. */
@Composable
fun DocIcon(color: Color, size: Dp = 34.dp) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val sw = w * .07f
        val left = w * .24f
        val right = w * .76f
        val top = w * .16f
        val bot = w * .84f
        val fold = w * .18f
        val p = Path().apply {
            moveTo(left, top)
            lineTo(right - fold, top)
            lineTo(right, top + fold)
            lineTo(right, bot)
            lineTo(left, bot)
            close()
        }
        drawPath(p, color, style = Stroke(sw, join = StrokeJoin.Round))
        listOf(w * .42f, w * .56f, w * .70f).forEach { y ->
            line(color, Offset(left + w * .10f, y), Offset(right - w * .10f, y), sw * .8f)
        }
    }
}

package app.wakelink.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wakelink.android.ui.theme.Sunrise
import kotlin.math.cos
import kotlin.math.sin

/**
 * WakeLink's mark: a sun half-risen over the horizon, with rays. Same
 * shape as the launcher icon (res/drawable/ic_launcher_foreground.xml).
 */
@Composable
fun SunriseMark(modifier: Modifier = Modifier, size: Dp = 72.dp, sun: Color = Sunrise, horizon: Color = sun) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val cx = w / 2
        val horizonY = w * 0.66f
        val r = w * 0.24f
        val stroke = w * 0.06f

        drawArc(
            color = sun,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx - r, horizonY - r),
            size = Size(r * 2, r * 2),
        )
        // Five rays fanning out above the horizon.
        for (deg in listOf(180.0, 225.0, 270.0, 315.0, 360.0)) {
            val a = Math.toRadians(deg)
            val inner = r + w * 0.07f
            val outer = r + w * 0.16f
            drawLine(
                color = sun,
                start = Offset(cx + (inner * cos(a)).toFloat(), horizonY + (inner * sin(a)).toFloat()),
                end = Offset(cx + (outer * cos(a)).toFloat(), horizonY + (outer * sin(a)).toFloat()),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        drawLine(
            color = horizon,
            start = Offset(w * 0.12f, horizonY + stroke * 1.4f),
            end = Offset(w * 0.88f, horizonY + stroke * 1.4f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

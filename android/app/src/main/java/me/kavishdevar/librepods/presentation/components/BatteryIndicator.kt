/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.components


import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import me.kavishdevar.librepods.presentation.theme.OnestFontFamily
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** Which bud (or the case) the reading belongs to; rendered as a vector/letter badge. */
enum class BatterySide { NONE, LEFT, RIGHT, CASE }

@Composable
fun BatteryIndicator(
    batteryPercentage: Int,
    status: Int,
    side: BatterySide = BatterySide.NONE,
    previousCharging: Boolean = false,
) {
    val batteryTextColor = MiuixTheme.colorScheme.onSurface
    val batteryFillColor =
        if (batteryPercentage > 25) Color(0xFF35C759) else Color(0xFFFE373C)

    val initialScale = if (previousCharging) 1f else 0f
    val scaleAnim = remember { Animatable(initialScale) }
    val charging = status == BatteryStatus.CHARGING || status == BatteryStatus.OPTIMIZED_CHARGING
    val targetScale = if (charging) 1f else 0f

    LaunchedEffect(previousCharging, charging) {
        scaleAnim.animateTo(targetScale, animationSpec = tween(durationMillis = 250))
    }

    Column(
        modifier = Modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.padding(bottom = 4.dp), contentAlignment = Alignment.Center
        ) {
            val strokeWidthPx = with(LocalDensity.current) { 4.dp.toPx() }
            val gapFromCenterPx = with(LocalDensity.current) { 8.sp.toPx() }

            val trackColor = MiuixTheme.colorScheme.surfaceContainerHigh
            val optimizedLimit = 0.8f
            val progress = batteryPercentage / 100f

            Canvas(modifier = Modifier.size(34.dp)) {
                val startAngle = -90f
                val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                val inset = strokeWidthPx / 2
                Rect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = size.height - inset
                )
                val radius = size.minDimension / 2

                if (status == BatteryStatus.OPTIMIZED_CHARGING) {
                    drawArc(
                        color = trackColor,
                        startAngle = startAngle,
                        sweepAngle = 360f * optimizedLimit,
                        useCenter = false,
                        style = stroke
                    )

                    val sweep = 360f * min(progress, optimizedLimit)
                    drawArc(
                        color = batteryFillColor,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = stroke
                    )

                    // ---- PILL MARKER AT 80% ----
                    val angleDeg = startAngle + 360f * optimizedLimit
                    val angleRad = Math.toRadians(angleDeg.toDouble())

                    val arcRadius = radius - strokeWidthPx

                    val outerX = center.x + arcRadius * cos(angleRad).toFloat()
                    val outerY = center.y + arcRadius * sin(angleRad).toFloat()

                    val dirX = center.x - outerX
                    val dirY = center.y - outerY
                    val length = sqrt(dirX * dirX + dirY * dirY)

                    val normX = dirX / length
                    val normY = dirY / length

                    val startX = outerX - normX * strokeWidthPx / 2
                    val startY = outerY - normY * strokeWidthPx / 2

                    val endX = center.x - normX * gapFromCenterPx
                    val endY = center.y - normY * gapFromCenterPx

                    drawLine(
                        color = if (batteryPercentage >= 80) batteryFillColor else trackColor,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round
                    )
                } else {
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke
                    )

                    drawArc(
                        color = batteryFillColor,
                        startAngle = startAngle,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = stroke
                    )
                }
            }

            Icon(
                painter = painterResource(R.drawable.ic_charging_bolt),
                contentDescription = null,
                tint = batteryFillColor,
                modifier = Modifier
                    .scale(scaleAnim.value)
                    .size(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            when (side) {
                BatterySide.LEFT, BatterySide.RIGHT -> Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(14.dp)
                        .background(batteryTextColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    MiuixText(
                        text = if (side == BatterySide.LEFT) "L" else "R",
                        color = batteryTextColor,
                        style = TextStyle(
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center
                        ),
                    )
                }

                BatterySide.CASE -> Icon(
                    painter = painterResource(R.drawable.ic_battery_case),
                    contentDescription = null,
                    tint = batteryTextColor,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(14.dp)
                )

                BatterySide.NONE -> Unit
            }

            MiuixText(
                text = "$batteryPercentage%",
                color = batteryTextColor,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontFamily = OnestFontFamily,
                    textAlign = TextAlign.Center
                ),
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BatteryIndicatorPreview() {
    LibrePodsTheme(darkTheme = true) {
        BatteryIndicator(
            batteryPercentage = 50,
            status = BatteryStatus.OPTIMIZED_CHARGING,
            side = BatterySide.LEFT,
            previousCharging = false
        )
    }
}

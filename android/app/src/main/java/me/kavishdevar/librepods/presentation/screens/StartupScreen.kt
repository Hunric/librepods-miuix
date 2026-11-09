package me.kavishdevar.librepods.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator

@Composable
fun StartupScreen() {
    val intro = remember { Animatable(0f) }
    val breathing by rememberInfiniteTransition(label = "startupLogoBreathing").animateFloat(
        initialValue = 1f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "startupLogoScale",
    )
    LaunchedEffect(Unit) {
        intro.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        )
    }

    fun stagedProgress(start: Float, end: Float): Float =
        ((intro.value - start) / (end - start)).coerceIn(0f, 1f)

    val logoProgress = stagedProgress(0f, 0.58f)
    val titleProgress = stagedProgress(0.20f, 0.76f)
    val secondaryProgress = stagedProgress(0.42f, 0.96f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.startup_background))
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.92f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.38f),
                        radius = size.minDimension * 0.72f,
                    ),
                )
            }
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val logoSize = if (maxHeight < 400.dp) 112.dp else 160.dp
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.startup_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(logoSize)
                    .graphicsLayer {
                        alpha = logoProgress
                        scaleX = (0.88f + 0.12f * logoProgress) * breathing
                        scaleY = (0.88f + 0.12f * logoProgress) * breathing
                        translationY = (1f - logoProgress) * 14.dp.toPx()
                    },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorResource(R.color.startup_foreground),
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = titleProgress
                    translationY = (1f - titleProgress) * 10.dp.toPx()
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.startup_preparing),
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.startup_secondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = secondaryProgress
                    translationY = (1f - secondaryProgress) * 8.dp.toPx()
                },
            )
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        alpha = secondaryProgress
                        scaleX = 0.88f + 0.12f * secondaryProgress
                        scaleY = 0.88f + 0.12f * secondaryProgress
                    },
            )
        }
    }
}

@Preview(showBackground = true, locale = "zh-rCN")
@Preview(showBackground = true, widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
private fun StartupScreenPreview() {
    LibrePodsTheme {
        StartupScreen()
    }
}

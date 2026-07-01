package com.example.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt

@Composable
fun PullDownRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    canPullDown: () -> Boolean = { true },
    backgroundColor: Color = Color.Black,
    content: @Composable () -> Unit,
) {
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val currentIsRefreshing by rememberUpdatedState(isRefreshing)
    val currentCanPullDown by rememberUpdatedState(canPullDown)
    val density = LocalDensity.current
    val thresholdPx = with(density) { 88.dp.toPx() }
    val maxOffsetPx = with(density) { 140.dp.toPx() }

    var rawOffsetPx by remember { mutableFloatStateOf(0f) }
    val releaseOffset = remember { Animatable(0f) }

    LaunchedEffect(rawOffsetPx) {
        if (rawOffsetPx > 0f) releaseOffset.snapTo(0f)
    }

    // LocalView — надёжнее LocalHapticFeedback: прямой доступ к view.performHapticFeedback(),
    // которое Google рекомендует как основной способ (не требует VIBRATE permission,
    // имеет fallback-ы, уважает системные настройки).
    val view = LocalView.current
    val currentView by rememberUpdatedState(view)

    val nestedScrollConnection = remember(thresholdPx, maxOffsetPx) {
        object : NestedScrollConnection {
            // Обычная переменная — не Compose State, чтобы не вызывать рекомпозиции
            private var hapticFired = false

            private suspend fun finishPull() {
                val pulled = rawOffsetPx
                if (pulled <= 0f) return
                val shouldRefresh = pulled >= thresholdPx && !currentIsRefreshing
                hapticFired = false      // сбрасываем флаг при каждом отпускании
                rawOffsetPx = 0f
                releaseOffset.snapTo(pulled)
                releaseOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                )
                if (shouldRefresh) currentOnRefresh()
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || rawOffsetPx <= 0f) return Offset.Zero
                val consumed = minOf(rawOffsetPx, -available.y)
                rawOffsetPx -= consumed
                return Offset(0f, -consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    source != NestedScrollSource.UserInput ||
                    available.y <= 0f ||
                    currentIsRefreshing ||
                    !currentCanPullDown()
                ) return Offset.Zero

                val resistance = if (rawOffsetPx < thresholdPx) 0.5f else 0.24f
                val next = (rawOffsetPx + available.y * resistance).coerceAtMost(maxOffsetPx)
                val consumedY = (next - rawOffsetPx) / resistance
                rawOffsetPx = next

                // Вибрация прямо здесь — синхронный callback жеста, main thread.
                // view.performHapticFeedback() вместо Vibrator API:
                //   - не требует VIBRATE permission
                //   - не блокируется системой как "слишком громкий"
                //   - уважает настройки пользователя
                if (rawOffsetPx >= thresholdPx && !hapticFired) {
                    hapticFired = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // API 34+: константа специально для pull-to-refresh жестов
                        currentView.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                    } else {
                        currentView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                } else if (rawOffsetPx < thresholdPx) {
                    hapticFired = false
                }

                return Offset(0f, consumedY)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                finishPull()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                finishPull()
                return Velocity.Zero
            }
        }
    }

    // 0..1 — прогресс свайпа относительно порога срабатывания
    val pullProgress = (rawOffsetPx / thresholdPx).coerceIn(0f, 1f)
    // Эффективное смещение контента (во время свайпа или анимации отпускания)
    val effectiveOffset = if (rawOffsetPx > 0f) rawOffsetPx else releaseOffset.value

    // Масштаб индикатора: следует за прогрессом при свайпе, плавно уходит в 0 при отпускании
    val indicatorScale = remember { Animatable(0f) }
    val targetScale = if (rawOffsetPx > 0f && pullProgress > 0.5f && !isRefreshing)
        ((pullProgress - 0.5f) * 2f).coerceIn(0f, 1f)
    else
        0f

    LaunchedEffect(targetScale) {
        if (targetScale > 0f) {
            indicatorScale.snapTo(targetScale)
        } else if (indicatorScale.value > 0.01f) {
            indicatorScale.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(nestedScrollConnection)
            .background(backgroundColor)
    ) {
        // Контент сдвигается вниз при свайпе
        Box(
            modifier = Modifier.graphicsLayer {
                translationY = effectiveOffset
            }
        ) {
            content()
        }

        // Индикатор обновления
        if (indicatorScale.value > 0.01f) {
            val indicatorSizeDp = 44.dp
            val indicatorSizePx = with(density) { indicatorSizeDp.toPx() }

            // Индикатор у нижнего края открытого пространства
            val topY = (effectiveOffset - indicatorSizePx).roundToInt().coerceAtLeast(0)

            // Цвет круга появляется по прогрессу
            val circleAlpha = ((pullProgress - 0.5f) * 2f).coerceAtMost(1f)
            val circleBg = if (pullProgress >= 1f || isRefreshing)
                Color(0xFF2C2C2C)
            else
                Color(0xFF1A1A1A)

            // Иконка: вращается от 0° при 50% до 270° при 100%
            val iconRotation = (pullProgress - 0.5f) * 2f * 270f
            val iconTint = if (pullProgress >= 1f || isRefreshing) Color.White else Color(0xFF777777)

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, topY) }
                    .size(indicatorSizeDp)
                    .scale(indicatorScale.value)
                    .clip(CircleShape)
                    .background(circleBg.copy(alpha = circleAlpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Обновление",
                    tint = iconTint,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = iconRotation }
                )
            }
        }
    }
}
package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ── Палитра ──────────────────────────────────────────────────────────────────
private val DotBase      = Color(0xFF1A1A1A)
private val DotGold      = Color(0xFFFFD700)

// ── Константы сетки ──────────────────────────────────────────────────────────
private const val COLS  = 28
private const val ROWS  = 7
private const val TOTAL = COLS * ROWS

private val WAVE_CENTER_ROW = (ROWS - 1) / 2.0f   // = 3.0

private const val WAVE_SPEED_RAD_PER_SEC = 5f
private val MIN_FLOW_MS = ((2.0 * PI / WAVE_SPEED_RAD_PER_SEC) * 1000.0).toFloat()  // ~1257 мс

// ── Шрифт 4×5 ───────────────────────────────────────────────────────────────
private val FONT_4x5: Map<Char, List<List<Int>>> = mapOf(
    'H' to listOf(listOf(1,0,0,1), listOf(1,0,0,1), listOf(1,1,1,1), listOf(1,0,0,1), listOf(1,0,0,1)),
    'E' to listOf(listOf(1,1,1,1), listOf(1,0,0,0), listOf(1,1,1,0), listOf(1,0,0,0), listOf(1,1,1,1)),
    'R' to listOf(listOf(1,1,1,0), listOf(1,0,0,1), listOf(1,1,1,0), listOf(1,0,1,0), listOf(1,0,0,1)),
    'C' to listOf(listOf(0,1,1,0), listOf(1,0,0,1), listOf(1,0,0,0), listOf(1,0,0,1), listOf(0,1,1,0)),
)

private const val WORD       = "HERCH"
private const val TOP_MARGIN = 1

// ── Маска букв ───────────────────────────────────────────────────────────────
private val LETTER_MASK: BooleanArray = run {
    val mask = BooleanArray(TOTAL) { false }
    WORD.forEachIndexed { charIdx, ch ->
        val glyph = FONT_4x5[ch] ?: return@forEachIndexed
        val colStart = 2 + charIdx * 5
        glyph.forEachIndexed { glyphRow, rowBits ->
            val gridRow = TOP_MARGIN + glyphRow
            rowBits.forEachIndexed { glyphCol, bit ->
                if (bit == 1) {
                    val gridCol = colStart + glyphCol
                    if (gridCol < COLS && gridRow < ROWS) {
                        mask[gridRow * COLS + gridCol] = true
                    }
                }
            }
        }
    }
    mask
}

private data class ColPoint(val origRow: Int, val initIntensity: Float, val strand: Int)

private val COLUMN_POINTS: List<List<ColPoint>> = List(COLS) { col ->
    val pts = mutableListOf<ColPoint>()
    var topCount = 0; var bottomCount = 0
    for (row in 0 until ROWS) {
        if (LETTER_MASK[row * COLS + col]) {
            val strand = when {
                row < WAVE_CENTER_ROW -> -1
                row > WAVE_CENTER_ROW ->  1
                else                  -> if (col % 2 == 0) -1 else 1
            }
            pts.add(ColPoint(origRow = row, initIntensity = 1f, strand = strand))
            if (strand == 1) bottomCount++ else topCount++
        }
    }
    if (topCount    == 0) pts.add(ColPoint(origRow = WAVE_CENTER_ROW.toInt(), initIntensity = 0f, strand = -1))
    if (bottomCount == 0) pts.add(ColPoint(origRow = WAVE_CENTER_ROW.toInt(), initIntensity = 0f, strand =  1))
    pts
}

private enum class LogoEffect { SINE, RADAR }
private enum class WaveState { IDLE, INTRO, FLOW, OUTRO }

private const val TRANSITION_MS = 600f
private const val SWEEP_DURATION_MS = 1600

@Composable
fun HerchLogo(
    modifier:     Modifier = Modifier,
    isLoading:    Boolean  = false,
    isProcessing: Boolean  = false,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "herch_logo")
    val rawTime by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
        label         = "raw_time"
    )

    var waveState          by remember { mutableStateOf(WaveState.IDLE) }
    var transitionStartSec by remember { mutableFloatStateOf(0f) }
    var flowStartSec       by remember { mutableFloatStateOf(0f) }
    var pendingStop        by remember { mutableStateOf(false) }
    var currentEffect      by remember { mutableStateOf(LogoEffect.SINE) }

    // Состояния для управления бликом
    val sweepProgress = remember { Animatable(1f) } // 1f означает, что блик завершен и скрыт
    var clickTrigger by remember { mutableStateOf(0) }
    var lastHandledClick by remember { mutableStateOf(0) }

    val isAnimating = waveState != WaveState.IDLE

    // Единый контроллер анимации блика
    LaunchedEffect(isAnimating, clickTrigger) {
        if (isAnimating) {
            // Во время анимации логотипа запускаем бесконечный цикл блика
            while (true) {
                sweepProgress.snapTo(0f)
                sweepProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = SWEEP_DURATION_MS, easing = LinearEasing)
                )
            }
        } else {
            // В состоянии покоя (IDLE)
            if (clickTrigger > lastHandledClick) {
                // Если сработал клик: фиксируем его, мгновенно сбрасываем блик в 0 и проигрываем 1 раз
                lastHandledClick = clickTrigger
                sweepProgress.snapTo(0f)
                sweepProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = SWEEP_DURATION_MS, easing = LinearEasing)
                )
            } else {
                // Если мы просто перешли из активного состояния в IDLE,
                // даем текущему запущенному блику плавно дойти до конца (1f)
                if (sweepProgress.value < 1f) {
                    val remainingFraction = 1f - sweepProgress.value
                    val remainingDuration = (remainingFraction * SWEEP_DURATION_MS).toInt().coerceAtLeast(0)
                    sweepProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = remainingDuration, easing = LinearEasing)
                    )
                }
            }
        }
    }

    LaunchedEffect(isLoading, isProcessing) {
        val nowSec = rawTime * 60f
        val shouldAnimate = isLoading || isProcessing
        if (shouldAnimate) {
            currentEffect = if (isProcessing) LogoEffect.RADAR else LogoEffect.SINE
            pendingStop = false
            if (waveState == WaveState.IDLE || waveState == WaveState.OUTRO) {
                waveState          = WaveState.INTRO
                transitionStartSec = nowSec
            }
        } else {
            pendingStop = true
        }
    }

    LaunchedEffect(rawTime) {
        val nowSec    = rawTime * 60f
        val elapsedMs = (nowSec - transitionStartSec) * 1000f

        when (waveState) {
            WaveState.INTRO -> {
                if (elapsedMs >= TRANSITION_MS) {
                    waveState    = WaveState.FLOW
                    flowStartSec = nowSec
                }
            }
            WaveState.FLOW -> {
                if (pendingStop) {
                    val flowElapsedMs = (nowSec - flowStartSec) * 1000f
                    if (flowElapsedMs >= MIN_FLOW_MS) {
                        pendingStop        = false
                        waveState          = WaveState.OUTRO
                        transitionStartSec = nowSec
                    }
                }
            }
            WaveState.OUTRO -> {
                if (elapsedMs >= TRANSITION_MS) {
                    waveState = WaveState.IDLE
                }
            }
            WaveState.IDLE -> Unit
        }
    }

    Canvas(
        modifier = modifier
            .aspectRatio(COLS.toFloat() / ROWS.toFloat())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Клик разрешен только когда логотип не находится в режиме системной анимации
                if (!isAnimating) {
                    clickTrigger++
                }
            }
    ) {
        val cellW     = size.width  / COLS.toFloat()
        val cellH     = size.height / ROWS.toFloat()
        val dotRadius = min(cellW, cellH) * 0.48f

        val timeSec = rawTime * 60f

        val elapsedMs = (timeSec - transitionStartSec) * 1000f
        val lerpTable = FloatArray(COLS)

        fun smoothstep(p: Float): Float = p * p * (3f - 2f * p)

        when (waveState) {
            WaveState.IDLE  -> lerpTable.fill(0f)
            WaveState.FLOW  -> lerpTable.fill(1f)
            WaveState.INTRO -> {
                val p = smoothstep((elapsedMs / TRANSITION_MS).coerceIn(0f, 1f))
                for (col in 0 until COLS) {
                    lerpTable[col] = min(max((p * 1.35f - col.toFloat() / (COLS - 1)) / 0.35f, 0f), 1f)
                }
            }
            WaveState.OUTRO -> {
                val p = smoothstep((elapsedMs / TRANSITION_MS).coerceIn(0f, 1f))
                for (col in 0 until COLS) {
                    lerpTable[col] = 1f - min(max((p * 1.35f - col.toFloat() / (COLS - 1)) / 0.35f, 0f), 1f)
                }
            }
        }

        val wp = timeSec * WAVE_SPEED_RAD_PER_SEC
        val gridIntensities = FloatArray(TOTAL) { 0f }

        for (col in 0 until COLS) {
            val pts        = COLUMN_POINTS[col]
            val lerpFactor = lerpTable[col]
            val normX      = (col - (COLS - 1) / 2f) / ((COLS - 1) / 2f)
            val envelope   = 1f - normX * normX

            for (pt in pts) {
                if (lerpFactor < 0.001f) continue

                val targetY: Float
                val targetThickness: Float
                val targetIntensity: Float
                when (currentEffect) {
                    LogoEffect.RADAR -> {
                        val headPos    = (sin(wp * 0.8f) + 1f) / 2f * (COLS - 1)
                        val distToHead = abs(col - headPos)
                        targetY        = WAVE_CENTER_ROW
                        if (distToHead < 4f) {
                            val imap      = 1f - distToHead / 4f
                            targetThickness = 0.5f + 2.0f * imap
                            targetIntensity = 0.2f + 0.8f * imap
                        } else {
                            targetThickness = 0.5f
                            targetIntensity = 0.15f
                        }
                    }
                    LogoEffect.SINE -> {
                        targetY         = WAVE_CENTER_ROW + sin(col * 0.27f - wp) * 2.2f
                        targetThickness = 0.55f + 1.45f * envelope
                        targetIntensity = 1f
                    }
                }

                val currentThickness = 0.5f + (targetThickness - 0.5f) * lerpFactor
                val yInterp          = pt.origRow + (targetY - pt.origRow) * lerpFactor
                val finalIntensity   = pt.initIntensity + (targetIntensity - pt.initIntensity) * lerpFactor

                if (lerpFactor < 1f && pt.initIntensity > 0f) {
                    val origIdx = pt.origRow * COLS + col
                    gridIntensities[origIdx] = max(gridIntensities[origIdx], (1f - lerpFactor) * pt.initIntensity)
                }

                val startRow = max(0, floor(yInterp - currentThickness).toInt())
                val endRow   = min(ROWS - 1, ceil(yInterp + currentThickness).toInt())
                for (r in startRow..endRow) {
                    val dist = abs(r - yInterp)
                    if (dist <= currentThickness) {
                        val weight = 1f - dist / currentThickness
                        val idx    = r * COLS + col
                        gridIntensities[idx] = max(gridIntensities[idx], weight * finalIntensity * lerpFactor)
                    }
                }
            }
        }

        // ── Расчет параметров блика ──────────────────────────────────────────
        val sweepWidth = 9f
        val showSweep = sweepProgress.value < 1f
        val sweepCenter = sweepProgress.value * (COLS + sweepWidth * 2f + ROWS * 0.6f) - (sweepWidth + ROWS * 0.3f)

        // ── Рендер ───────────────────────────────────────────────────────────
        for (i in 0 until TOTAL) {
            val col        = i % COLS
            val row        = i / COLS
            if ((col == 0 || col == COLS - 1) && (row == 0 || row == ROWS - 1)) continue
            val centerX    = cellW * col + cellW / 2f
            val centerY    = cellH * row + cellH / 2f
            val isLetter   = LETTER_MASK[i]
            val lerpFactor = lerpTable[col]

            if (lerpFactor < 0.01f) {
                val color = if (isLetter) DotGold else DotBase
                drawCircle(color = color, radius = dotRadius, center = Offset(centerX, centerY))
                
                if (isLetter && showSweep) {
                    val distToSweep = abs((col + row * 0.6f) - sweepCenter)
                    if (distToSweep < sweepWidth) {
                        val alpha = ((1f - distToSweep / sweepWidth) * 0.85f).coerceIn(0f, 1f)
                        drawCircle(
                            color  = Color.White.copy(alpha = alpha),
                            radius = dotRadius,
                            center = Offset(centerX, centerY)
                        )
                    }
                }
            } else {
                val brightness = gridIntensities[i]
                if (brightness > 0.25f) {
                    drawCircle(color = DotGold, radius = dotRadius, center = Offset(centerX, centerY))
                    
                    if (showSweep) {
                        val distToSweep = abs((col + row * 0.6f) - sweepCenter)
                        if (distToSweep < sweepWidth) {
                            val alpha = ((1f - distToSweep / sweepWidth) * 0.85f * brightness).coerceIn(0f, 1f)
                            drawCircle(
                                color  = Color.White.copy(alpha = alpha),
                                radius = dotRadius,
                                center = Offset(centerX, centerY)
                            )
                        }
                    }
                } else {
                    drawCircle(color = DotBase, radius = dotRadius, center = Offset(centerX, centerY))
                }
            }
        }
    }
}
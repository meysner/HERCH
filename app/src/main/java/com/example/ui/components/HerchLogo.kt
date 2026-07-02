package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ── Палитра ──────────────────────────────────────────────────────────────────
private val DotBase      = Color(0xFF1A1A1A)
private val DotGold      = Color(0xFFFFD700)

// Цвета по эффектам (см. html-прототип: EFFECT_COLORS)
private val ColorDefault = DotGold                 // sine / dna (анализ) / radar (поиск)
private val ColorDna2    = Color(0xFFB24BF3)        // фиолетовый — ДНК (реверс, ожидание ответа)
private val ColorToolUse = Color(0xFF3CE28C)        // мятно-изумрудный — Tool Use (обработка)
private val ColorError   = Color(0xFFFF3B30)        // классический красный — офлайн / ошибка сети

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
    'O' to listOf(listOf(0,1,1,0), listOf(1,0,0,1), listOf(1,0,0,1), listOf(1,0,0,1), listOf(0,1,1,0)),
)

private const val WORD       = "HERCH"
private const val WORD_ERROR = "ERROR"
private const val TOP_MARGIN = 1

// ── Маска букв (аналог buildLetterMask() из html-прототипа) ───────────────────
private fun buildLetterMask(word: String): BooleanArray {
    val mask = BooleanArray(TOTAL) { false }
    // Обе используемые сейчас надписи (HERCH / ERROR) — по 5 символов шириной
    // 4 + 1 колонка-разделитель, поэтому центрирование через totalWidth даёт
    // тот же стартовый столбец (=2), что и раньше было захардкожено для HERCH.
    val totalWidth = word.length * 5 - 1
    var colStart = max(0, (COLS - totalWidth) / 2)
    word.forEach { ch ->
        val glyph = FONT_4x5[ch]
        if (glyph != null) {
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
        colStart += 5
    }
    return mask
}

private data class ColPoint(val origRow: Int, val initIntensity: Float, val strand: Int)

private fun buildColumnPoints(mask: BooleanArray): List<List<ColPoint>> = List(COLS) { col ->
    val pts = mutableListOf<ColPoint>()
    var topCount = 0; var bottomCount = 0
    for (row in 0 until ROWS) {
        if (mask[row * COLS + col]) {
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

private val LETTER_MASK: BooleanArray = buildLetterMask(WORD)
private val COLUMN_POINTS: List<List<ColPoint>> = buildColumnPoints(LETTER_MASK)

// ── Маска слова ERROR — используется эффектом ERROR_TEXT (офлайн/сбой сети) ───
private val ERROR_MASK: BooleanArray = buildLetterMask(WORD_ERROR)
private val ERROR_COLUMN_POINTS: List<List<ColPoint>> = buildColumnPoints(ERROR_MASK)

/**
 * Публичное состояние индикатора HERCH — управляется снаружи (ChatScreen/ChatBottomBar)
 * в зависимости от фазы ответа ассистента:
 *
 *  - IDLE       — покой, ничего не происходит (анимация не идёт).
 *  - LOADING    — общая фоновая загрузка вне контекста чата (волна) — например, экран меню.
 *  - WAITING    — сообщение отправлено, ответ ещё не начал приходить → ДНК (реверс).
 *  - REASONING  — модель рассуждает (thinking-блок) → РАДАР (поиск).
 *  - TOOL_USE   — вызов/выполнение инструмента → TOOL USE (обработка).
 *  - WRITING    — модель пишет текст прямо в чат → ДНК (анализ).
 *  - OFFLINE    — нет соединения / повторные попытки подключения → красная волна,
 *                 которая периодически расформировывается в надпись ERROR.
 */
enum class HerchLogoState { IDLE, LOADING, WAITING, REASONING, TOOL_USE, WRITING, OFFLINE }

private enum class LogoEffect { SINE, DNA, DNA2, RADAR, TOOLUSE, ERROR_TEXT }
private enum class WaveState { IDLE, INTRO, FLOW, OUTRO }

private const val TRANSITION_MS = 900f
private const val SWEEP_DURATION_MS = 1600

// Длительность zipper-перехода между эффектами внутри FLOW (см. html: TRANS_DURATION = 800)
private const val TRANS_DURATION_MS = 800f

// ── Тайминги ERROR_TEXT (см. html: ER_WAVE_HOLD / ER_MORPH_DURATION) ─────────
private const val ER_WAVE_HOLD_MS      = 900f  // сколько держим чистую красную волну перед морфом в текст
private const val ER_MORPH_DURATION_MS = 900f  // длительность "сборки" надписи ERROR слева направо

private fun stateToEffect(state: HerchLogoState): LogoEffect = when (state) {
    HerchLogoState.LOADING   -> LogoEffect.SINE
    HerchLogoState.WAITING   -> LogoEffect.DNA2
    HerchLogoState.REASONING -> LogoEffect.RADAR
    HerchLogoState.TOOL_USE  -> LogoEffect.TOOLUSE
    HerchLogoState.WRITING   -> LogoEffect.DNA
    HerchLogoState.OFFLINE   -> LogoEffect.ERROR_TEXT
    HerchLogoState.IDLE      -> LogoEffect.SINE
}

private fun effectColor(effect: LogoEffect): Color = when (effect) {
    LogoEffect.DNA2       -> ColorDna2
    LogoEffect.TOOLUSE    -> ColorToolUse
    LogoEffect.ERROR_TEXT -> ColorError
    else                  -> ColorDefault
}

private data class Target(val y: Float, val thickness: Float, val intensity: Float)

// Аналог computeTarget() из html-прототипа: считает целевые y/толщину/яркость точки
// для текущего эффекта. twistScale всегда 1 — кросс-эффектных переходов (uncoil/depth/…)
// здесь не делаем, эффект просто мгновенно подменяется внутри FLOW (как раньше было
// с sine↔radar), а цвет плавно перетекает через animateColorAsState.
private fun computeTarget(
    effect: LogoEffect,
    col: Int,
    pt: ColPoint,
    envelope: Float,
    wp: Float,
    timeMs: Float,
    errorMorphT: Float = 0f,
): Target = when (effect) {
    LogoEffect.SINE -> {
        val y = WAVE_CENTER_ROW + sin(col * 0.27f - wp) * 2.2f
        val thickness = 0.55f + 1.45f * envelope
        Target(y, thickness, 1f)
    }
    LogoEffect.DNA -> {
        val tPhase = col * 0.29f - wp * 1.3f
        val depth  = pt.strand * cos(tPhase)
        val y      = WAVE_CENTER_ROW + pt.strand * sin(tPhase) * 2.4f
        val thickness = 0.5f + ((0.4f + (depth + 1f) * 0.7f) - 0.5f) * envelope
        val intensity = 0.15f + (depth + 1f) * 0.425f
        Target(y, thickness, intensity)
    }
    LogoEffect.DNA2 -> {
        // Тот же винт ДНК, но со встречным вращением фазы — визуально «раскручивается
        // назад», как будто нить ещё сматывается в ожидании начала ответа.
        val tPhase = col * 0.29f + wp * 1.3f
        val depth  = pt.strand * cos(tPhase)
        val y      = WAVE_CENTER_ROW + pt.strand * sin(tPhase) * 2.4f
        val thickness = 0.5f + ((0.4f + (depth + 1f) * 0.7f) - 0.5f) * envelope
        val intensity = 0.15f + (depth + 1f) * 0.425f
        Target(y, thickness, intensity)
    }
    LogoEffect.RADAR -> {
        val headPos    = (sin(wp * 0.8f) + 1f) / 2f * (COLS - 1)
        val distToHead = abs(col - headPos)
        if (distToHead < 4f) {
            val imap = 1f - distToHead / 4f
            Target(WAVE_CENTER_ROW, 0.5f + 2.0f * imap, 0.2f + 0.8f * imap)
        } else {
            Target(WAVE_CENTER_ROW, 0.5f, 0.15f)
        }
    }
    LogoEffect.TOOLUSE -> {
        // Из центра расходятся кольцевые импульсы — инструмент «работает».
        val ringPeriodMs = 1100f
        val ringCount    = 3
        val maxRadius    = COLS / 2f + 3f
        var glow = 0f
        for (k in 0 until ringCount) {
            val localTime      = (timeMs + k * (ringPeriodMs / ringCount)) % ringPeriodMs
            val radius         = (localTime / ringPeriodMs) * maxRadius
            val distFromCenter = abs(col - COLS / 2f)
            val distToRing     = abs(distFromCenter - radius)
            val fade           = 1f - (radius / maxRadius)
            val ring           = exp(-distToRing * distToRing * 0.55f) * fade
            if (ring > glow) glow = ring
        }
        val y = WAVE_CENTER_ROW + pt.strand * 0.35f
        val thickness = 0.45f + (1.6f * glow + 0.15f) * envelope
        val intensity = 0.18f + 0.82f * glow
        Target(y, thickness, intensity)
    }
    LogoEffect.ERROR_TEXT -> {
        // Плавный бленд между "чистой красной волной" (errorMorphT=0) и
        // "погашено, место занято словом ERROR" (errorMorphT=1). Раньше это был
        // жёсткий boolean-переключатель — отсюда и резкий скачок при возврате
        // из фазы текста обратно в волну. Теперь это одна интерполяция, значит
        // и назад по errorMorphT она едет так же плавно.
        val waveY         = WAVE_CENTER_ROW + sin(col * 0.27f - wp) * 2.2f
        val waveThickness = 0.55f + 1.45f * envelope
        val y         = waveY + (WAVE_CENTER_ROW - waveY) * errorMorphT
        val thickness = waveThickness + (0.4f - waveThickness) * errorMorphT
        val intensity = 1f - errorMorphT
        Target(y, thickness, intensity)
    }
}

private fun clamp01(v: Float): Float = min(max(v, 0f), 1f)
private fun smoothstepClamped(t: Float): Float {
    val c = clamp01(t)
    return c * c * (3f - 2f * c)
}

// Аналог computeLocalT(mode, transT, col, p) из html-прототипа — но только для режима
// 'split' (Zipper): верхняя и нижняя пряди буквы переключаются со сдвигом по времени,
// как будто расходятся/сходятся застёжкой.
private fun computeLocalT(transT: Float, pt: ColPoint): Float {
    val stagger = if (pt.strand == 1) 0f else 0.28f
    return smoothstepClamped(transT * 1.4f - stagger)
}

// Аналог renderErrorMorph() из html-прототипа: пока эффект ERROR_TEXT в фазе
// "text", волна слева направо "сшивается" в надпись ERROR (weave-раскрытие),
// результат накладывается поверх основной сетки через max().
// Аналог renderErrorMorph() из html-прототипа, но управляется напрямую значением
// errorMorph (Animatable, 0..1) вместо ручного расчёта elapsed/duration — поэтому
// один и тот же код одинаково плавно "сшивает" слово ERROR слева направо (morphT
// растёт к 1) и "расшивает" его обратно в волну (morphT падает к 0).
private fun renderErrorMorph(
    morphT: Float,
    wp: Float,
    grid: FloatArray,
) {
    val p = morphT

    for (col in 0 until COLS) {
        val colFrac    = col / (COLS - 1).toFloat()
        val lerpFactor = 1f - clamp01((p * 1.35f - colFrac) / 0.35f)
        val normX      = (col - (COLS - 1) / 2f) / ((COLS - 1) / 2f)
        val envelope   = 1f - normX * normX

        val targetY         = WAVE_CENTER_ROW + sin(col * 0.27f - wp) * 2.2f
        val targetThickness = 0.55f + 1.45f * envelope
        val targetIntensity = 1f

        for (pt in ERROR_COLUMN_POINTS[col]) {
            val currentThickness = 0.5f + (targetThickness - 0.5f) * lerpFactor
            val yInterp        = pt.origRow + (targetY - pt.origRow) * lerpFactor
            val finalIntensity = pt.initIntensity + (targetIntensity - pt.initIntensity) * lerpFactor

            if (lerpFactor == 0f && pt.initIntensity > 0f) {
                val idx = pt.origRow * COLS + col
                grid[idx] = max(grid[idx], 1f)
            } else if (lerpFactor > 0f) {
                val startRow = max(0, floor(yInterp - currentThickness).toInt())
                val endRow   = min(ROWS - 1, ceil(yInterp + currentThickness).toInt())
                for (r in startRow..endRow) {
                    val dist = abs(r - yInterp)
                    if (dist <= currentThickness) {
                        val weight = 1f - dist / currentThickness
                        val idx    = r * COLS + col
                        grid[idx] = max(grid[idx], weight * finalIntensity)
                    }
                }
            }
        }
    }
}

@Composable
fun HerchLogo(
    modifier: Modifier = Modifier,
    state: HerchLogoState = HerchLogoState.IDLE,
    // Инкрементируется снаружи при каждой новой попытке переподключения (например,
    // раз в 3 секунды из поллинга SessionViewModel). Пока state == OFFLINE, любое
    // изменение retryTick заново запускает цикл "красная волна → ERROR".
    retryTick: Int = 0,
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

    // ── Эффект ERROR_TEXT: непрерывное значение 0..1, где 0 = чистая красная
    // волна, 1 = полностью сформированная надпись ERROR. Animatable даёт
    // плавную анимацию в обе стороны (в html-прототипе была только в одну).
    val errorMorph = remember { Animatable(0f) }

    // ── Zipper-переход между эффектами (аналог isTransitioning/fromEffect/toEffect из html) ──
    var isTransitioning by remember { mutableStateOf(false) }
    var fromEffect       by remember { mutableStateOf<LogoEffect?>(null) }
    var toEffect         by remember { mutableStateOf<LogoEffect?>(null) }
    var transStartSec    by remember { mutableFloatStateOf(0f) }

    // Состояния для управления бликом
    val sweepProgress = remember { Animatable(1f) } // 1f означает, что блик завершен и скрыт
    var clickTrigger by remember { mutableStateOf(0) }
    var lastHandledClick by remember { mutableStateOf(0) }

    val isAnimating = waveState != WaveState.IDLE

    // Плавный переход цвета точек между эффектами (gold ↔ фиолетовый ↔ мятный)
    val animatedColor by animateColorAsState(
        targetValue   = effectColor(currentEffect),
        animationSpec = tween(durationMillis = 500),
        label         = "herch_logo_color",
    )

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

    LaunchedEffect(state) {
        val nowSec = rawTime * 60f
        val newEffect = stateToEffect(state)
        val shouldAnimate = state != HerchLogoState.IDLE
        if (shouldAnimate) {
            pendingStop = false
            when (waveState) {
                WaveState.IDLE, WaveState.OUTRO -> {
                    // Старт "с нуля" — как раньше, через INTRO, без zipper-перехода.
                    currentEffect       = newEffect
                    waveState           = WaveState.INTRO
                    transitionStartSec  = nowSec
                    isTransitioning     = false
                    fromEffect          = null
                    toEffect            = null
                }
                WaveState.FLOW -> {
                    // Уже в FLOW и эффект реально меняется — запускаем zipper-переход
                    // (аналог html: fromEffect = currentEffect; toEffect = effect; isTransitioning = true).
                    if (newEffect != currentEffect) {
                        fromEffect      = currentEffect
                        toEffect        = newEffect
                        currentEffect   = newEffect
                        isTransitioning = true
                        transStartSec   = nowSec
                    }
                }
                WaveState.INTRO -> {
                    // Ещё не доехали до FLOW — просто меняем целевой эффект без zipper'а.
                    currentEffect = newEffect
                }
            }
        } else {
            // Уходим в состояние покоя (IDLE). Если сейчас волна другого цвета/эффекта
            // (например, красный ERROR при восстановлении связи) — сначала плавно
            // зиппер-переходим к целевому SINE/золото, а уже потом даём OUTRO
            // доиграть затухание. Раньше currentEffect тут не менялся вообще, из-за
            // чего статичная надпись HERCH в покое могла остаться в "чужом" цвете
            // (например красной после офлайна) вместо золотого.
            if (waveState == WaveState.FLOW && newEffect != currentEffect) {
                fromEffect      = currentEffect
                toEffect        = newEffect
                currentEffect   = newEffect
                isTransitioning = true
                transStartSec   = nowSec
            } else if (waveState == WaveState.INTRO) {
                currentEffect = newEffect
            }
            pendingStop = true
        }
    }

    // ── Контроллер errorMorph: пока текущее состояние — OFFLINE, крутим цикл
    // "волна (hold) → сборка в ERROR (морф) → держим текст", пока не придёт
    // новый retryTick или state не сменится. При отмене корутины (новый ключ)
    // Compose сам прерывает animateTo/delay — так что переключение всегда
    // получается анимированным, а не мгновенным.
    LaunchedEffect(state, retryTick) {
        val newEffect = stateToEffect(state)
        if (newEffect == LogoEffect.ERROR_TEXT) {
            if (errorMorph.value != 0f) {
                errorMorph.animateTo(0f, tween(ER_MORPH_DURATION_MS.toInt(), easing = FastOutSlowInEasing))
            }
            delay(ER_WAVE_HOLD_MS.toLong())
            errorMorph.animateTo(1f, tween(ER_MORPH_DURATION_MS.toInt(), easing = FastOutSlowInEasing))
        } else if (errorMorph.value != 0f) {
            errorMorph.animateTo(0f, tween(ER_MORPH_DURATION_MS.toInt(), easing = FastOutSlowInEasing))
        }
    }

    LaunchedEffect(rawTime) {
        val nowSec    = rawTime * 60f
        val elapsedMs = (nowSec - transitionStartSec) * 1000f

        if (isTransitioning) {
            val transElapsedMs = (nowSec - transStartSec) * 1000f
            if (transElapsedMs >= TRANS_DURATION_MS) {
                isTransitioning = false
                fromEffect      = null
            }
        }

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
                if (!isAnimating) clickTrigger++
            }
    ) {
        val cellW     = size.width  / COLS.toFloat()
        val cellH     = size.height / ROWS.toFloat()
        val dotRadius = min(cellW, cellH) * 0.48f

        val timeSec = rawTime * 60f
        val timeMs  = timeSec * 1000f

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
        val errorMorphT = errorMorph.value
        val gridIntensities = FloatArray(TOTAL) { 0f }

        for (col in 0 until COLS) {
            val pts        = COLUMN_POINTS[col]
            val lerpFactor = lerpTable[col]
            val normX      = (col - (COLS - 1) / 2f) / ((COLS - 1) / 2f)
            val envelope   = 1f - normX * normX

            for (pt in pts) {
                if (lerpFactor == 0f) {
                    if (pt.initIntensity > 0f) {
                        val origIdx = pt.origRow * COLS + col
                        gridIntensities[origIdx] = max(gridIntensities[origIdx], 1f)
                    }
                } else {
                    val target: Target
                    if (isTransitioning && fromEffect != null && toEffect != null) {
                        val transElapsedMs = (timeSec - transStartSec) * 1000f
                        val transT = (transElapsedMs / TRANS_DURATION_MS).coerceIn(0f, 1f)

                        val fromT = computeTarget(fromEffect!!, col, pt, envelope, wp, timeMs, errorMorphT)
                        val toT   = computeTarget(toEffect!!, col, pt, envelope, wp, timeMs, errorMorphT)
                        val localT = computeLocalT(transT, pt)

                        target = Target(
                            y         = fromT.y * (1f - localT) + toT.y * localT,
                            thickness = fromT.thickness * (1f - localT) + toT.thickness * localT,
                            intensity = fromT.intensity * (1f - localT) + toT.intensity * localT,
                        )
                    } else {
                        target = computeTarget(currentEffect, col, pt, envelope, wp, timeMs, errorMorphT)
                    }

                    val currentThickness = 0.5f + (target.thickness - 0.5f) * lerpFactor
                    val yInterp          = pt.origRow + (target.y - pt.origRow) * lerpFactor
                    val finalIntensity   = pt.initIntensity + (target.intensity - pt.initIntensity) * lerpFactor

                    val startRow = max(0, floor(yInterp - currentThickness).toInt())
                    val endRow   = min(ROWS - 1, ceil(yInterp + currentThickness).toInt())

                    for (r in startRow..endRow) {
                        val dist = abs(r - yInterp)
                        if (dist <= currentThickness) {
                            val weight = 1f - dist / currentThickness
                            val idx    = r * COLS + col
                            gridIntensities[idx] = max(gridIntensities[idx], weight * finalIntensity)
                        }
                    }
                }
            }
        }

        if (currentEffect == LogoEffect.ERROR_TEXT && waveState == WaveState.FLOW && !isTransitioning && errorMorphT > 0f) {
            renderErrorMorph(errorMorphT, wp, gridIntensities)
        }

        val sweepWidth  = 9f
        val showSweep   = sweepProgress.value < 1f
        val sweepCenter = sweepProgress.value * (COLS + sweepWidth * 2f + ROWS * 0.6f) - (sweepWidth + ROWS * 0.3f)

        for (i in 0 until TOTAL) {
            val col = i % COLS
            val row = i / COLS
            if ((col == 0 || col == COLS - 1) && (row == 0 || row == ROWS - 1)) continue

            val centerX = cellW * col + cellW / 2f
            val centerY = cellH * row + cellH / 2f

            val finalBrightness = gridIntensities[i]

            val isLedOn = finalBrightness > 0.35f

            if (isLedOn) {
                drawCircle(color = animatedColor, radius = dotRadius, center = Offset(centerX, centerY))

                if (showSweep) {
                    val distToSweep = abs((col + row * 0.6f) - sweepCenter)
                    if (distToSweep < sweepWidth) {
                        val alpha = ((1f - distToSweep / sweepWidth) * 0.85f * finalBrightness).coerceIn(0f, 1f)
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
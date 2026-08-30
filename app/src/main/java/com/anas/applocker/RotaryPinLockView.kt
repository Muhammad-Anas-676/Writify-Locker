package com.anas.applocker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Rotary dial PIN entry, styled after the reference "Rotary Lock Screen" HTML mock:
 * a circular dial with 11 buttons (1-9, 0, delete) that can either be tapped directly
 * or dragged/rotated to the top stopper, plus a center hub showing masked PIN dots.
 *
 * Unlike the reference (which used a hard-coded 6-digit PIN with an instant auto-submit
 * once length == 6), Writify's real/fake PINs can be any length. So instead of a fixed
 * length, this view auto-verifies after the user pauses for a moment once at least
 * [MIN_DIGITS_TO_VERIFY] digits have been entered - it "settles" and checks, the same way
 * the reference checks the instant PIN_LENGTH is reached.
 */
class RotaryPinLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---------- Palette (matches the reference HTML's CSS variables) ----------
    private val colorSurfaceBtn = Color.parseColor("#121826")
    private val colorBorder = Color.parseColor("#1C2436")
    private val colorBorderBlue = Color.parseColor("#1D4ED8")
    private val colorBlue = Color.parseColor("#2563EB")
    private val colorBlueGlow = Color.parseColor("#3B82F6")
    private val colorDanger = Color.parseColor("#EF4444")
    private val colorSuccess = Color.parseColor("#10B981")
    private val colorHubBg = Color.parseColor("#0C1019")
    private val colorText = Color.parseColor("#F8FAFC")
    private val colorMuted = Color.parseColor("#475569")
    private val colorDeleteText = Color.parseColor("#94A3B8")

    private data class DialButton(val value: String, val baseDeg: Float, var bx: Float = 0f, var by: Float = 0f)

    private val digitOrder = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "\u2715")
    private val buttons: List<DialButton> = digitOrder.mapIndexed { i, v ->
        val rad = (i / digitOrder.size.toFloat()) * (2f * PI.toFloat()) - (PI.toFloat() / 2f)
        DialButton(v, Math.toDegrees(rad.toDouble()).toFloat())
    }

    private var cx = 0f
    private var cy = 0f
    private var dialRadius = 0f
    private var buttonRadiusPx = 0f
    private var hitRadiusPx = 0f
    private var hubRadiusPx = 0f

    // ---------- Gesture / rotation state ----------
    private var currentRotation = 0f
    private var isPointerDown = false
    private var isAutoSpinning = false
    private var hasRotated = false
    private var startPointerAngle = 0f
    private var lastSoundAngle = 0f
    private var activeButton: DialButton? = null
    private var stopperLocked = false

    // ---------- Visual state ----------
    private var trackState = STATE_NORMAL

    // ---------- Entered PIN state ----------
    private val entered = StringBuilder()
    private var revealedIndex = -1
    private var maxDigits = 8
    private val handler = Handler(Looper.getMainLooper())
    private var maskRunnable: Runnable? = null
    private var settleRunnable: Runnable? = null

    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    /** Return true if [pin] is the correct one. Called once entry "settles". */
    var onVerify: ((String) -> Boolean)? = null
    var onSuccess: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val btnFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val btnStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val numTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stopperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Pre-allocated once; only its bounds are mutated per-frame in onSizeChanged/onDraw -
    // zero object churn during onDraw itself (was previously `RectF(...)`'d on every frame).
    private val stopperRect = RectF()

    /** When false, all haptic feedback + tone-generator sounds are skipped (Settings toggle). */
    var hapticsSoundEnabled: Boolean = true

    init {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_SYSTEM, 45)
        } catch (e: Exception) {
            toneGen = null
        }
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setMaxDigits(n: Int) {
        maxDigits = n.coerceIn(4, 12)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        val size = min(w, h).toFloat()
        dialRadius = size * 0.38f
        buttonRadiusPx = size * 0.065f
        hitRadiusPx = size * 0.09f
        hubRadiusPx = size * 0.19f

        buttons.forEach { b ->
            val rad = Math.toRadians(b.baseDeg.toDouble())
            b.bx = cx + dialRadius * cos(rad).toFloat()
            b.by = cy + dialRadius * sin(rad).toFloat()
        }
    }

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cx == 0f) return

        val (trackColor, hubBorderColor) = when (trackState) {
            STATE_ERROR -> colorDanger to colorDanger
            STATE_SUCCESS -> colorSuccess to colorSuccess
            else -> colorBorderBlue to colorBorderBlue
        }

        // Outer track ring
        trackPaint.color = trackColor
        trackPaint.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, dialRadius + buttonRadiusPx + dp(14f), trackPaint)

        // Stopper (small pill at top, fixed - does not rotate)
        stopperPaint.color = if (stopperLocked) colorBlueGlow else colorBorder
        val stopperTop = cy - (dialRadius + buttonRadiusPx + dp(14f)) - dp(2f)
        stopperRect.set(cx - dp(6f), stopperTop, cx + dp(6f), stopperTop + dp(6f))
        canvas.drawRoundRect(stopperRect, dp(3f), dp(3f), stopperPaint)

        // Rotor: buttons drawn rotated around center
        canvas.save()
        canvas.rotate(currentRotation, cx, cy)
        for (b in buttons) {
            val isActive = b === activeButton
            btnFillPaint.color = if (isActive) colorBlue else colorSurfaceBtn
            canvas.drawCircle(b.bx, b.by, buttonRadiusPx, btnFillPaint)
            btnStrokePaint.color = if (isActive) colorBlueGlow else colorBorder
            btnStrokePaint.strokeWidth = dp(1.5f)
            canvas.drawCircle(b.bx, b.by, buttonRadiusPx, btnStrokePaint)

            btnTextPaint.color = when {
                isActive -> Color.WHITE
                b.value == "\u2715" -> colorDeleteText
                else -> colorText
            }
            btnTextPaint.textSize = buttonRadiusPx * (if (b.value == "\u2715") 0.62f else 0.72f)
            val metrics = btnTextPaint.fontMetrics
            val textY = b.by - (metrics.ascent + metrics.descent) / 2f
            // Counter-rotate the glyph itself so digits always stay upright.
            canvas.save()
            canvas.rotate(-currentRotation, b.bx, b.by)
            canvas.drawText(b.value, b.bx, textY, btnTextPaint)
            canvas.restore()
        }
        canvas.restore()

        // Center hub
        hubPaint.color = colorHubBg
        canvas.drawCircle(cx, cy, hubRadiusPx, hubPaint)
        hubStrokePaint.color = hubBorderColor
        hubStrokePaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(cx, cy, hubRadiusPx, hubStrokePaint)

        drawPinSlots(canvas)
    }

    private fun drawPinSlots(canvas: Canvas) {
        val slotCount = if (entered.isEmpty()) 4 else min(entered.length, 8)
        val count = maxOf(slotCount, 1)
        val spacing = dp(15f)
        val totalWidth = spacing * (count - 1)
        val startX = cx - totalWidth / 2f

        for (i in 0 until count) {
            val slotX = startX + spacing * i
            if (i < entered.length && i == revealedIndex) {
                numTextPaint.color = colorText
                numTextPaint.textSize = dp(15f)
                val metrics = numTextPaint.fontMetrics
                canvas.drawText(entered[i].toString(), slotX, cy - (metrics.ascent + metrics.descent) / 2f, numTextPaint)
            } else if (i < entered.length) {
                dotPaint.color = colorBlue
                canvas.drawCircle(slotX, cy, dp(4f), dotPaint)
            } else {
                dotPaint.color = colorMuted
                dotPaint.alpha = 80
                canvas.drawCircle(slotX, cy, dp(3.5f), dotPaint)
                dotPaint.alpha = 255
            }
        }
    }

    // ---------- Touch handling (ported from the reference pointer-event logic) ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onPointerMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onPointerUp()
        }
        return true
    }

    private fun angleOf(x: Float, y: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun findTouchedButton(x: Float, y: Float): DialButton? {
        for (b in buttons) {
            if (hypot((x - b.bx).toDouble(), (y - b.by).toDouble()) <= hitRadiusPx) return b
        }
        return null
    }

    private fun onPointerDown(event: MotionEvent) {
        if (isAutoSpinning) return
        isPointerDown = true
        hasRotated = false
        startPointerAngle = angleOf(event.x, event.y)
        lastSoundAngle = startPointerAngle

        val hit = findTouchedButton(event.x, event.y)
        activeButton = hit
        if (hit != null) {
            playTapSound()
            vibrateFor(5)
        }
        invalidate()
    }

    private fun onPointerMove(event: MotionEvent) {
        if (!isPointerDown || isAutoSpinning) return

        val angle = angleOf(event.x, event.y)
        var diff = angle - startPointerAngle
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360

        var soundDiff = abs(angle - lastSoundAngle)
        if (soundDiff > 180) soundDiff = 360 - soundDiff
        if (soundDiff >= 10) {
            playTickSound()
            vibrateFor(4)
            lastSoundAngle = angle
        }

        if (abs(diff) > 7) hasRotated = true
        currentRotation = diff

        activeButton?.let { b ->
            val currentDeg = (b.baseDeg + currentRotation + 360f) % 360f
            val distToStopper = abs(currentDeg - 270f)
            stopperLocked = distToStopper < 24f || distToStopper > 336f
        }
        invalidate()
    }

    private fun onPointerUp() {
        if (!isPointerDown || isAutoSpinning) return
        isPointerDown = false

        val touched = activeButton
        activeButton = null

        if (touched != null) {
            if (!hasRotated) {
                handleInput(touched.value)
                snapBack()
                return
            } else {
                var targetAngleNeeded = (270f - touched.baseDeg + 360f) % 360f
                if (targetAngleNeeded == 0f) targetAngleNeeded = 360f
                if (abs(currentRotation) >= 8f) {
                    animateFullRotationToStopper(targetAngleNeeded, touched.value)
                    return
                }
            }
        }
        snapBack()
    }

    private fun snapBack() {
        stopperLocked = false
        val start = currentRotation
        if (start == 0f) {
            invalidate()
            return
        }
        val anim = ValueAnimator.ofFloat(start, 0f)
        anim.duration = 240
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener {
            currentRotation = it.animatedValue as Float
            invalidate()
        }
        anim.start()
    }

    private fun animateFullRotationToStopper(targetDeg: Float, numVal: String) {
        isAutoSpinning = true
        val startDeg = currentRotation
        val delta = targetDeg - startDeg

        val forward = ValueAnimator.ofFloat(0f, 1f)
        forward.duration = 220
        var lastTick = startDeg
        forward.addUpdateListener {
            val progress = it.animatedFraction
            val ease = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            currentRotation = startDeg + delta * ease
            if (abs(currentRotation - lastTick) >= 10f) {
                playTickSound()
                vibrateFor(3)
                lastTick = currentRotation
            }
            invalidate()
        }
        forward.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentRotation = targetDeg
                stopperLocked = true
                playTapSound()
                vibrateFor(8)
                handleInput(numVal)
                invalidate()
                handler.postDelayed({
                    stopperLocked = false
                    animateRewind()
                }, 60)
            }
        })
        forward.start()
    }

    private fun animateRewind() {
        val start = currentRotation
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 240
        var lastTick = start
        anim.addUpdateListener {
            val progress = it.animatedFraction
            val ease = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            currentRotation = start * (1f - ease)
            if (abs(currentRotation - lastTick) >= 12f) {
                playTickSound()
                vibrateFor(3)
                lastTick = currentRotation
            }
            invalidate()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentRotation = 0f
                isAutoSpinning = false
                invalidate()
            }
        })
        anim.start()
    }

    // ---------- PIN entry logic ----------

    private fun handleInput(value: String) {
        if (value == "\u2715") deleteLastDigit() else appendDigit(value)
    }

    private fun deleteLastDigit() {
        if (entered.isEmpty()) return
        playTapSound()
        vibrateFor(6)
        settleRunnable?.let { handler.removeCallbacks(it) }
        maskRunnable?.let { handler.removeCallbacks(it) }
        entered.deleteCharAt(entered.length - 1)
        revealedIndex = -1
        invalidate()
    }

    private fun appendDigit(value: String) {
        if (entered.length >= maxDigits) return
        entered.append(value)
        val idx = entered.length - 1
        revealedIndex = idx

        playTapSound()
        vibrateFor(8)
        invalidate()

        maskRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (revealedIndex == idx) revealedIndex = -1
            invalidate()
        }
        maskRunnable = runnable
        handler.postDelayed(runnable, 1000)

        settleRunnable?.let { handler.removeCallbacks(it) }
        if (entered.length >= MIN_DIGITS_TO_VERIFY) {
            val settle = Runnable { verifyPin() }
            settleRunnable = settle
            handler.postDelayed(settle, SETTLE_DELAY_MS)
        }
    }

    private fun verifyPin() {
        revealedIndex = -1
        val pin = entered.toString()
        val correct = onVerify?.invoke(pin) ?: false
        if (correct) {
            trackState = STATE_SUCCESS
            vibratePattern(longArrayOf(0, 15, 40, 15))
            invalidate()
            onSuccess?.invoke()
            handler.postDelayed({ reset() }, 500)
        } else {
            trackState = STATE_ERROR
            vibrateFor(50)
            invalidate()
            shake()
            onError?.invoke()
            handler.postDelayed({ reset() }, 400)
        }
    }

    /** Force an immediate verification attempt instead of waiting for the settle delay. */
    fun verifyNow() {
        if (entered.length < MIN_DIGITS_TO_VERIFY || isAutoSpinning) return
        settleRunnable?.let { handler.removeCallbacks(it) }
        verifyPin()
    }

    fun currentLength(): Int = entered.length

    /** Clears entry and visuals, ready for another attempt. Safe to call any time. */
    fun reset() {
        settleRunnable?.let { handler.removeCallbacks(it) }
        maskRunnable?.let { handler.removeCallbacks(it) }
        entered.clear()
        revealedIndex = -1
        trackState = STATE_NORMAL
        currentRotation = 0f
        stopperLocked = false
        invalidate()
    }

    private fun shake() {
        val anim = android.animation.ObjectAnimator.ofFloat(
            this, "translationX",
            0f, -dp(8f), dp(8f), -dp(5f), dp(5f), 0f
        )
        anim.duration = 380
        anim.start()
    }

    // ---------- Feedback ----------

    private fun playTapSound() {
        if (!hapticsSoundEnabled) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 18) } catch (e: Exception) { }
    }

    private fun playTickSound() {
        if (!hapticsSoundEnabled) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 8) } catch (e: Exception) { }
    }

    private fun vibrateFor(ms: Long) {
        if (!hapticsSoundEnabled) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (e: Exception) { }
    }

    private fun vibratePattern(pattern: LongArray) {
        if (!hapticsSoundEnabled) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) { }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        settleRunnable?.let { handler.removeCallbacks(it) }
        maskRunnable?.let { handler.removeCallbacks(it) }
        try { toneGen?.release() } catch (e: Exception) { }
    }

    companion object {
        private const val STATE_NORMAL = 0
        private const val STATE_ERROR = 1
        private const val STATE_SUCCESS = 2
        private const val MIN_DIGITS_TO_VERIFY = 4
        private const val SETTLE_DELAY_MS = 900L
    }
}

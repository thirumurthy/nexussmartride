package com.thirutricks.nexussmartride.ui

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.thirutricks.nexussmartride.R

/**
 * Handles smooth staggered entrance animations, card press spring feedback,
 * and live connected electric powertrain aura animations on the Scooter Hero Card.
 */
object CyberCardAnimator {

    private var scooterFloatAnimator: ObjectAnimator? = null
    private var auraPulseAnimator: ObjectAnimator? = null
    private var borderStrokeAnimator: ValueAnimator? = null
    private var badgePulseAnimator: ObjectAnimator? = null

    /**
     * Staggered slide-up and fade-in entrance for all cards in the Home Tab.
     */
    fun animateHomeCardsEntrance(container: ViewGroup) {
        val childCount = container.childCount
        for (i in 0 until childCount) {
            val card = container.getChildAt(i) ?: continue
            card.alpha = 0f
            card.translationY = 55f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(420)
                .setStartDelay((i * 45).toLong())
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    /**
     * Attaches cyber spring touch-down feedback to cards.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachCardTouchFeedback(card: View, onClick: (() -> Unit)? = null) {
        card.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.982f)
                        .scaleY(0.982f)
                        .setDuration(120)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    CyberSoundManager.playClickSound(v.context)
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(220)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                    v.performClick()
                    onClick?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            true
        }
    }

    /**
     * Activates live high-tech powertrain hover and cyan energy aura
     * when the scooter cluster is connected.
     */
    fun startHeroConnectedAnimation(
        cardHero: MaterialCardView?,
        ivScooter: ImageView?,
        viewAura: View?,
        tvBadge: TextView?
    ) {
        stopHeroConnectedAnimation(cardHero, ivScooter, viewAura, tvBadge)

        // 1. Scooter gentle floating & breathing hover animation
        ivScooter?.let { scooter ->
            val pvhY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -8f, 0f)
            val pvhScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.035f, 1.0f)
            val pvhScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.035f, 1.0f)

            scooterFloatAnimator = ObjectAnimator.ofPropertyValuesHolder(scooter, pvhY, pvhScaleX, pvhScaleY).apply {
                duration = 3400L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }

        // 2. Neon cyan energy aura pulse behind the scooter
        viewAura?.let { aura ->
            aura.visibility = View.VISIBLE
            val pvhAuraScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.28f, 0.92f)
            val pvhAuraScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.28f, 0.92f)
            val pvhAuraAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 0.28f, 0.85f)

            auraPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(aura, pvhAuraScaleX, pvhAuraScaleY, pvhAuraAlpha).apply {
                duration = 2600L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }

        // 3. Glowing neon border stroke pulsation on the hero card
        cardHero?.let { card ->
            val startColor = Color.parseColor("#4000E5FF")
            val pulseColor = Color.parseColor("#FF00E5FF")
            card.strokeWidth = 4 // ~2dp

            borderStrokeAnimator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, pulseColor, startColor).apply {
                duration = 2200L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { anim ->
                    val color = anim.animatedValue as? Int ?: return@addUpdateListener
                    card.strokeColor = color
                }
                start()
            }
        }

        // 4. Connected status badge styling & subtle pulse
        tvBadge?.let { badge ->
            badge.text = "⚡ LIVE CLUSTER • BLE 5.2"
            badge.background = ContextCompat.getDrawable(badge.context, R.drawable.bg_pill_cyan_solid)
            badge.setTextColor(Color.BLACK)

            val pvhBadgeAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.82f, 1.0f)
            badgePulseAnimator = ObjectAnimator.ofPropertyValuesHolder(badge, pvhBadgeAlpha).apply {
                duration = 1800L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                start()
            }
        }
    }

    /**
     * Resets the Hero card to idle standby state when disconnected.
     */
    fun stopHeroConnectedAnimation(
        cardHero: MaterialCardView?,
        ivScooter: ImageView?,
        viewAura: View?,
        tvBadge: TextView?
    ) {
        scooterFloatAnimator?.cancel()
        scooterFloatAnimator = null

        auraPulseAnimator?.cancel()
        auraPulseAnimator = null

        borderStrokeAnimator?.cancel()
        borderStrokeAnimator = null

        badgePulseAnimator?.cancel()
        badgePulseAnimator = null

        ivScooter?.animate()
            ?.translationY(0f)
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.setDuration(300)
            ?.start()

        viewAura?.animate()
            ?.alpha(0f)
            ?.setDuration(250)
            ?.withEndAction { viewAura.visibility = View.GONE }
            ?.start()

        cardHero?.let { card ->
            card.strokeColor = ContextCompat.getColor(card.context, R.color.glass_border)
            card.strokeWidth = 2 // ~1dp
        }

        tvBadge?.let { badge ->
            badge.alpha = 1.0f
            badge.text = "BLE 5.2 • STANDBY"
            badge.background = ContextCompat.getDrawable(badge.context, R.drawable.bg_pill_dark)
            badge.setTextColor(ContextCompat.getColor(badge.context, R.color.text_secondary))
        }
    }
}

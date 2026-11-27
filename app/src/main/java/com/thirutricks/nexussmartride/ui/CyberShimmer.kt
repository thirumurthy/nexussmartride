package com.thirutricks.nexussmartride.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * High-performance cyber shimmer pulse animator for skeleton loading states.
 * Runs hardware-accelerated alpha oscillation with zero external dependencies.
 */
object CyberShimmer {
    private val activeAnimators = mutableMapOf<View, ValueAnimator>()

    fun start(vararg rootViews: View) {
        for (view in rootViews) {
            stop(view)
            val animator = ValueAnimator.ofFloat(0.35f, 0.95f).apply {
                duration = 850L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { va ->
                    val alpha = va.animatedValue as Float
                    view.alpha = alpha
                }
            }
            activeAnimators[view] = animator
            animator.start()
        }
    }

    fun stop(vararg rootViews: View) {
        for (view in rootViews) {
            activeAnimators.remove(view)?.cancel()
            view.alpha = 1.0f
        }
    }

    fun stopAll() {
        activeAnimators.values.forEach { it.cancel() }
        activeAnimators.keys.forEach { it.alpha = 1.0f }
        activeAnimators.clear()
    }
}

package org.avium.autotask.overlay.animation

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

class OverlayAnimator {
    private var activeAnimator: ValueAnimator? = null

    fun cancel() {
        activeAnimator?.cancel()
        activeAnimator = null
    }

    fun start(
        durationMs: Long,
        onStart: () -> Unit,
        onFrame: (Float) -> Unit,
        onEnd: () -> Unit,
        onCancel: () -> Unit,
    ) {
        cancel()

        val animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener { valueAnimator ->
                    onFrame(valueAnimator.animatedValue as Float)
                }
                addListener(
                    object : Animator.AnimatorListener {
                        override fun onAnimationStart(animation: Animator) = onStart()

                        override fun onAnimationEnd(animation: Animator) {
                            activeAnimator = null
                            onEnd()
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            activeAnimator = null
                            onCancel()
                        }

                        override fun onAnimationRepeat(animation: Animator) = Unit
                    },
                )
            }

        activeAnimator = animator
        animator.start()
    }
}

package com.karen.flymetool.hook.feature.launcher

internal object RecentsNativeScale {
    fun compose(
        persistent: Float,
        dismiss: Float,
        modalness: Float,
        modalScale: Float
    ): Float? {
        if (!persistent.isFinite() || !dismiss.isFinite() ||
            !modalness.isFinite() || !modalScale.isFinite()
        ) {
            return null
        }
        val scale = persistent * dismiss * (1f + modalness * (modalScale - 1f))
        return scale.takeIf { it.isFinite() && it >= 0f }
    }
}

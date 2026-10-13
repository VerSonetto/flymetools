package com.karen.flymetool.hook.feature.launcher

internal class RecentsLaunchGate {
    private var currentToken = 0L

    private var isActive = false

    val active: Boolean
        get() = isActive

    fun begin(): Long {
        currentToken++
        isActive = true
        return currentToken
    }

    fun cancel(token: Long): Boolean {
        if (!active || token != currentToken) return false
        isActive = false
        return true
    }

    fun reset() {
        currentToken++
        isActive = false
    }
}

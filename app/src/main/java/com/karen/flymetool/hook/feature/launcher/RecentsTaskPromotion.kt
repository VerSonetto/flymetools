package com.karen.flymetool.hook.feature.launcher

/** 仅在一次原生重排调用内覆盖目标位置，不影响底部快速切换或普通任务加载。 */
internal class RecentsTaskPromotion {
    private var task: Any? = null
    private var index = -1

    fun expectedIndex(candidate: Any): Int? = if (candidate === task) index else null

    fun <T> withTarget(candidate: Any, targetIndex: Int, move: () -> T): T {
        require(targetIndex >= 0)
        val previousTask = task
        val previousIndex = index
        task = candidate
        index = targetIndex
        try {
            return move()
        } finally {
            task = previousTask
            index = previousIndex
        }
    }
}

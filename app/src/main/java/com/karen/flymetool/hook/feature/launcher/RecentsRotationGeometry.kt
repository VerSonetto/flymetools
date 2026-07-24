package com.karen.flymetool.hook.feature.launcher

internal object RecentsRotationGeometry {

    fun isLandscape(rotation: Int): Boolean {
        return rotation == 1 || rotation == 3
    }

    /** 将 Quickstep 逻辑主轴转换为屏幕从左到右的物理方向。 */
    fun logicalToPhysical(value: Float, rotation: Int): Float {
        return value * physicalDirection(rotation)
    }

    fun physicalToLogical(value: Float, rotation: Int): Float {
        return value * physicalDirection(rotation)
    }

    /** 将 Quickstep 逻辑次轴转换为屏幕从上到下的物理方向。 */
    fun logicalSecondaryToPhysical(value: Float, rotation: Int): Float {
        return value * physicalDirection(rotation)
    }

    private fun physicalDirection(rotation: Int): Float {
        return when (rotation) {
            2, 3 -> -1f
            else -> 1f
        }
    }
}

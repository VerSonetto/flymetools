package com.karen.flymetool.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsposedModuleLogFilterTest {

    @Test
    fun keepsFlymeToolRecordAndItsStackTraceOnly() {
        val filter = LsposedModuleLogFilter()

        assertFalse(filter.shouldKeep("----part 1 start----"))
        assertFalse(filter.shouldKeep(otherModuleHeader()))
        assertTrue(filter.shouldKeep(flymeToolHeader()))
        assertTrue(filter.shouldKeep("java.lang.IllegalStateException: test"))
        assertTrue(filter.shouldKeep("    at com.karen.flymetool.Test.run(Test.kt:1)"))
        assertFalse(filter.shouldKeep(otherModuleHeader()))
        assertFalse(filter.shouldKeep("other module continuation"))
    }

    private fun flymeToolHeader(): String {
        return "[ 2026-08-10T12:00:00.000 I/LSPosedFramework ] " +
            "(system)[com.karen.flymetool,XposedBridge,id,0,1] [FlymeTool] test"
    }

    private fun otherModuleHeader(): String {
        return "[ 2026-08-10T12:00:00.000 I/LSPosedFramework ] " +
            "(system)[other.module,XposedBridge,id,0,1] test"
    }
}

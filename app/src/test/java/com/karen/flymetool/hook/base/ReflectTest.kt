package com.karen.flymetool.hook.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ReflectTest {
    @Test
    fun concreteOverrideSkipsAbstractParentDeclaration() {
        val methods = Reflect.findHookableMethods(ConcreteSection::class.java, "addViews")

        assertEquals(1, methods.size)
        assertEquals(ConcreteSection::class.java, methods.single().declaringClass)
        assertFalse(Modifier.isAbstract(methods.single().modifiers))
    }

    @Test
    fun inheritedConcreteImplementationIsStillHookable() {
        val methods = Reflect.findHookableMethods(InheritedSection::class.java, "addViews")

        assertEquals(1, methods.size)
        assertEquals(ConcreteSection::class.java, methods.single().declaringClass)
    }

    @Test
    fun abstractOnlyDeclarationIsNotHookable() {
        assertTrue(Reflect.findHookableMethods(AbstractSection::class.java, "addViews").isEmpty())
    }

    @Test
    fun concreteParentImplementationsAndOverloadsArePreserved() {
        val methods = Reflect.findHookableMethods(OverridingSection::class.java, "addViews")
        val signatures = methods.map { it.declaringClass to it.parameterTypes.single() }.toSet()

        assertEquals(3, methods.size)
        assertEquals(
            setOf(
                OverridingSection::class.java to String::class.java,
                OverloadedSection::class.java to String::class.java,
                OverloadedSection::class.java to Int::class.javaPrimitiveType,
            ),
            signatures,
        )
    }

    @Test
    fun callerExclusionsStillFilterConcreteMethods() {
        val methods = Reflect.findHookableMethods(OverridingSection::class.java, "addViews") {
            it.parameterTypes.single() == Int::class.javaPrimitiveType
        }

        assertEquals(2, methods.size)
        assertTrue(methods.all { it.parameterTypes.single() == String::class.java })
    }

    @Test
    fun callerExclusionsDoNotReceiveAbstractDeclarations() {
        var inspectedCount = 0
        val methods = Reflect.findHookableMethods(ConcreteSection::class.java, "addViews") {
            assertFalse(Modifier.isAbstract(it.modifiers))
            inspectedCount++
            false
        }

        assertEquals(1, inspectedCount)
        assertEquals(1, methods.size)
    }

    @Test
    fun ordinaryReflectionLookupStillIncludesAbstractDeclarations() {
        val methods = Reflect.findMethods(ConcreteSection::class.java, "addViews")

        assertEquals(2, methods.size)
        assertTrue(methods.any { Modifier.isAbstract(it.modifiers) })
        assertEquals(
            AbstractSection::class.java.getDeclaredMethod("addViews", String::class.java),
            Reflect.findMethod(AbstractSection::class.java, "addViews", String::class.java),
        )
    }

    @Test
    fun missingMethodReturnsNoHookTargets() {
        assertTrue(Reflect.findHookableMethods(ConcreteSection::class.java, "missing").isEmpty())
    }

    private abstract class AbstractSection {
        abstract fun addViews(host: String)
    }

    private open class ConcreteSection : AbstractSection() {
        override fun addViews(host: String) = Unit
    }

    private class InheritedSection : ConcreteSection()

    private open class OverloadedSection {
        open fun addViews(host: String) = Unit
        fun addViews(host: Int) = Unit
    }

    private class OverridingSection : OverloadedSection() {
        override fun addViews(host: String) = super.addViews(host)
    }
}

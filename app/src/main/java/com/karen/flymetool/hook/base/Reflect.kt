package com.karen.flymetool.hook.base

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 自建反射工具层（libxposed API 101 没有 XposedHelpers）。
 *
 * 在 Modern API 之上保留经典 XposedHelpers 的"按名字查找/调用/hook"语义，
 * 让各 feature Hook 只做回调外壳改造，不改变查找逻辑。
 *
 * 设计约束：
 * - 热路径必须命中缓存（本项目有卡顿先例：热路径禁止逐帧深反射/深栈行走）。
 * - 方法调用走框架 Invoker（绕过访问检查），不额外 setAccessible。
 * - 字段访问走 java.lang.reflect.Field + setAccessible，带父类回溯。
 * - 找不到成员时抛异常（语义同经典 findAndHookMethod），由各 Hook 的 try-catch 兜底。
 */
object Reflect {

    private val methodCache = ConcurrentHashMap<String, Method?>()
    private val fieldCache = ConcurrentHashMap<String, Field?>()
    private val ctorCache = ConcurrentHashMap<String, Constructor<*>?>()

    private fun methodKey(clazz: Class<*>, name: String, params: List<Class<*>?>): String =
        "${clazz.name}#$name(${params.joinToString(",") { it?.name ?: "*" }})"

    private fun fieldKey(clazz: Class<*>, name: String): String = "${clazz.name}#$name"

    // ---------- 类 ----------

    /** 语义同 XposedHelpers.findClass：按全限定名 + 类加载器加载。 */
    fun findClass(name: String, classLoader: ClassLoader?): Class<*> {
        val loader = classLoader ?: javaClass.classLoader
        return Class.forName(name, false, loader)
    }

    // ---------- 方法查找 ----------

    /** 基本类型装箱，用于参数匹配（int ↔ Integer 视为等价）。 */
    private fun boxCls(c: Class<*>): Class<*> = when (c) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> c
    }

    /** 参数匹配（given 为 null 表示通配；int ↔ Integer 视为等价）。 */
    private fun matchesParams(declared: List<Class<*>>, given: List<Class<*>?>): Boolean {
        if (declared.size != given.size) return false
        for (i in declared.indices) {
            val g = given[i]
            if (g != null && boxCls(declared[i]) != boxCls(g)) return false
        }
        return true
    }

    /** 按参数类型精确定位（含父类、私有方法）；找不到返回 null。 */
    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? {
        val params = paramTypes.toList()
        return methodCache.computeIfAbsent(methodKey(clazz, name, params)) {
            var c: Class<*>? = clazz
            while (c != null) {
                for (m in c.declaredMethods) {
                    if (m.name == name && matchesParams(m.parameterTypes.toList(), params)) {
                        return@computeIfAbsent m
                    }
                }
                c = c.superclass
            }
            null
        }
    }

    /** 按名字取全部同名方法（含父类、私有方法）。 */
    fun findMethods(clazz: Class<*>, name: String): List<Method> {
        var c: Class<*>? = clazz
        val out = mutableListOf<Method>()
        while (c != null) {
            c.declaredMethods.filter { it.name == name }.forEach { if (it !in out) out.add(it) }
            c = c.superclass
        }
        return out
    }

    // ---------- 构造器查找 ----------

    fun findConstructor(clazz: Class<*>, vararg paramTypes: Class<*>?): Constructor<*>? {
        val params = paramTypes.toList()
        return ctorCache.computeIfAbsent(methodKey(clazz, "<init>", params)) {
            for (c in clazz.declaredConstructors) {
                if (matchesParams(c.parameterTypes.toList(), params)) return@computeIfAbsent c
            }
            null
        }
    }

    // ---------- 调用（经典语义：java.lang.reflect 直调，解析结果按 arity 缓存） ----------

    /**
     * 热路径专用解析缓存：键 = "类名#方法名#参数个数"。
     * 命中后 O(1)，彻底避免每帧 findMethods 全扫与框架 Invoker 分配；
     * 与经典 XposedHelpers.callMethod 行为一致（反射直达原实现）。
     */
    private val invokedMethodCache = ConcurrentHashMap<String, Method>()
    private val invokedCtorCache = ConcurrentHashMap<String, Constructor<*>>()

    private fun invokedMethod(clazz: Class<*>, name: String, args: Array<out Any?>): Method {
        val key = "${clazz.name}#$name#${args.size}"
        invokedMethodCache[key]?.let { return it }
        val m = resolveDeclared(clazz, name, args.map { it?.javaClass })
            ?: throw NoSuchMethodException("$name in ${clazz.name}")
        try {
            m.isAccessible = true
        } catch (_: Throwable) {
        }
        invokedMethodCache[key] = m
        return m
    }

    private fun invokedCtor(clazz: Class<*>, args: Array<out Any?>): Constructor<*> {
        val key = "${clazz.name}#<init>#${args.size}"
        invokedCtorCache[key]?.let { return it }
        val c = resolveCtor(clazz, args.map { it?.javaClass })
            ?: throw NoSuchMethodException("<init> in ${clazz.name}")
        try {
            c.isAccessible = true
        } catch (_: Throwable) {
        }
        invokedCtorCache[key] = c
        return c
    }

    /** 语义同 XposedHelpers.callMethod：按名字 + 实参类型解析后反射调用（缓存命中 O(1)）。 */
    fun callMethod(api: XposedInterface, obj: Any?, name: String, vararg args: Any?): Any? {
        val m = invokedMethod(obj?.javaClass ?: throw NullPointerException(), name, args)
        return m.invoke(obj, *args)
    }

    /** 语义同 XposedHelpers.callStaticMethod。 */
    fun callStaticMethod(api: XposedInterface, clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val m = invokedMethod(clazz, name, args)
        return m.invoke(null, *args)
    }

    /** 语义同 XposedHelpers.newInstance：按实参类型选构造器后反射创建。 */
    fun <T> newInstance(api: XposedInterface, clazz: Class<T>, vararg args: Any?): T {
        val c = invokedCtor(clazz, args)
        @Suppress("UNCHECKED_CAST")
        return c.newInstance(*args) as T
    }

    /** 解析：优先类型匹配，其次退化为仅按参数个数匹配（保留经典选择宽松度）。 */
    private fun resolveDeclared(clazz: Class<*>, name: String, argTypes: List<Class<*>?>): Method? {
        val candidates = findMethods(clazz, name)
        if (candidates.isEmpty()) return null
        val boxedArgs = argTypes.map { it?.let(::boxCls) }
        return candidates.firstOrNull { m ->
            val pts = m.parameterTypes.map(::boxCls)
            pts.size == boxedArgs.size && pts.indices.all { i ->
                val a = boxedArgs[i]
                a == null || pts[i].isAssignableFrom(a)
            }
        } ?: candidates.firstOrNull { it.parameterCount == argTypes.size }
    }

    private fun resolveCtor(clazz: Class<*>, argTypes: List<Class<*>?>): Constructor<*>? {
        val ctors = clazz.declaredConstructors
        if (ctors.isEmpty()) return null
        val boxedArgs = argTypes.map { it?.let(::boxCls) }
        return ctors.firstOrNull { c ->
            val pts = c.parameterTypes.map(::boxCls)
            pts.size == boxedArgs.size && pts.indices.all { i ->
                val a = boxedArgs[i]
                a == null || pts[i].isAssignableFrom(a)
            }
        } ?: ctors.firstOrNull { it.parameterCount == argTypes.size }
    }

    // ---------- 字段 ----------

    private fun findField(clazz: Class<*>, name: String): Field? {
        return fieldCache.computeIfAbsent(fieldKey(clazz, name)) {
            var c: Class<*>? = clazz
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    if (!f.isAccessible) {
                        try {
                            f.isAccessible = true
                        } catch (_: Throwable) {
                        }
                    }
                    return@computeIfAbsent f
                } catch (_: NoSuchFieldException) {
                }
                c = c.superclass
            }
            null
        }
    }

    /** 字段必定要存在（语义同经典 XposedHelpers：找不到即抛，由调用方 try-catch 兜底）。 */
    private fun fieldOrThrow(clazz: Class<*>, name: String): Field =
        findField(clazz, name) ?: throw NoSuchFieldException("$name in ${clazz.name}")

    fun getObjectField(obj: Any, name: String): Any? = fieldOrThrow(obj.javaClass, name).get(obj)
    fun getBooleanField(obj: Any, name: String): Boolean = fieldOrThrow(obj.javaClass, name).getBoolean(obj)
    fun getIntField(obj: Any, name: String): Int = fieldOrThrow(obj.javaClass, name).getInt(obj)
    fun getFloatField(obj: Any, name: String): Float = fieldOrThrow(obj.javaClass, name).getFloat(obj)
    fun getStaticObjectField(clazz: Class<*>, name: String): Any? = fieldOrThrow(clazz, name).get(null)
    fun getStaticIntField(clazz: Class<*>, name: String): Int = fieldOrThrow(clazz, name).getInt(null)

    fun setObjectField(obj: Any, name: String, value: Any?) {
        fieldOrThrow(obj.javaClass, name).set(obj, value)
    }

    fun setBooleanField(obj: Any, name: String, value: Boolean) {
        fieldOrThrow(obj.javaClass, name).setBoolean(obj, value)
    }

    fun setIntField(obj: Any, name: String, value: Int) {
        fieldOrThrow(obj.javaClass, name).setInt(obj, value)
    }

    fun setFloatField(obj: Any, name: String, value: Float) {
        fieldOrThrow(obj.javaClass, name).setFloat(obj, value)
    }

    /** 语义同 XposedHelpers.getSurroundingThis：取匿名/内部类的外层实例。 */
    fun getSurroundingThis(obj: Any): Any? {
        return try {
            val f = obj.javaClass.getDeclaredField("this\$0")
            if (!f.isAccessible) {
                try {
                    f.isAccessible = true
                } catch (_: Throwable) {
                }
            }
            f.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- Hook 挂载 ----------

    /** 语义同 XposedBridge.hookMethod + XC_MethodHook：统一 PROTECTIVE，防 hook 异常打崩进程。 */
    fun hookMethod(api: XposedInterface, method: Method, block: (Chain) -> Any?) {
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain -> block(chain) }
    }

    /** 语义同 XposedHelpers.findAndHookMethod：按签名定位后挂接；找不到抛异常（由调用方 try-catch 兜底）。 */
    fun hookMethodOn(
        api: XposedInterface,
        clazz: Class<*>,
        name: String,
        vararg paramTypes: Class<*>?,
        block: (Chain) -> Any?
    ) {
        val m = findMethod(clazz, name, *paramTypes) ?: throw NoSuchMethodException("$name(${paramTypes.joinToString { it?.name ?: "*" }})")
        hookMethod(api, m, block)
    }

    /**
     * 语义同 XposedBridge.hookAllMethods：挂接同名全部方法。
     * block 放在最后，使 `hookAllMethods(api, clazz, name) { chain -> ... }`
     * 的尾随 lambda 能正确绑定到 block（而非 excluded）。
     */
    fun hookAllMethods(
        api: XposedInterface,
        clazz: Class<*>,
        name: String,
        excluded: (Method) -> Boolean = { false },
        block: (Chain) -> Any?
    ) {
        findMethods(clazz, name).filter { !excluded(it) }.forEach { hookMethod(api, it, block) }
    }

    /** 语义同 XposedBridge.hookAllConstructors。 */
    fun hookAllConstructors(
        api: XposedInterface,
        clazz: Class<*>,
        block: (Chain) -> Any?
    ) {
        clazz.declaredConstructors.forEach { c ->
            api.hook(c)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain -> block(chain) }
        }
    }

    /** 语义同 XposedHelpers.findAndHookConstructor。 */
    fun hookConstructorOn(
        api: XposedInterface,
        clazz: Class<*>,
        vararg paramTypes: Class<*>?,
        block: (Chain) -> Any?
    ) {
        val c = findConstructor(clazz, *paramTypes) ?: throw NoSuchMethodException("<init>(${paramTypes.joinToString { it?.name ?: "*" }})")
        api.hook(c)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain -> block(chain) }
    }
}
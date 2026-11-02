package com.karen.flymetool.hook.feature.systemtools

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import java.util.HashMap

/**
 * 系统界面工具：在小窗快捷菜单的「功能」页加入「唤起检索」条目。
 *
 * 数据流：功能区列表由 SlideLaunchAppSettings 汇总成「分组标题 -> 条目列表」的 Map 后
 * 交给列表适配器；已添加的条目由快捷菜单管理器持有，供 AppLauncherWindow 取前 6 项铺弧。
 * 这里在汇总 Map 交给适配器前补入条目，并在弧菜单点击时把该条目路由到桌面检索。
 *
 * 条目复用系统的 AppInfo 模型（C0652a）：用公开四参构造器（标识、标题、包名、图标）建实例，
 * 包名用哨兵值，避免和真实应用列表混淆。
 *
 * 三处已知陷阱（均已在下方处理）：
 * 1. 三参构造器只写「快捷方式图标」字段，非快捷方式条目渲染读的是另一个图标字段，需补设；
 * 2. 弧菜单只铺前 6 个快捷项，条目追加在列表末尾会落到弧外，必须在构建前插到列表前面；
 * 3. 系统按包名持久化已添加列表，哨兵包名在重启加载时查不到会被丢弃，因此自存一份状态。
 */
object SlideGestureSearchEntryHook : FeatureHook {

    private const val TAG = "SlideGestureSearchEntry"
    private const val FEATURE_KEY = "slide_gesture_search_entry"
    private const val TARGET_PACKAGE = "com.flyme.systemuitools"

    private const val SETTINGS_ACTIVITY =
        "com.flyme.systemuitools.windowmode.views.SlideLaunchAppSettings"
    private const val APP_LAUNCHER_WINDOW =
        "com.flyme.systemuitools.windowmode.views.AppLauncherWindow"
    private const val SHORTCUT_FACTORY =
        "com.flyme.systemuitools.windowmode.factory.ShortcutFactory"
    private const val GESTURE_APP_LAUNCHER =
        "com.flyme.systemuitools.windowmode.widget.GestureAppLauncher"

    /** 目标界面是桌面的字母检索面板（左右边缘上下滑动那个），需要拉桌面进程来展开 */
    private const val LAUNCHER_PACKAGE = "com.meizu.flyme.launcher"

    /** 与桌面侧 SlideSearchLauncherHook 约定的请求标记 */
    private const val EXTRA_SHOW_SLIDE_SEARCH = "com.karen.flymetool.extra.SHOW_SLIDE_SEARCH"

    /** 弧菜单在哪半屏展开：0=左、1=右，桌面侧据此在同侧展开检索 */
    private const val EXTRA_SIDE = "com.karen.flymetool.extra.SLIDE_SEARCH_SIDE"

    /** 条目图标借搜索应用的应用图标 */
    private const val ICON_PACKAGE = "com.meizu.net.search"

    private const val SENTINEL_PACKAGE = "com.karen.flymetool.search_entry"
    private const val ENTRY_LABEL = "唤起检索"
    private const val ENTRY_ID = "flymetool_search_entry"

    /**
     * 「已加入弧菜单」状态。系统的已添加列表按包名持久化，重启加载时按包名回查已安装应用，
     * 哨兵包名查不到会被丢弃，因此这里自存一份，弧菜单据此决定是否包含本条目。
     */
    private const val STATE_PREFS = "flymetool_slide_menu"
    private const val KEY_ADDED = "search_entry_added"

    private const val RX_SUBSCRIBE = "com.hwangjr.rxbus.annotation.Subscribe"

    private var itemClass: Class<*>? = null
    private var labelGetter: java.lang.reflect.Method? = null

    /** 弧菜单本次的展开方向：0=左半屏、1=右半屏，由 GestureAppLauncher 的布局方向决定 */
    @Volatile
    private var wheelSide = 0

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != TARGET_PACKAGE) return
        if (!ctx.featureEnabled(FEATURE_KEY)) return

        hookFunctionList(ctx)
        hookAddEvent(ctx)
        hookWheelSide(ctx)
        hookWheelItems(ctx)
        hookWheelClick(ctx)
        hookHolderClick(ctx)

        Logger.i(TAG, "已加载")
    }

    // ---------- 功能区注入 ----------

    /**
     * SlideLaunchAppSettings 的页面装配方法签名：void (List, HashMap, HashMap)。
     * 第一个参数是已添加列表，第三个是「功能」页分组（值即全部功能条目）；
     * 必须在原方法执行前补入，否则列表已经建好。
     */
    private fun hookFunctionList(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(SETTINGS_ACTIVITY, ctx.classLoader)
            val method = clazz.declaredMethods.firstOrNull { m ->
                m.returnType == Void.TYPE &&
                    m.parameterCount == 3 &&
                    List::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    m.parameterTypes[1] == HashMap::class.java &&
                    m.parameterTypes[2] == HashMap::class.java
            }
            if (method == null) {
                Logger.w(TAG, "未找到功能区列表装配方法")
                return
            }

            Reflect.hookMethod(ctx.api, method) { chain ->
                try {
                    injectIntoFunctionPage(chain.getThisObject(), chain.getArgs().getOrNull(2))
                } catch (t: Throwable) {
                    Logger.e(TAG, "注入功能区条目失败", t)
                }
                chain.proceed()
            }
            Logger.i(TAG, "已挂载功能区列表装配")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 功能区列表失败", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectIntoFunctionPage(activity: Any?, functionMap: Any?) {
        val context = activity as? Context ?: return
        val map = functionMap as? MutableMap<Any?, Any?> ?: return
        val list = map.values.firstOrNull { it is MutableList<*> } as? MutableList<Any?> ?: return
        if (list.any { labelOf(it) == ENTRY_LABEL }) return

        val item = createEntry(context) ?: return
        list.add(item)
        Logger.d(TAG) { "功能区已补入「$ENTRY_LABEL」，现有条目=${list.size}" }
    }

    private fun createEntry(context: Context): Any? {
        val clazz = resolveItemClass(context.classLoader) ?: return null
        val icon = runCatching { context.packageManager.getApplicationIcon(ICON_PACKAGE) }.getOrNull()

        val ctor = clazz.getDeclaredConstructor(
            String::class.java,
            CharSequence::class.java,
            String::class.java,
            Drawable::class.java,
        )
        ctor.isAccessible = true
        val item = ctor.newInstance(ENTRY_ID, ENTRY_LABEL, SENTINEL_PACKAGE, icon) ?: return null

        // 三参构造器只写「快捷方式图标」字段 f26150n，而非快捷方式条目渲染读的是 f26137a；
        // 两个 Drawable 设值方法都调一遍，避免出现无图标。
        clazz.superclass?.declaredMethods
            ?.filter { it.parameterCount == 1 && it.parameterTypes[0] == Drawable::class.java }
            ?.forEach { setter ->
                runCatching {
                    setter.isAccessible = true
                    setter.invoke(item, icon)
                }
            }

        // 用户档案必须与真实条目一致，否则 equals 比对会走异常分支，加入时被判重失败
        clazz.superclass?.declaredMethods
            ?.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == UserHandle::class.java }
            ?.also { it.isAccessible = true }
            ?.invoke(item, Process.myUserHandle())

        return item
    }

    /**
     * 条目模型类从 ShortcutFactory 的构建方法反推：唯一一个参数为内置功能枚举、返回非 void 的方法，
     * 其返回类型即系统实际使用的 AppInfo 类（C0652a）。
     */
    private fun resolveItemClass(classLoader: ClassLoader): Class<*>? {
        itemClass?.let { return it }
        return try {
            val factory = Reflect.findClass(SHORTCUT_FACTORY, classLoader)
            val typeClass = factory.declaredClasses.firstOrNull { it.isEnum } ?: return null
            val build = factory.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 && m.parameterTypes[0] == typeClass && m.returnType != Void.TYPE
            } ?: return null
            build.returnType.also { itemClass = it }
        } catch (t: Throwable) {
            Logger.e(TAG, "解析条目模型类失败", t)
            null
        }
    }

    // ---------- 弧菜单方向 ----------

    /**
     * GestureAppLauncher 用 setLayoutDirection(0/1) 记录弧菜单在左半屏还是右半屏展开，
     * 方法名未混淆，记住最后一次取值即可让检索面板与弧菜单同侧展开。
     */
    private fun hookWheelSide(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(GESTURE_APP_LAUNCHER, ctx.classLoader)
            Reflect.hookMethodOn(ctx.api, clazz, "setLayoutDirection", Int::class.javaPrimitiveType) { chain ->
                (chain.getArgs().getOrNull(0) as? Int)?.let { wheelSide = it }
                chain.proceed()
            }
            Logger.i(TAG, "已挂载弧菜单方向监听")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 弧菜单方向失败", t)
        }
    }

    // ---------- 弧菜单条目 ----------

    /**
     * 弧菜单只取前 6 个快捷项铺弧（AppLauncherWindow 里 `if (i6 >= 6) break`），
     * 追加到末尾等于不可见，因此在构建前把条目插到列表最前面。
     */
    private fun hookWheelItems(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(APP_LAUNCHER_WINDOW, ctx.classLoader)
            val method = clazz.declaredMethods.firstOrNull { m ->
                List::class.java.isAssignableFrom(m.returnType) &&
                    m.parameterCount == 1 &&
                    List::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    m.declaredAnnotations.isEmpty()
            }
            if (method == null) {
                Logger.w(TAG, "未找到快捷项构建方法")
                return
            }

            Reflect.hookMethod(ctx.api, method) { chain ->
                try {
                    ensureWheelEntry(chain.getThisObject(), chain.getArgs().getOrNull(0))
                } catch (t: Throwable) {
                    Logger.e(TAG, "补充弧菜单条目失败", t)
                }
                chain.proceed()
            }
            Logger.i(TAG, "已挂载快捷项构建")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 快捷项构建失败", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ensureWheelEntry(window: Any?, source: Any?) {
        val list = source as? MutableList<Any?> ?: return
        val present = list.any { labelOf(it) == ENTRY_LABEL }
        Logger.d(TAG) { "弧菜单构建：源列表 ${list.size} 项，本条目${if (present) "已在" else "缺失"}" }
        if (present) return

        val context = windowContext(window) ?: return
        if (!readAdded(context)) return

        val item = createEntry(context) ?: return
        list.add(0, item)
        Logger.d(TAG) { "弧菜单已补入「$ENTRY_LABEL」" }
    }

    // ---------- 弧菜单点击 ----------

    /**
     * 弧菜单的点击统一从 AppLauncherWindow 派发（void(条目, View, int)）。
     * 第一个参数类型是混淆的条目基类，用「能接受条目模型类」反推，不依赖名字。
     */
    private fun hookWheelClick(ctx: HookContext) {
        try {
            val windowClass = Reflect.findClass(APP_LAUNCHER_WINDOW, ctx.classLoader)
            val appClass = resolveItemClass(ctx.classLoader) ?: run {
                Logger.w(TAG, "未解析到条目模型类，跳过点击路由")
                return
            }

            val candidates = windowClass.declaredMethods.filter { m ->
                m.returnType == Void.TYPE &&
                    m.parameterCount == 3 &&
                    m.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[0] != Any::class.java &&
                    (
                        m.parameterTypes[0].isAssignableFrom(appClass) ||
                            appClass.isAssignableFrom(m.parameterTypes[0])
                        )
            }
            if (candidates.isEmpty()) {
                Logger.w(TAG, "未找到快捷菜单点击方法")
                return
            }

            for (click in candidates) {
                Reflect.hookMethod(ctx.api, click) { chain ->
                    val item = chain.getArgs().getOrNull(0)
                    if (labelOf(item) == ENTRY_LABEL) {
                        Logger.i(TAG, "点击命中「$ENTRY_LABEL」")
                        launchSearch(chain.getThisObject())
                        null
                    } else {
                        chain.proceed()
                    }
                }
            }
            Logger.i(TAG, "已挂载快捷菜单点击路由 x${candidates.size}")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 快捷菜单点击失败", t)
        }
    }

    /**
     * 兜底：部分路径可能直接从条目包装类的 void(int) 发起，这里再拦一层。
     * 命中时同样改走检索；未被命中则正常执行。
     */
    private fun hookHolderClick(ctx: HookContext) {
        try {
            val windowClass = Reflect.findClass(APP_LAUNCHER_WINDOW, ctx.classLoader)
            val appClass = resolveItemClass(ctx.classLoader) ?: return
            val holder = windowClass.declaredClasses.firstOrNull { inner ->
                inner.declaredConstructors.any { ctor ->
                    ctor.parameterCount == 2 &&
                        ctor.parameterTypes[0].isAssignableFrom(windowClass) &&
                        ctor.parameterTypes[1].isAssignableFrom(appClass)
                }
            } ?: return

            val click = holder.declaredMethods.firstOrNull { m ->
                m.returnType == Void.TYPE &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return

            Reflect.hookMethod(ctx.api, click) { chain ->
                if (labelOf(chain.getThisObject()) == ENTRY_LABEL) {
                    launchSearch(chain.getThisObject())
                    null
                } else {
                    chain.proceed()
                }
            }
            Logger.i(TAG, "已挂载条目包装类点击兜底")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 条目包装类点击失败", t)
        }
    }

    private fun launchSearch(host: Any?) {
        try {
            val context = windowContext(host) ?: windowContext(Reflect.getSurroundingThis(host ?: return))
            if (context == null) {
                Logger.w(TAG, "未取到宿主 Context，无法唤起检索")
                return
            }

            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(LAUNCHER_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_SHOW_SLIDE_SEARCH, true)
                .putExtra(EXTRA_SIDE, wheelSide)

            val resolved = runCatching {
                context.packageManager.queryIntentActivities(intent, 0).size
            }.getOrDefault(-1)
            Logger.i(TAG, "桌面检索 Intent 可解析 $resolved 个，侧边=${if (wheelSide == 1) "右" else "左"}")

            context.startActivity(intent)
            Logger.i(TAG, "已请求桌面展开字母检索")
        } catch (t: Throwable) {
            Logger.e(TAG, "唤起桌面检索失败", t)
        }
    }

    /** AppLauncherWindow 持有唯一的 Context 字段，据此拿宿主环境。 */
    private fun windowContext(window: Any?): Context? {
        if (window == null) return null
        return window.javaClass.declaredFields
            .firstOrNull { Context::class.java.isAssignableFrom(it.type) }
            ?.also { it.isAccessible = true }
            ?.get(window) as? Context
    }

    // ---------- 添加/移除事件 ----------

    /**
     * 菜单管理页把「加入/移出弧菜单」以 RxBus 事件广播出来，事件携带条目和是否添加。
     * 这里挂它的 @Subscribe 处理方法，把本条目真正的开关状态记到自己的存储里；
     * 不依赖系统那份按包名持久化的列表（哨兵包名活不过重启）。
     */
    private fun hookAddEvent(ctx: HookContext) {
        try {
            val activity = Reflect.findClass(SETTINGS_ACTIVITY, ctx.classLoader)
            val subscribe = Reflect.findClass(RX_SUBSCRIBE, ctx.classLoader)
            val appClass = resolveItemClass(ctx.classLoader) ?: run {
                Logger.w(TAG, "未解析到条目模型类，跳过添加事件监听")
                return
            }

            val handlers = activity.declaredMethods.filter { m ->
                m.parameterCount == 1 && m.getAnnotation(subscribe as Class<out Annotation>) != null
            }
            var hooked = 0
            for (method in handlers) {
                if (findNoArg(method.parameterTypes[0], appClass) == null) continue
                Reflect.hookMethod(ctx.api, method) { chain ->
                    try {
                        recordAdded(chain.getThisObject(), chain.getArgs().getOrNull(0), appClass)
                    } catch (t: Throwable) {
                        Logger.e(TAG, "记录添加状态失败", t)
                    }
                    chain.proceed()
                }
                hooked++
            }
            if (hooked == 0) Logger.w(TAG, "未找到条目添加事件处理方法") else Logger.i(TAG, "已挂载添加事件监听 x$hooked")
        } catch (t: Throwable) {
            Logger.e(TAG, "Hook 添加事件失败", t)
        }
    }

    private fun recordAdded(activity: Any?, event: Any?, appClass: Class<*>) {
        val context = activity as? Context ?: return
        if (event == null) return
        val item = findNoArg(event.javaClass, appClass)?.let { m ->
            m.isAccessible = true
            runCatching { m.invoke(event) }.getOrNull()
        } ?: return
        if (labelOf(item) != ENTRY_LABEL) return

        val added = findNoArg(event.javaClass, Boolean::class.javaPrimitiveType)?.let { m ->
            m.isAccessible = true
            runCatching { m.invoke(event) as? Boolean }.getOrNull()
        } ?: return

        writeAdded(context, added)
    }

    /** 沿父类链找无参、返回指定类型的方法（事件与条目模型的 getter 都是混淆名，只能按签名取）。 */
    private fun findNoArg(clazz: Class<*>, returnType: Class<*>?): java.lang.reflect.Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.parameterCount == 0 && it.returnType == returnType
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    // ---------- 状态 ----------

    private fun statePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun readAdded(context: Context): Boolean =
        runCatching { statePrefs(context).getBoolean(KEY_ADDED, false) }.getOrDefault(false)

    private fun writeAdded(context: Context, added: Boolean) {
        runCatching {
            val prefs = statePrefs(context)
            if (prefs.getBoolean(KEY_ADDED, false) != added) {
                prefs.edit().putBoolean(KEY_ADDED, added).apply()
                Logger.i(TAG, "「$ENTRY_LABEL」加入状态 -> $added")
            }
        }
    }

    // ---------- 共用 ----------

    /**
     * 取条目标题：模型里唯一的无参 CharSequence getter。
     * 包名/类型等 getter 返回 String，不会命中，因此不需要依赖混淆名。
     */
    private fun labelOf(item: Any?): String? {
        if (item == null) return null
        val getter = labelGetter ?: findLabelGetter(item.javaClass)?.also { labelGetter = it } ?: return null
        return runCatching { getter.invoke(item)?.toString() }.getOrNull()
    }

    private fun findLabelGetter(clazz: Class<*>): java.lang.reflect.Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val found = current.declaredMethods.firstOrNull {
                it.parameterCount == 0 && it.returnType == CharSequence::class.java
            }
            if (found != null) {
                found.isAccessible = true
                return found
            }
            current = current.superclass
        }
        return null
    }
}

package com.karen.flymetool.hook.feature.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 相机滤镜记忆：记录用户选择的滤镜，冷启动预览开始后自动恢复。
 *
 * 挂载点（均为未混淆的稳定名称，字段按特征定位）：
 * - 记录：FilterAdapter.setSelIndex(int) —— 滤镜列表面板选中项变化的唯一入口，
 *   内部会回调 FilterListener.onSelIndexCheck 走 FilterManager 应用滤镜；
 *   AI 场景识别 / 模式切换重置不走此路径，不会污染记录。
 * - 恢复：MzCamControllerImpl.restoreFilterEffect(boolean) —— 每次预览启动
 *   （含冷启动，MzCamModule.onPreviewStarted）都会调用。原方法只恢复会话内
 *   临时保存的滤镜（FilterHandler 的 mSavedFilterEffect 字段，冷启动为空），
 *   我们在其后补上跨进程持久化的选择。
 */
object FilterMemoryHook : FeatureHook {

    private const val TAG = "FilterMemory"
    private const val PACKAGE_NAME = "com.meizu.media.camera"
    private const val FEATURE_KEY = "camera_filter_memory"
    private const val STORE_FILE = "flymetool_filter_memory"
    private const val RENDER_NONE = "Mznone"

    private val CLASS_FILTER_ADAPTER = "com.meizu.media.camera.filter.FilterAdapter"
    private val CLASS_CONTROLLER_IMPL = "com.meizu.media.camera.impl.MzCamControllerImpl"

    @Volatile
    private var appContext: Context? = null

    /** FilterAdapter 内的滤镜名列表字段（ArrayList<String>，含 "Mznone"），按特征定位后缓存 */
    @Volatile
    private var renderTypesField: Field? = null

    /** FilterHandler 内表示「当前滤镜」的 String 字段（初始值 "Mznone"），按特征定位后缓存 */
    @Volatile
    private var currentFilterField: Field? = null

    @Volatile
    private var getMCamModuleMethod: Method? = null

    @Volatile
    private var getMFilterHandlerMethod: Method? = null

    @Volatile
    private var getMUIMethod: Method? = null

    @Volatile
    private var getActivityMethod: Method? = null

    @Volatile
    private var setRenderTypeMethod: Method? = null

    /** 上次已落盘的滤镜名，避免面板打开同步时重复写文件 */
    @Volatile
    private var lastRecorded: String? = null

    /** 定位字段/方法失败只告警一次，避免刷日志 */
    @Volatile
    private var locateFailedLogged = false

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        if (lpparam.packageName != PACKAGE_NAME) return

        hookRecord(lpparam)
        hookRestore(lpparam)
    }

    // ---------------------------------------------------------------- 记录

    private fun hookRecord(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLASS_FILTER_ADAPTER, lpparam.classLoader)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "setSelIndex" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (method == null) {
                Logger.w(TAG, "未找到 FilterAdapter.setSelIndex，记录功能降级")
                return
            }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val index = param.args[0] as? Int ?: return
                        val name = resolveRenderType(param.thisObject, index) ?: return
                        // 「我的滤镜」列表国内版首位是管理入口项（"null"），不是可选滤镜；
                        // Mznone（原图）是有效选择，需要记录，否则选原图重启后旧滤镜会回来
                        if (name == "null") return
                        saveFilter(name)
                    } catch (e: Throwable) {
                        Logger.e(TAG, "记录滤镜失败", e)
                    }
                }
            })
            Logger.i(TAG, "滤镜记录 Hook 已挂载")
        } catch (e: Throwable) {
            Logger.e(TAG, "滤镜记录 Hook 挂载失败", e)
        }
    }

    /** 从 FilterAdapter 实例上按特征定位滤镜名列表，再取选中项对应的名字 */
    private fun resolveRenderType(adapter: Any, index: Int): String? {
        var field = renderTypesField
        if (field == null || field.declaringClass != adapter.javaClass) {
            field = adapter.javaClass.declaredFields.firstOrNull { f ->
                f.type == ArrayList::class.java && !Modifier.isStatic(f.modifiers) && run {
                    f.isAccessible = true
                    val v = f.get(adapter) as? ArrayList<*>
                    v?.contains(RENDER_NONE) == true
                }
            } ?: return logLocateFailureOnce("FilterAdapter 滤镜列表字段")
            renderTypesField = field
        }
        field.isAccessible = true
        val list = field.get(adapter) as? ArrayList<*> ?: return null
        if (index < 0 || index >= list.size) return null
        return list[index] as? String
    }

    private fun saveFilter(name: String) {
        if (name == lastRecorded) return
        val ctx = appContext ?: return
        try {
            File(ctx.filesDir, STORE_FILE).writeText(name)
            lastRecorded = name
            Logger.i(TAG, "已记录滤镜: $name")
        } catch (e: Throwable) {
            Logger.e(TAG, "写入滤镜记录失败", e)
        }
    }

    private fun readFilter(): String? {
        val ctx = appContext ?: return null
        return try {
            val file = File(ctx.filesDir, STORE_FILE)
            if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (e: Throwable) {
            Logger.e(TAG, "读取滤镜记录失败", e)
            null
        }
    }

    // ---------------------------------------------------------------- 恢复

    private fun hookRestore(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CLASS_CONTROLLER_IMPL, lpparam.classLoader)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "restoreFilterEffect" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            if (method == null) {
                Logger.w(TAG, "未找到 MzCamControllerImpl.restoreFilterEffect，恢复功能降级")
                return
            }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        applySavedFilter(param.thisObject)
                    } catch (e: Throwable) {
                        Logger.e(TAG, "恢复滤镜失败", e)
                    }
                }
            })
            Logger.i(TAG, "滤镜恢复 Hook 已挂载")
        } catch (e: Throwable) {
            Logger.e(TAG, "滤镜恢复 Hook 挂载失败", e)
        }
    }

    private fun applySavedFilter(controller: Any) {
        val module = invokeCached(controller, "getMCamModule") ?: run {
            logLocateFailureOnce("getMCamModule")
            return
        }
        val handler = invokeCached(module, "getMFilterHandler") ?: return
        cacheContext(module)

        val saved = readFilter() ?: return
        if (saved == RENDER_NONE) return

        val field = locateCurrentFilterField(handler) ?: return
        // 当前滤镜仍为原图时才补挂；非原图说明系统会话恢复已生效或用户已选其它滤镜
        if (field.get(handler) != RENDER_NONE) return

        val mui = invokeCached(module, "getMUI") ?: run {
            logLocateFailureOnce("getMUI")
            return
        }
        applyOnMainThread(mui, saved)
        field.set(handler, saved)
        Logger.i(TAG, "已恢复滤镜: $saved")
    }

    /** FilterHandler 内初始值为 "Mznone" 的 String 字段即「当前滤镜」字段 */
    private fun locateCurrentFilterField(handler: Any): Field? {
        currentFilterField?.let {
            if (it.declaringClass == handler.javaClass) return it
        }
        val field = handler.javaClass.declaredFields.firstOrNull { f ->
            f.type == String::class.java && !Modifier.isStatic(f.modifiers) && run {
                f.isAccessible = true
                f.get(handler) == RENDER_NONE
            }
        } ?: return logLocateFailureOnce("FilterHandler 当前滤镜字段")
        currentFilterField = field
        return field
    }

    /** 预览启动可能在非主线程，setRenderType 涉及 UI 渲染统一切主线程 */
    private fun applyOnMainThread(mui: Any, saved: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invokeSetRenderType(mui, saved)
        } else {
            Handler(Looper.getMainLooper()).post { invokeSetRenderType(mui, saved) }
        }
    }

    private fun invokeSetRenderType(mui: Any, saved: String) {
        try {
            var method = setRenderTypeMethod
            if (method == null || method.declaringClass != mui.javaClass) {
                method = mui.javaClass.methods.firstOrNull {
                    it.name == "setRenderType" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
                } ?: run {
                    logLocateFailureOnce("MzCamUI.setRenderType")
                    return
                }
                setRenderTypeMethod = method
            }
            method.invoke(mui, saved)
        } catch (e: Throwable) {
            Logger.e(TAG, "调用 setRenderType 恢复滤镜失败", e)
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun cacheContext(module: Any) {
        if (appContext != null) return
        val activity = invokeCached(module, "getActivity") as? Context ?: return
        appContext = activity.applicationContext
    }

    private fun invokeCached(target: Any, methodName: String): Any? {
        val method = when (methodName) {
            "getMCamModule" -> getMCamModuleMethod
            "getMFilterHandler" -> getMFilterHandlerMethod
            "getMUI" -> getMUIMethod
            "getActivity" -> getActivityMethod
            else -> null
        }?.takeIf { it.declaringClass == target.javaClass || target.javaClass.isAssignableFrom(it.declaringClass) }
            ?: target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?: return null

        when (methodName) {
            "getMCamModule" -> getMCamModuleMethod = method
            "getMFilterHandler" -> getMFilterHandlerMethod = method
            "getMUI" -> getMUIMethod = method
            "getActivity" -> getActivityMethod = method
        }
        return try {
            method.invoke(target)
        } catch (e: Throwable) {
            Logger.e(TAG, "反射调用 $methodName 失败", e)
            null
        }
    }

    private fun logLocateFailureOnce(what: String): Nothing? {
        if (!locateFailedLogged) {
            locateFailedLogged = true
            Logger.w(TAG, "未定位到 $what，滤镜记忆部分降级")
        }
        return null
    }
}

package com.karen.flymetool.hook.feature.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.method.TransformationMethod
import android.view.View
import android.widget.TextView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.hook.base.XposedPrefs
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.Executors

object ShowWifiPasswordHook : FeatureHook {
    private const val TAG = "ShowWifiPassword"
    private const val CACHE_TTL_MS = 5_000L
    private const val KEY_MGMT_WPA2_PSK = 4
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "WifiPasswordReader").apply { isDaemon = true }
    }
    private val entryFields = mutableMapOf<Class<*>, List<Field>>()
    private val configMethods = mutableMapOf<Class<*>, Method?>()
    private val rows = WeakHashMap<TextView, Row>()
    private var cache = emptyMap<Identity, Credential>()
    private var cacheTime = 0L
    private var generation = 0L
    private var loading = false
    private var monitoringContext: Context? = null
    private var registered = false
    private var warned = false
    private val diagnostics = mutableSetOf<String>()

    private data class Identity(val networkId: Int, val ssid: String, val security: Int)
    private data class Credential(val identity: Identity, val password: String)
    private class Row(
        val preference: WeakReference<Any>,
        val summary: CharSequence,
        val visibility: Int,
        val singleLine: Boolean,
        val horizontallyScrolling: Boolean,
        val minLines: Int,
        val maxLines: Int,
        val minHeight: Int,
        val maxHeight: Int,
        val ellipsize: TextUtils.TruncateAt?,
        val transformation: TransformationMethod?,
        var rendered: CharSequence? = null
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidate()
            refresh(context)
        }
    }
    private val pulse = object : Runnable {
        override fun run() {
            val context = monitoringContext ?: return
            if (rows.keys.none { it.isAttachedToWindow }) {
                stopMonitoring()
                return
            }
            if (rows.keys.none { it.windowVisibility == View.VISIBLE && it.isShown }) {
                invalidate()
                main.postDelayed(this, 1_000L)
                return
            }
            if (!loading && SystemClock.elapsedRealtime() - cacheTime >= CACHE_TTL_MS) {
                invalidate()
                refresh(context)
            }
            renderRows()
            main.postDelayed(this, 1_000L)
        }
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            startMonitoring(view.context.applicationContext)
            renderRows()
        }
        override fun onViewDetachedFromWindow(view: View) {
            if (view is TextView) rows[view]?.let { restore(view, it) }
        }
    }

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.settings" && ctx.packageName != "com.meizu.connectivitysettings") return
        if (!XposedPrefs.isFeatureEnabled("com.android.settings", "show_wifi_password")) return
        try {
            if (ctx.packageName == "com.meizu.connectivitysettings") {
                hookConnectivity(ctx)
                return
            }
            val preferenceClass = Reflect.findClass("androidx.preference.Preference", ctx.classLoader)
            val holderClass = Reflect.findClass("androidx.preference.PreferenceViewHolder", ctx.classLoader)
            val findView = holderClass.getMethod("findViewById", Int::class.javaPrimitiveType)
            Reflect.hookMethodOn(ctx.api, preferenceClass, "onBindViewHolder", holderClass) { chain ->
                val summary = runCatching {
                    findView.invoke(chain.getArgs()[0], android.R.id.summary) as? TextView
                }.getOrNull()
                if (Looper.myLooper() == Looper.getMainLooper() && summary != null) {
                    rows.remove(summary)?.let { restore(summary, it) }
                    summary.removeOnAttachStateChangeListener(attachment)
                }
                val result = chain.proceed()
                if (Looper.myLooper() == Looper.getMainLooper() && summary != null) {
                    try {
                        val preference = chain.getThisObject()
                        if (preference != null && configuration(preference) != null) {
                            val row = snapshot(summary, preference)
                            rows[summary] = row
                            summary.addOnAttachStateChangeListener(attachment)
                            if (summary.isAttachedToWindow) startMonitoring(summary.context.applicationContext)
                            render(summary, row)
                        }
                    } catch (_: Throwable) {
                        // 布局或条目签名不匹配时保留原生绑定结果。
                    }
                }
                result
            }
            Logger.i(TAG, "已挂载 Wi-Fi 列表摘要显示")
        } catch (error: Throwable) {
            Logger.e(TAG, "挂载失败", error)
        }
    }

    private fun hookConnectivity(ctx: HookContext) {
        val adapterClass = Reflect.findClass("com.meizu.connectivitysettings.wifi.AccessPointAdapter", ctx.classLoader)
        val recyclerAdapter = Reflect.findClass("androidx.recyclerview.widget.RecyclerView\$Adapter", ctx.classLoader)
        val holderClass = Reflect.findClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder", ctx.classLoader)
        if (!recyclerAdapter.isAssignableFrom(adapterClass)) {
            Logger.w(TAG, "连接设置适配器不符合 RecyclerView 父类特征")
            return
        }
        val bind = adapterClass.declaredMethods.singleOrNull {
            it.name == "onBindViewHolder" && !Modifier.isStatic(it.modifiers) &&
                !it.isBridge && !it.isSynthetic && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(holderClass, Int::class.javaPrimitiveType))
        }
        val itemViewField = holderClass.fields.firstOrNull {
            it.name == "itemView" && !Modifier.isStatic(it.modifiers) && it.type == View::class.java
        } ?: holderClass.fields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == View::class.java
        }.singleOrNull()
        if (bind == null || itemViewField == null) {
            Logger.w(TAG, "连接设置绑定方法或条目视图字段不符合特征")
            return
        }
        Reflect.hookMethod(ctx.api, bind) { chain ->
            val itemView = runCatching { itemViewField.get(chain.getArgs()[0]) as? View }.getOrNull()
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            if (onMain && itemView != null) {
                runCatching {
                    connectivitySummary(itemView)?.let { summary ->
                        rows.remove(summary)?.let { restore(summary, it) }
                        summary.removeOnAttachStateChangeListener(attachment)
                    }
                }.onFailure { diagnose("restore", "连接设置旧摘要恢复失败，保留原生绑定") }
            }
            val result = chain.proceed()
            if (onMain && itemView != null) {
                try {
                    val summary = connectivitySummary(itemView)
                    val accessPoint = itemView.tag
                    when {
                        summary == null -> diagnose("summary", "连接设置条目未命中 wifi_ap_summary 文本视图")
                        accessPoint == null || configMethod(accessPoint.javaClass) == null ->
                            diagnose("tag", "连接设置条目标签未命中唯一配置返回方法")
                        else -> {
                            if (diagnostics.add("bound")) Logger.i(TAG, "已命中连接设置 Wi-Fi 条目及配置读取特征")
                            val row = snapshot(summary, accessPoint)
                            rows[summary] = row
                            summary.addOnAttachStateChangeListener(attachment)
                            if (summary.isAttachedToWindow) startMonitoring(summary.context.applicationContext)
                            render(summary, row)
                        }
                    }
                } catch (_: Throwable) {
                    diagnose("bind", "连接设置摘要处理失败，保留原生绑定结果")
                }
            } else if (itemView == null) {
                diagnose("item", "连接设置绑定未取得条目视图")
            }
            result
        }
        Logger.i(TAG, "已挂载连接设置 Wi-Fi 列表摘要显示")
    }

    private fun connectivitySummary(itemView: View): TextView? {
        val id = itemView.resources.getIdentifier("wifi_ap_summary", "id", "com.meizu.connectivitysettings")
        return if (id == 0) null else itemView.findViewById<View>(id) as? TextView
    }

    private fun diagnose(key: String, message: String) {
        if (diagnostics.add(key)) Logger.w(TAG, message)
    }

    private fun snapshot(view: TextView, preference: Any): Row = Row(
        WeakReference(preference), TextUtils.stringOrSpannedString(view.text), view.visibility,
        view.isSingleLine, view.isHorizontallyScrollable, view.minLines, view.maxLines,
        view.minHeight, view.maxHeight, view.ellipsize, view.transformationMethod
    )

    private fun configuration(preference: Any): WifiConfiguration? {
        configMethod(preference.javaClass)?.let { method ->
            (method.invoke(preference) as? WifiConfiguration)?.let { return it }
        }
        val fields = entryFields.getOrPut(preference.javaClass) {
            generateSequence<Class<*>>(preference.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { field ->
                    !Modifier.isStatic(field.modifiers) && configMethod(field.type) != null &&
                        field.type.methods.any { method ->
                            method.returnType == String::class.java &&
                                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                        }
                }.onEach { it.isAccessible = true }.toList()
        }
        val configurations = fields.mapNotNull { field ->
            val entry = field.get(preference) ?: return@mapNotNull null
            configMethod(entry.javaClass)?.invoke(entry) as? WifiConfiguration
        }
        return configurations.singleOrNull()
    }

    private fun configMethod(type: Class<*>): Method? {
        if (!configMethods.containsKey(type)) {
            configMethods[type] = type.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == WifiConfiguration::class.java && !it.isBridge && !it.isSynthetic
            }.singleOrNull()?.apply { isAccessible = true }
        }
        return configMethods[type]
    }

    private fun identity(config: WifiConfiguration): Identity? {
        if (config.networkId < 0 || config.isPasspoint) return null
        val ssid = unquote(config.SSID ?: return null)
        if (ssid.isEmpty() || ssid == "<unknown ssid>") return null
        val keys = config.allowedKeyManagement ?: return null
        var security = 0
        for (key in 0 until keys.length()) {
            if (!keys[key]) continue
            when (key) {
                WifiConfiguration.KeyMgmt.NONE -> Unit
                WifiConfiguration.KeyMgmt.WPA_PSK, KEY_MGMT_WPA2_PSK -> security = security or 1
                WifiConfiguration.KeyMgmt.SAE -> security = security or 2
                else -> return null
            }
        }
        if (security == 0) {
            if (config.wepKeys?.any { !it.isNullOrEmpty() } != true) return null
            security = 4
        }
        return Identity(config.networkId, ssid, security)
    }

    private fun password(config: WifiConfiguration, identity: Identity): String? {
        val raw = if (identity.security == 4) {
            config.wepKeys?.getOrNull(config.wepTxKeyIndex)
        } else {
            config.preSharedKey
        } ?: return null
        val value = unquote(raw)
        return value.takeIf { it.isNotEmpty() && !it.all { character -> character == '*' } }
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else value

    private fun startMonitoring(context: Context) {
        if (monitoringContext != null) return
        monitoringContext = context
        if (diagnostics.add("monitor")) Logger.i(TAG, "已启动可见条目凭据查询")
        try {
            val filter = IntentFilter().apply {
                addAction("android.net.wifi.CONFIGURED_NETWORKS_CHANGE")
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (_: Throwable) {
            warnUnavailable()
        }
        refresh(context)
        main.removeCallbacks(pulse)
        main.postDelayed(pulse, 1_000L)
    }

    private fun stopMonitoring() {
        val context = monitoringContext
        monitoringContext = null
        if (registered && context != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        registered = false
        main.removeCallbacks(pulse)
        invalidate()
    }

    private fun invalidate() {
        generation++
        cache = emptyMap()
        cacheTime = SystemClock.elapsedRealtime()
        renderRows()
    }

    private fun refresh(context: Context) {
        if (loading || monitoringContext == null) return
        loading = true
        val requestedGeneration = generation
        worker.execute {
            var failed = false
            val loaded = try {
                val manager = context.getSystemService(WifiManager::class.java)
                val configurations = WifiManager::class.java.getMethod("getPrivilegedConfiguredNetworks")
                    .invoke(manager) as? List<*>
                val configs = configurations.orEmpty().filterIsInstance<WifiConfiguration>()
                val credentials = configs.mapNotNull { config ->
                    val identity = identity(config) ?: return@mapNotNull null
                    val password = password(config, identity) ?: return@mapNotNull null
                    Credential(identity, password)
                }
                credentials.groupBy { it.identity }.mapNotNull { (identity, values) ->
                    values.distinctBy { it.password }.singleOrNull()?.let { identity to it }
                }.toMap()
            } catch (_: Throwable) {
                failed = true
                emptyMap()
            }
            main.post {
                loading = false
                if (monitoringContext == null) return@post
                if (requestedGeneration != generation) {
                    refresh(monitoringContext ?: return@post)
                    return@post
                }
                if (diagnostics.add("loaded")) Logger.w(TAG, "凭据查询完成", "count" to loaded.size, "failed" to failed)
                cache = loaded
                cacheTime = SystemClock.elapsedRealtime()
                if (failed) warnUnavailable()
                renderRows()
            }
        }
    }

    private fun renderRows() {
        rows.entries.toList().forEach { (view, row) -> render(view, row) }
    }

    private fun render(view: TextView, row: Row) {
        try {
            if (!view.isAttachedToWindow) return
            val preference = row.preference.get()
            val identity = preference?.let { configuration(it) }?.let { identity(it) }
            val credential = identity?.let { cache[it] }?.takeIf {
                it.identity == identity && SystemClock.elapsedRealtime() - cacheTime < CACHE_TTL_MS
            }
            if (credential == null) {
                if (cache.isNotEmpty() && diagnostics.add("unmatched")) Logger.w(TAG, "条目配置匹配诊断", "identity" to (identity != null), "identityMatch" to (identity in cache), "security" to identity?.security)
                restore(view, row)
                return
            }
            if (row.rendered != null && !TextUtils.equals(view.text, row.rendered)) return
            val nativeSummary = row.summary.takeIf { row.visibility == View.VISIBLE && it.isNotEmpty() }
            val text = TextUtils.concat(nativeSummary ?: "", if (nativeSummary == null) "" else "\n", "密码：", credential.password)
            view.setSingleLine(false)
            view.setHorizontallyScrolling(false)
            view.maxLines = Int.MAX_VALUE
            view.ellipsize = null
            view.transformationMethod = null
            view.text = text
            view.visibility = View.VISIBLE
            row.rendered = text
        } catch (_: Throwable) {
            restore(view, row)
        }
    }

    private fun restore(view: TextView, row: Row) {
        if (row.rendered != null) {
            if (TextUtils.equals(view.text, row.rendered)) {
                view.text = row.summary
                view.visibility = row.visibility
            }
            view.setSingleLine(row.singleLine)
            view.setHorizontallyScrolling(row.horizontallyScrolling)
            if (row.minLines >= 0) view.minLines = row.minLines else view.minHeight = row.minHeight
            if (row.maxLines >= 0) view.maxLines = row.maxLines else view.maxHeight = row.maxHeight
            view.ellipsize = row.ellipsize
            view.transformationMethod = row.transformation
        }
        row.rendered = null
    }

    private fun warnUnavailable() {
        if (warned) return
        warned = true
        Logger.w(TAG, "Wi-Fi 凭据读取或变更监听不可用，将保留原摘要并定时重试")
    }
}

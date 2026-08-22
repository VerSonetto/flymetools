package com.karen.flymetool.hook.feature.systemuieditor

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 为经典时钟所使用的 legacy 锁屏壁纸工具栏补上景深入口。
 *
 * 目标布局由公开资源名定位。景深结果沿用编辑器内置的 MattingHelper，输出在锁屏壁纸
 * 同目录，供 SystemUI 的经典时钟前景层读取；不依赖编辑器的混淆 ViewModel 或方法名。
 */
object ClassicClockDepthHook : FeatureHook {

    private const val TAG = "ClassicClockDepth"
    private const val FEATURE_KEY = "classic_clock_depth"
    private const val LEGACY_BUTTON_LAYOUT = "editor_lockscreen_button_photo_wp_legacy_sysui"
    private const val SETTING_KEY = "flymetool_classic_clock_dof_data"
    private const val BUTTON_TAG = "flymetool:classic-clock-dof"
    private const val ACTION_WALLPAPER_CHANGED = "android.intent.action.WALLPAPER_CHANGED"
    private const val MATTING_HELPER = "com.meizu.algorithm.wallpapermatting.MattingHelper"
    private const val MATTING_MODEL = "/system/media/models/imagematting/matting.mnn"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FlymeToolClassicDof").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewCutouts = WeakHashMap<ViewGroup, PreviewCutoutState>()
    private val previewRoots = WeakHashMap<View, Unit>()
    private var wallpaperChangedReceiver: BroadcastReceiver? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        try {
            XposedBridge.hookAllMethods(
                LayoutInflater::class.java,
                "inflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val layoutId = param.args.firstOrNull() as? Int ?: return
                        val root = param.result as? View ?: return
                        if (!isLegacyButtonLayout(root.context, layoutId)) return
                        installWallpaperChangedReceiver(root.context.applicationContext)
                        addDepthButton(root)
                    }
                },
            )
            Logger.i(TAG, "已挂载经典时钟景深按钮")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载经典时钟景深按钮失败", e)
        }
    }

    private fun isLegacyButtonLayout(context: Context, layoutId: Int): Boolean = try {
        context.resources.getResourceEntryName(layoutId) == LEGACY_BUTTON_LAYOUT
    } catch (_: Throwable) {
        false
    }

    private fun addDepthButton(root: View) {
        try {
            val container = root as? LinearLayout ?: return
            if (container.findViewWithTag<View>(BUTTON_TAG) != null) return

            val context = container.context
            val button = LinearLayout(context).apply {
                tag = BUTTON_TAG
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }
            val iconSize = dimension(context, "editor_bottom_button_size", 48)
            val iconMargin = dimension(context, "editor_button_margin_horizontal_normal", 18)
            val icon = ImageView(context).apply {
                background = drawable(context, "editor_btn_bg_selector")
                setImageDrawable(drawable(context, "ic_depth_of_field"))
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginStart = iconMargin
                    marginEnd = iconMargin
                }
            }
            val label = TextView(context).apply {
                text = string(context, "depth_of_field", "景深")
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimension(context, "editor_button_label_margin_top", 4) }
            }
            button.addView(icon)
            button.addView(label)
            button.setOnClickListener { toggleDepth(context, button, label) }
            container.addView(button)
            previewRoots[root.rootView] = Unit
            updateButtonState(context, button, label)
            // 经典样式不属于编辑器原生景深的四种样式。遗留的 is_dof_enabled 会让
            // 编辑器等待它永远不会创建的原生抠图任务，从而一直显示“处理中”。
            clearLegacyDofFlagAsync(context)
            migrateMaskToSharedStorageAsync(context, root.rootView)
            regenerateForCurrentWallpaperAsync(context, 0)
            root.rootView.post { updateEditorPreview(root.rootView) }
        } catch (e: Throwable) {
            Logger.e(TAG, "添加经典时钟景深按钮失败", e)
        }
    }

    private fun toggleDepth(context: Context, button: View, label: TextView) {
        if (!button.isEnabled) return
        button.isEnabled = false
        executor.execute {
            try {
                val current = readConfig(context)
                if (current?.optBoolean("enabled") == true) {
                    saveConfig(context, false, null, null)
                    clearLegacyDofFlag(context, current.optString("group_id"))
                    onMain {
                        toast(context, "已关闭经典时钟景深")
                        updateButtonState(context, button, label)
                        updateEditorPreview(button.rootView)
                    }
                    return@execute
                }

                onMain { label.text = string(context, "dof_calculating", "景深计算中") }
                val source = queryCurrentLockscreenImage(context)
                    ?: throw IllegalStateException("未找到当前已应用的锁屏壁纸")
                val wallpaperId = currentLockscreenWallpaperId(context)
                if (wallpaperId <= 0) throw IllegalStateException("无法读取当前锁屏壁纸 ID")
                val mask = createMask(context, source.imagePath, wallpaperId.toString())
                    ?: throw IllegalStateException("当前照片不支持景深效果")
                saveConfig(context, true, source.groupId, mask.absolutePath, wallpaperId, source.imagePath)
                clearLegacyDofFlag(context, source.groupId)
                onMain {
                    toast(context, "经典时钟景深已开启")
                    updateButtonState(context, button, label)
                    updateEditorPreview(button.rootView)
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "生成经典时钟景深失败", e)
                onMain {
                    toast(context, string(context, "depth_of_field_unsupported", "当前照片不支持景深效果"))
                    updateButtonState(context, button, label)
                }
            } finally {
                onMain { button.isEnabled = true }
            }
        }
    }

    /**
     * 锁屏预览图就是编辑器已经处理完成、并写入 WallpaperManager 的成品。
     * 原始图可能还会经过裁剪、滤镜等步骤，不能直接拿来与锁屏底图对齐。
     */
    private fun queryCurrentLockscreenImage(context: Context): WallpaperSource? {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath("app.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        database.use { db ->
            db.rawQuery(
                """
                    SELECT p.group_id, p.lockscreen_preview_path, p.lockscreen_image_path
                    FROM photo_wallpaper p
                    INNER JOIN apply_history h ON h.group_id = p.group_id
                    ORDER BY h.apply_time DESC
                    LIMIT 1
                """.trimIndent(),
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val groupId = cursor.getString(0) ?: return null
                val previewPath = cursor.getString(1)
                val sourcePath = cursor.getString(2)
                val imagePath = previewPath?.takeIf { File(it).isFile }
                    ?: sourcePath?.takeIf { File(it).isFile }
                    ?: return null
                return WallpaperSource(groupId, imagePath)
            }
        }
    }

    private fun createMask(context: Context, imagePath: String, cacheKey: String): File? {
        val source = BitmapFactory.decodeFile(imagePath)
            ?: throw IllegalStateException("无法读取锁屏壁纸: $imagePath")
        try {
            val helper = Class.forName(MATTING_HELPER, true, context.classLoader)
            XposedHelpers.callStaticMethod(helper, "init", MATTING_MODEL)
            val result = XposedHelpers.callStaticMethod(helper, "matting", source)
                ?: return null
            val mask = XposedHelpers.callMethod(result, "getResultBitmap") as? Bitmap ?: return null
            try {
                val outputDirectory = sharedOutputDirectory()
                val output = File(
                    outputDirectory,
                    "flymetool_classic_dof_$cacheKey.png",
                )
                FileOutputStream(output).use { stream ->
                    if (!mask.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IllegalStateException("无法写入景深前景图")
                    }
                }
                output.setReadable(true, false)
                output.parentFile?.setReadable(true, false)
                return output
            } finally {
                if (mask !== source && !mask.isRecycled) mask.recycle()
            }
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun clearLegacyDofFlagAsync(context: Context) {
        executor.execute {
            clearLegacyDofFlag(context, readConfig(context)?.optString("group_id").orEmpty())
        }
    }

    private fun clearLegacyDofFlag(context: Context, groupId: String) {
        if (groupId.isBlank()) return
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("app.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.update(
                "sysui_legacy",
                ContentValues().apply {
                    put("is_dof_enabled", 0)
                    put("lockscreen_last_modified_time", System.currentTimeMillis())
                },
                "group_id = ?",
                arrayOf(groupId),
            )
        }
    }

    /** 将旧版存于编辑器私有目录的结果迁移到 SystemUI 可读取的共享媒体目录。 */
    private fun migrateMaskToSharedStorageAsync(context: Context, root: View) {
        executor.execute {
            try {
                val config = readConfig(context) ?: return@execute
                if (!config.optBoolean("enabled")) return@execute
                val source = File(config.optString("mask_path"))
                if (!source.isFile || source.absolutePath.startsWith(Environment.getExternalStorageDirectory().path)) {
                    return@execute
                }
                val outputDirectory = sharedOutputDirectory()
                val output = File(outputDirectory, source.name)
                source.inputStream().use { input ->
                    FileOutputStream(output).use(input::copyTo)
                }
                output.setReadable(true, false)
                outputDirectory.setReadable(true, false)
                saveConfig(
                    context,
                    true,
                    config.optString("group_id"),
                    output.absolutePath,
                    config.optInt("wallpaper_id", -1),
                    config.optString("source_image_path"),
                )
                onMain { updateEditorPreview(root) }
                Logger.i(TAG, "已将经典时钟景深前景图迁移到共享媒体目录")
            } catch (e: Throwable) {
                Logger.e(TAG, "迁移经典时钟景深前景图失败", e)
            }
        }
    }

    private fun readConfig(context: Context): JSONObject? = try {
        Settings.Secure.getString(context.contentResolver, SETTING_KEY)
            ?.let(::JSONObject)
    } catch (_: Throwable) {
        null
    }

    private fun saveConfig(
        context: Context,
        enabled: Boolean,
        groupId: String?,
        maskPath: String?,
        wallpaperId: Int = -1,
        sourceImagePath: String? = null,
    ) {
        val config = JSONObject().apply {
            put("enabled", enabled)
            put("group_id", groupId)
            put("mask_path", maskPath)
            put("wallpaper_id", wallpaperId)
            put("source_image_path", sourceImagePath)
        }
        if (!Settings.Secure.putString(context.contentResolver, SETTING_KEY, config.toString())) {
            throw IllegalStateException("无法保存景深状态")
        }
    }

    private fun updateButtonState(context: Context, button: View, label: TextView) {
        val enabled = readConfig(context)?.optBoolean("enabled") == true
        button.alpha = if (enabled) 1f else 0.72f
        label.text = string(context, "depth_of_field", "景深")
    }

    private fun installWallpaperChangedReceiver(context: Context?) {
        if (context == null || wallpaperChangedReceiver != null) return
        try {
            val appContext = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action == ACTION_WALLPAPER_CHANGED) {
                        // 系统先发送广播、再完成锁屏图文件替换的机型上，稍后读取才能确保拿到新图。
                        regenerateForCurrentWallpaperAsync(appContext, 700, retryWhenUnchanged = true)
                    }
                }
            }
            appContext.registerReceiver(
                receiver,
                IntentFilter(ACTION_WALLPAPER_CHANGED),
                // 此广播由 system_server 发出；NOT_EXPORTED 会在 Android 13+ 拦截非本 UID 的系统发送者。
                Context.RECEIVER_EXPORTED,
            )
            wallpaperChangedReceiver = receiver
            Logger.i(TAG, "已监听锁屏壁纸变更")
        } catch (e: Throwable) {
            Logger.e(TAG, "监听锁屏壁纸变更失败", e)
        }
    }

    /**
     * 在壁纸应用完成后重新抠图。ID 已一致时不重复计算；否则绝不继续沿用旧前景。
     */
    private fun regenerateForCurrentWallpaperAsync(
        context: Context,
        delayMillis: Long,
        retryCount: Int = 0,
        retryWhenUnchanged: Boolean = false,
    ) {
        mainHandler.postDelayed({
            executor.execute {
                try {
                    val config = readConfig(context) ?: return@execute
                    if (!config.optBoolean("enabled")) return@execute
                    val source = queryCurrentLockscreenImage(context) ?: return@execute
                    val wallpaperId = currentLockscreenWallpaperId(context)
                    val hasBoundSource = config.optString("source_image_path").isNotBlank()
                    if (
                        wallpaperId <= 0 ||
                        (hasBoundSource && config.optInt("wallpaper_id", -1) == wallpaperId)
                    ) {
                        return@execute
                    }
                    // 只有数据库已切到新锁屏成品时才生成。否则广播可能早于数据库提交，
                    // 不能把上一次图片错误绑定到新的壁纸 ID。
                    if (config.optString("source_image_path") == source.imagePath) {
                        if (retryWhenUnchanged && retryCount < 5) {
                            regenerateForCurrentWallpaperAsync(
                                context,
                                700,
                                retryCount + 1,
                                retryWhenUnchanged = true,
                            )
                        }
                        return@execute
                    }
                    val mask = createMask(context, source.imagePath, wallpaperId.toString())
                        ?: throw IllegalStateException("当前锁屏壁纸不支持景深效果")
                    saveConfig(context, true, source.groupId, mask.absolutePath, wallpaperId, source.imagePath)
                    clearLegacyDofFlag(context, source.groupId)
                    onMain { refreshAllEditorPreviews() }
                    Logger.i(TAG, "已按新锁屏壁纸重新生成景深抠图")
                } catch (e: Throwable) {
                    // 不写入旧 ID；SystemUI 的 ID 校验会保持景深关闭，直到下次生成成功。
                    Logger.e(TAG, "按新锁屏壁纸重新生成景深抠图失败", e)
                }
            }
        }, delayMillis)
    }

    private fun refreshAllEditorPreviews() {
        previewRoots.keys.toList().forEach(::updateEditorPreview)
    }

    private fun sharedOutputDirectory(): File {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "FlymeTool",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建景深前景图目录")
        }
        return directory
    }

    /** 编辑器预览与锁屏一致：抠图只从时钟挖空，不额外绘制一个会重影的前景。 */
    private fun updateEditorPreview(root: View) {
        try {
            val hostId = root.resources.getIdentifier(
                "sysui_legacy_lockscreen_preview_container",
                "id",
                root.context.packageName,
            )
            val host = root.findViewById<ViewGroup>(hostId) ?: return
            removePreviewCutout(host)
            val config = readConfig(host.context) ?: return
            if (!config.optBoolean("enabled")) return
            if (config.optInt("wallpaper_id", -1) != currentLockscreenWallpaperId(host.context)) return
            val path = config.optString("mask_path").takeIf { it.isNotBlank() } ?: return
            val bitmap = BitmapFactory.decodeFile(path) ?: return
            val clockId = root.resources.getIdentifier(
                "layout_lockscreen_clock",
                "id",
                root.context.packageName,
            )
            val clock = host.findViewById<View>(clockId) ?: return
            val cutout = PreviewDofCutoutView(host.context, host, clock, bitmap)
            val previousLayerType = host.layerType
            if (previousLayerType != View.LAYER_TYPE_HARDWARE) {
                host.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            host.addView(
                cutout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            previewCutouts[host] = PreviewCutoutState(cutout, previousLayerType)
        } catch (e: Throwable) {
            Logger.e(TAG, "更新经典时钟景深预览失败", e)
        }
    }

    private fun removePreviewCutout(host: ViewGroup) {
        val state = previewCutouts.remove(host) ?: return
        host.removeView(state.cutout)
        state.cutout.recycle()
        if (host.layerType != state.previousLayerType) {
            host.setLayerType(state.previousLayerType, null)
        }
    }

    private fun dimension(context: Context, name: String, fallbackDp: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", context.packageName)
        return if (id != 0) context.resources.getDimensionPixelSize(id)
        else (fallbackDp * context.resources.displayMetrics.density).toInt()
    }

    private fun drawable(context: Context, name: String): Drawable? {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id == 0) null else context.getDrawable(id)
    }

    private fun string(context: Context, name: String, fallback: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id == 0) fallback else context.getString(id)
    }

    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun onMain(action: () -> Unit) = mainHandler.post(action)

    private data class PreviewCutoutState(
        val cutout: PreviewDofCutoutView,
        val previousLayerType: Int,
    )

    private class PreviewDofCutoutView(
        context: Context,
        private val host: ViewGroup,
        private val clock: View,
        private val bitmap: Bitmap,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        private val hostLocation = IntArray(2)
        private val clockLocation = IntArray(2)
        private val destination = Rect()

        init {
            setWillNotDraw(false)
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            if (bitmap.isRecycled || width <= 0 || height <= 0 || !clock.isShown) return
            host.getLocationOnScreen(hostLocation)
            clock.getLocationOnScreen(clockLocation)
            val scale = maxOf(
                host.width.toFloat() / bitmap.width,
                host.height.toFloat() / bitmap.height,
            )
            val imageWidth = (bitmap.width * scale).toInt()
            val imageHeight = (bitmap.height * scale).toInt()
            destination.set(
                (host.width - imageWidth) / 2,
                (host.height - imageHeight) / 2,
                (host.width + imageWidth) / 2,
                (host.height + imageHeight) / 2,
            )
            val saveCount = canvas.save()
            canvas.clipRect(
                clockLocation[0] - hostLocation[0],
                clockLocation[1] - hostLocation[1],
                clockLocation[0] - hostLocation[0] + clock.width,
                clockLocation[1] - hostLocation[1] + clock.height,
            )
            canvas.drawBitmap(bitmap, null, destination, paint)
            canvas.restoreToCount(saveCount)
        }

        fun recycle() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun currentLockscreenWallpaperId(context: Context): Int = try {
        WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK)
    } catch (_: Throwable) {
        -1
    }

    private data class WallpaperSource(val groupId: String, val imagePath: String)
}

package com.karen.flymetool.hook.feature.systemuieditor

import android.app.Activity
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
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
import android.media.ExifInterface
import android.net.Uri
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 为经典时钟所使用的 legacy 锁屏壁纸工具栏补上景深入口。
 *
 * 目标布局由公开资源名定位。景深结果沿用编辑器内置的 MattingHelper，输出在锁屏壁纸
 * 同目录，供 SystemUI 的经典时钟前景层读取；不依赖编辑器的混淆 ViewModel 或方法名。
 * 另提供「蒙版」按钮调用系统媒体选择器，导入用户自抠的前景图，与模型结果共用配置。
 */
object ClassicClockDepthHook : FeatureHook {

    private const val TAG = "ClassicClockDepth"
    private const val FEATURE_KEY = "classic_clock_depth"
    private const val LEGACY_BUTTON_LAYOUT = "editor_lockscreen_button_photo_wp_legacy_sysui"
    private const val SETTING_KEY = "flymetool_classic_clock_dof_data"
    private const val BUTTON_TAG = "flymetool:classic-clock-dof"
    private const val BUTTON_ICON_TAG = "flymetool:classic-clock-dof-icon"
    private const val MASK_BUTTON_TAG = "flymetool:classic-clock-dof-mask"
    private const val MASK_FILE_PREFIX = "flymetool_classic_dof_"
    private const val PICK_MASK_REQUEST_CODE = 0x4644
    private const val ACTION_WALLPAPER_CHANGED = "android.intent.action.WALLPAPER_CHANGED"
    private const val PENDING_SOURCE_IMAGE_PATH = "pending_source_image_path"
    private const val MATTING_HELPER = "com.meizu.algorithm.wallpapermatting.MattingHelper"
    private const val MATTING_MODEL = "/system/media/models/imagematting/matting.mnn"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FlymeToolClassicDof").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewCutouts = WeakHashMap<ViewGroup, PreviewCutoutState>()
    private val previewRoots = WeakHashMap<View, Unit>()
    /** 每次壁纸变更都淘汰此前排队的抠图任务，避免连续应用时旧任务覆盖新结果。 */
    private val wallpaperTaskGeneration = AtomicLong()
    private var wallpaperChangedReceiver: BroadcastReceiver? = null
    private val depthButtons = WeakHashMap<LinearLayout, TextView>()
    private val resultHookedClasses = WeakHashMap<Class<*>, Unit>()
    /** 基类与具体实现类的回调可能各触发一次，用该标志保证一次选择只导入一次。 */
    private val importPending = AtomicBoolean(false)
    private val pickResultHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val requestCode = param.args.firstOrNull() as? Int ?: return
            if (requestCode != PICK_MASK_REQUEST_CODE) return
            if (param.args.getOrNull(1) as? Int != Activity.RESULT_OK) return
            val intent = param.args.getOrNull(2) as? Intent ?: return
            val uri = intent.data ?: return
            val context = (param.thisObject as? Activity)?.applicationContext ?: return
            if (!importPending.compareAndSet(false, true)) return
            executor.execute {
                try {
                    importCustomMask(context, uri)
                } finally {
                    importPending.set(false)
                }
            }
        }
    }

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
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                pickResultHook,
            )
            Logger.i(TAG, "已挂载蒙版选择结果回调")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载蒙版选择结果回调失败", e)
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
            val (button, label) = createToolbarButton(
                context,
                BUTTON_TAG,
                string(context, "depth_of_field", "景深"),
            )
            button.setOnClickListener { toggleDepth(context, button, label) }
            container.addView(button)
            depthButtons[button] = label
            // 编辑器 Activity 可能重写 onActivityResult 且不调 super，按特征补挂具体类。
            findActivity(context)?.let(::hookConcreteActivityResult)

            val (maskButton, _) = createToolbarButton(context, MASK_BUTTON_TAG, "蒙版")
            maskButton.setOnClickListener { pickCustomMask(context) }
            container.addView(maskButton)

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

    private fun createToolbarButton(
        context: Context,
        buttonTag: String,
        labelText: String,
    ): Pair<LinearLayout, TextView> {
        val button = LinearLayout(context).apply {
            tag = buttonTag
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
            tag = "$buttonTag-icon"
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
            text = labelText
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
        return button to label
    }

    private fun toggleDepth(context: Context, button: View, label: TextView) {
        if (!button.isEnabled) return
        button.isEnabled = false
        val generation = wallpaperTaskGeneration.incrementAndGet()
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
                if (wallpaperId < 0) throw IllegalStateException("无法读取当前锁屏壁纸 ID")
                val mask = createMask(context, source.imagePath, wallpaperId.toString())
                    ?: throw IllegalStateException("当前照片不支持景深效果")
                if (!isWallpaperSourceStable(context, generation, wallpaperId, source)) {
                    mask.delete()
                    throw IllegalStateException("锁屏壁纸仍在切换，请稍后重试")
                }
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

    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current !is Activity) {
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return current
    }

    private fun hookConcreteActivityResult(activity: Activity) {
        val clazz = activity.javaClass
        if (resultHookedClasses.containsKey(clazz)) return
        resultHookedClasses[clazz] = Unit
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", pickResultHook)
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载蒙版选择回调失败", e)
        }
    }

    /** 调系统媒体选择器挑选自定义蒙版，结果经 onActivityResult 回到 pickResultHook。 */
    private fun pickCustomMask(context: Context) {
        val activity = findActivity(context) ?: run {
            toast(context, "无法打开图片选择器")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            activity.startActivityForResult(intent, PICK_MASK_REQUEST_CODE)
        } catch (e: Throwable) {
            Logger.e(TAG, "打开蒙版选择器失败", e)
            toast(context, "未找到可用的图片选择器")
        }
    }

    /**
     * 导入用户自备的蒙版 PNG：校验透明通道与壁纸比例后缩放落盘，写入与模型抠图
     * 相同的配置结构，SystemUI 侧无需任何改动即可生效。
     */
    private fun importCustomMask(context: Context, uri: Uri) {
        try {
            // 使仍在排队或执行中的模型抠图任务失效，避免旧结果覆盖用户选择。
            wallpaperTaskGeneration.incrementAndGet()
            val wallpaperId = currentLockscreenWallpaperId(context)
            if (wallpaperId < 0) throw IllegalStateException("无法读取当前锁屏壁纸 ID")
            val source = queryCurrentLockscreenImage(context)
                ?: throw IllegalStateException("未找到当前已应用的锁屏壁纸")
            val wallpaperRatio = imageAspectRatio(source.imagePath)
                ?: throw IllegalStateException("无法读取当前锁屏壁纸尺寸")
            // 用户可能基于原图制作蒙版：编辑器应用时对原图居中裁剪，渲染端对蒙版做同样的
            // 居中裁剪变换，因此蒙版与原图同比例时主体同样对齐，一并放行。
            val acceptableRatios = listOfNotNull(
                wallpaperRatio,
                source.sourceImagePath?.let { imageAspectRatio(it) },
            ).filter { it > 0f }
            val decoded = decodeCustomMask(context, uri)
                ?: throw IllegalStateException("无法读取所选图片")
            var mask: Bitmap? = null
            try {
                if (!decoded.hasAlpha()) throw IllegalStateException("蒙版需为带透明背景的 PNG 图片")
                val maskRatio = decoded.width.toFloat() / decoded.height
                if (acceptableRatios.none { abs(maskRatio - it) / it <= 0.02f }) {
                    Logger.w(
                        TAG,
                        "蒙版比例不符: 蒙版=$maskRatio 可用=${acceptableRatios.joinToString(",")} 参照=${source.imagePath}",
                    )
                    throw IllegalStateException("蒙版比例需与锁屏壁纸或其原图一致")
                }
                mask = scaleBitmapToFitScreen(context, decoded)
                val output = File(
                    sharedOutputDirectory(),
                    "${MASK_FILE_PREFIX}custom_$wallpaperId.png",
                )
                FileOutputStream(output).use { stream ->
                    if (!mask.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IllegalStateException("无法写入自定义蒙版")
                    }
                }
                output.setReadable(true, false)
                output.parentFile?.setReadable(true, false)
                deleteStaleMask(readConfig(context)?.optString("mask_path"), output.absolutePath)
                saveConfig(
                    context,
                    true,
                    source.groupId,
                    output.absolutePath,
                    wallpaperId,
                    source.imagePath,
                    null,
                    true,
                )
                clearLegacyDofFlag(context, source.groupId)
                onMain {
                    toast(context, "已应用自定义景深蒙版")
                    refreshDepthButtons(context)
                    refreshAllEditorPreviews()
                }
                Logger.i(TAG, "已导入自定义景深蒙版")
            } finally {
                if (decoded !== mask && !decoded.isRecycled) decoded.recycle()
                if (mask != null && !mask.isRecycled) mask.recycle()
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "导入自定义景深蒙版失败", e)
            onMain { toast(context, e.message ?: "导入自定义景深蒙版失败") }
        }
    }

    private fun decodeCustomMask(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Throwable) {
        Logger.e(TAG, "读取所选蒙版失败", e)
        null
    }

    private fun imageAspectRatio(path: String): Float? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            // BitmapFactory 不解析 EXIF 方向：竖拍照片以横向像素存储，须按旋转标记换回，
            // 否则与 PS 等已烘焙方向的导出图比较时比例正好倒置。
            val rotated = when (readExifOrientation(path)) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE,
                -> true
                else -> false
            }
            val width = if (rotated) options.outHeight else options.outWidth
            val height = if (rotated) options.outWidth else options.outHeight
            width.toFloat() / height
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private fun readExifOrientation(path: String): Int = try {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Throwable) {
        ExifInterface.ORIENTATION_UNDEFINED
    }

    /** 换新蒙版后淘汰旧文件，避免共享目录残留无用的抠图。 */
    private fun deleteStaleMask(previousPath: String?, currentPath: String) {
        if (previousPath.isNullOrBlank() || previousPath == currentPath) return
        val file = File(previousPath)
        if (!file.isFile || !file.name.startsWith(MASK_FILE_PREFIX)) return
        if (!file.delete()) Logger.w(TAG, "删除旧景深蒙版失败: $file")
    }

    private fun refreshDepthButtons(context: Context) {
        depthButtons.keys.toList().forEach { button ->
            val label = depthButtons[button] ?: return@forEach
            updateButtonState(context, button, label)
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
                return WallpaperSource(groupId, imagePath, sourcePath?.takeIf { File(it).isFile })
            }
        }
    }

    private fun createMask(context: Context, imagePath: String, cacheKey: String): File? {
        val source = loadMattingSource(context, imagePath)
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
                    "${MASK_FILE_PREFIX}$cacheKey.png",
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

    /**
     * 景深结果最终只会绘制到屏幕上。先缩到屏幕像素可大幅降低模型推理与 PNG 编码耗时，
     * 同时保留壁纸宽高比，保证 SystemUI 的中心裁剪坐标不变。
     */
    private fun loadMattingSource(context: Context, imagePath: String): Bitmap {
        val decoded = BitmapFactory.decodeFile(imagePath)
            ?: throw IllegalStateException("无法读取锁屏壁纸: $imagePath")
        return try {
            scaleBitmapToFitScreen(context, decoded)
        } catch (e: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw e
        }
    }

    private fun scaleBitmapToFitScreen(context: Context, decoded: Bitmap): Bitmap {
        val metrics = context.resources.displayMetrics
        val scale = minOf(
            1f,
            metrics.widthPixels.toFloat() / decoded.width,
            metrics.heightPixels.toFloat() / decoded.height,
        )
        if (scale >= 1f) return decoded
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, width, height, true).also { resized ->
            if (resized !== decoded) decoded.recycle()
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
                    config.optString(PENDING_SOURCE_IMAGE_PATH),
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
        pendingSourceImagePath: String? = null,
        custom: Boolean = false,
    ) {
        val config = JSONObject().apply {
            put("enabled", enabled)
            put("group_id", groupId)
            put("mask_path", maskPath)
            put("wallpaper_id", wallpaperId)
            put("source_image_path", sourceImagePath)
            put(PENDING_SOURCE_IMAGE_PATH, pendingSourceImagePath)
            put("custom", custom)
        }
        if (!Settings.Secure.putString(context.contentResolver, SETTING_KEY, config.toString())) {
            throw IllegalStateException("无法保存景深状态")
        }
    }

    private fun updateButtonState(context: Context, button: View, label: TextView) {
        val enabled = readConfig(context)?.optBoolean("enabled") == true
        // 原生四款景深时钟以 selected 状态点亮圆形按钮：白底、黑色图标。
        button.isSelected = enabled
        button.findViewWithTag<ImageView>(BUTTON_ICON_TAG)?.apply {
            isSelected = enabled
            setColorFilter(if (enabled) Color.BLACK else Color.WHITE)
        }
        button.alpha = 1f
        label.text = string(context, "depth_of_field", "景深")
    }

    private fun installWallpaperChangedReceiver(context: Context?) {
        if (context == null || wallpaperChangedReceiver != null) return
        try {
            val appContext = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action == ACTION_WALLPAPER_CHANGED) {
                        invalidateMaskForWallpaperChangeAsync(appContext)
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
        retryWhenUnchanged: Boolean = false,
    ) {
        val generation = wallpaperTaskGeneration.incrementAndGet()
        scheduleWallpaperRegeneration(context, delayMillis, generation, 0, retryWhenUnchanged)
    }

    /**
     * 锁屏壁纸 ID 改变时，先原子地撤销旧景深并清理模块缓存。
     * 等数据库提交了不同的新预览成品后才重新抠图，绝不让旧主体进入新底图。
     */
    private fun invalidateMaskForWallpaperChangeAsync(context: Context) {
        val generation = wallpaperTaskGeneration.incrementAndGet()
        executor.execute {
            try {
                val config = readConfig(context) ?: return@execute
                if (!config.optBoolean("enabled")) return@execute
                val wallpaperId = currentLockscreenWallpaperId(context)
                if (wallpaperId < 0 || wallpaperId == config.optInt("wallpaper_id", -1)) return@execute

                if (config.optBoolean("custom")) {
                    // 同图重新应用时壁纸 ID 会变但蒙版仍有效：交给延迟任务按源图重新绑定，
                    // 真换了图才关闭。期间 SystemUI 的 ID 校验会先移除旧挖空层，不会残留。
                    scheduleWallpaperRegeneration(context, 700, generation, 0, retryWhenUnchanged = true)
                    Logger.i(TAG, "锁屏壁纸已变更，稍后尝试重新绑定自定义蒙版")
                    return@execute
                }
                val pendingSource = config.optString("source_image_path").takeIf { it.isNotBlank() }
                // 先清配置，SystemUI 将立即去除当前旧挖空层；随后再删除磁盘缓存。
                saveConfig(context, true, null, null, -1, null, pendingSource)
                clearOwnedMaskCache()
                onMain {
                    refreshAllEditorPreviews()
                    // 广播可能早于数据库应用记录，延迟后通过 pendingSource 判断是否已切到新成品。
                    scheduleWallpaperRegeneration(context, 700, generation, 0, retryWhenUnchanged = true)
                }
                Logger.i(TAG, "已清除旧锁屏壁纸景深缓存")
            } catch (e: Throwable) {
                Logger.e(TAG, "清除旧锁屏壁纸景深缓存失败", e)
            }
        }
    }

    private fun scheduleWallpaperRegeneration(
        context: Context,
        delayMillis: Long,
        generation: Long,
        retryCount: Int,
        retryWhenUnchanged: Boolean,
    ) {
        mainHandler.postDelayed({
            executor.execute {
                try {
                    if (generation != wallpaperTaskGeneration.get()) return@execute
                    val config = readConfig(context) ?: return@execute
                    if (!config.optBoolean("enabled")) return@execute
                    val source = queryCurrentLockscreenImage(context) ?: return@execute
                    val wallpaperId = currentLockscreenWallpaperId(context)
                    val hasBoundSource = config.optString("source_image_path").isNotBlank()
                    if (
                        wallpaperId < 0 ||
                        (hasBoundSource && config.optInt("wallpaper_id", -1) == wallpaperId)
                    ) {
                        return@execute
                    }
                    // 进程重启等场景会错过广播清配置。自定义蒙版按源图判断：仍是同一张图
                    // （如重新应用）就重新绑定到新壁纸 ID；换了图则关闭，等待重新选择。
                    if (config.optBoolean("custom")) {
                        rebindOrCloseCustomMask(context, config, source, wallpaperId)
                        return@execute
                    }
                    // 只有数据库已切到新锁屏成品时才生成。否则广播可能早于数据库提交，
                    // 不能把上一次图片错误绑定到新的壁纸 ID。
                    val pendingSource = config.optString(PENDING_SOURCE_IMAGE_PATH)
                    if (pendingSource.isNotBlank() && pendingSource == source.imagePath) {
                        if (retryWhenUnchanged && retryCount < 5) {
                            scheduleWallpaperRegeneration(
                                context,
                                700,
                                generation,
                                retryCount + 1,
                                retryWhenUnchanged = true,
                            )
                        }
                        return@execute
                    }
                    val mask = createMask(context, source.imagePath, wallpaperId.toString())
                        ?: throw IllegalStateException("当前锁屏壁纸不支持景深效果")
                    if (!isWallpaperSourceStable(context, generation, wallpaperId, source)) {
                        mask.delete()
                        return@execute
                    }
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

    /**
     * 重新应用同一张壁纸时壁纸 ID 会变，自定义蒙版按源图与比例校验后重新绑定新 ID；
     * 已换图（源图路径或比例不符）则关闭景深，等待用户重新选择蒙版。
     */
    private fun rebindOrCloseCustomMask(
        context: Context,
        config: JSONObject,
        source: WallpaperSource,
        wallpaperId: Int,
    ) {
        val maskPath = config.optString("mask_path")
        val boundSourcePath = config.optString("source_image_path")
        val stillSameImage =
            boundSourcePath.isNotBlank() &&
                boundSourcePath == source.imagePath &&
                maskRatioMatchesSource(maskPath, source)
        Logger.d(TAG) {
            "重新绑定检查: 绑定=$boundSourcePath 当前=${source.imagePath} " +
                "蒙版=${imageAspectRatio(maskPath)} 成品=${imageAspectRatio(source.imagePath)} " +
                "原图=${source.sourceImagePath?.let { imageAspectRatio(it) }} 蒙版存在=${File(maskPath).isFile}"
        }
        if (stillSameImage) {
            saveConfig(
                context,
                true,
                source.groupId,
                maskPath,
                wallpaperId,
                boundSourcePath,
                null,
                true,
            )
            onMain { refreshAllEditorPreviews() }
            Logger.i(TAG, "已将自定义蒙版重新绑定到当前锁屏壁纸")
            return
        }
        saveConfig(context, false, null, null)
        onMain {
            refreshDepthButtons(context)
            refreshAllEditorPreviews()
        }
        Logger.i(TAG, "锁屏壁纸已更换，已关闭自定义景深蒙版")
    }

    /**
     * 蒙版经屏幕缩放但比例不变。与导入校验保持同一标准：匹配成品图或原图比例都算
     * 同源（编辑器应用时会对原图居中裁剪，两种画布的主体位置都与渲染端对齐）。
     */
    private fun maskRatioMatchesSource(maskPath: String, source: WallpaperSource): Boolean {
        if (!File(maskPath).isFile) return false
        val maskRatio = imageAspectRatio(maskPath) ?: return false
        val acceptableRatios = listOfNotNull(
            imageAspectRatio(source.imagePath),
            source.sourceImagePath?.let { imageAspectRatio(it) },
        ).filter { it > 0f }
        return acceptableRatios.any { abs(maskRatio - it) / it <= 0.02f }
    }

    /** 抠图耗时期间，壁纸 ID、应用记录或任务代际任一变化都拒绝写入结果。 */
    private fun isWallpaperSourceStable(
        context: Context,
        generation: Long,
        wallpaperId: Int,
        source: WallpaperSource,
    ): Boolean =
        generation == wallpaperTaskGeneration.get() &&
            wallpaperId == currentLockscreenWallpaperId(context) &&
            source == queryCurrentLockscreenImage(context)

    private fun refreshAllEditorPreviews() {
        previewRoots.keys.toList().forEach(::updateEditorPreview)
    }

    /** 仅删除模型生成的蒙版文件；用户的自定义蒙版保留，供重新绑定使用。 */
    private fun clearOwnedMaskCache() {
        sharedOutputDirectory().listFiles()
            ?.filter { it.isFile && it.name.startsWith(MASK_FILE_PREFIX) && !it.name.contains("_custom_") }
            ?.forEach { file ->
                if (!file.delete()) Logger.w(TAG, "删除旧景深缓存失败: ${file.name}")
            }
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
        val wallpaperManager = context.getSystemService(WallpaperManager::class.java)
            ?: return -1
        wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
    } catch (e: Throwable) {
        Logger.e(TAG, "读取当前锁屏壁纸 ID 失败", e)
        -1
    }

    private data class WallpaperSource(
        val groupId: String,
        val imagePath: String,
        val sourceImagePath: String?,
    )
}

package com.karen.flymetool.ui.component.feature

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.AppSlider
import com.karen.flymetool.ui.component.SelectionGroup
import com.karen.flymetool.ui.component.SelectionOption
import com.karen.flymetool.ui.component.SingleSelectionRow
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

private const val CHANNEL_ID = "ios_stack_test"
private const val NOTIF_ID_BASE = 0x1057A00
private const val MAX_TEST = 20

private val SAMPLE_TITLES = listOf(
    "交房租提醒",
    "未接来电",
    "每日推荐",
    "回忆已生成",
    "设计评审会议",
    "工作邮件",
    "团队消息",
    "快递到达",
    "系统更新",
    "日历提醒",
    "天气预警",
    "健身打卡",
    "支付成功",
    "新评论",
    "下载完成",
)

private val SAMPLE_TEXTS = listOf(
    "明天上午 10:00 到期，别忘了转账给房东",
    "妈妈 2 分钟前来电，未接听",
    "根据你的口味精选了 25 首歌曲，已加入资料库",
    "「去年夏天的海边旅行」相册已自动生成，点击查看",
    "今天 15:00 · 会议室 A，请提前准备好评审材料",
    "Q3 季度经营分析会议纪要，请查收附件中的详细数据报表",
    "王工：新版首页原型已经更新到 Figma，大家有空看一下",
    "你的包裹已放在小区快递柜，取件码 8848",
    "Flyme 有可用更新，建议在 Wi-Fi 下完成",
    "明天 09:30 周会，地点：万象城四楼",
    "明日有暴雨，出行请带伞",
    "今日步数 8234，还差 1766 步达成目标",
    "向 星巴克 支付 ¥32.00 成功",
    "有人回复了你的动态",
    "「设计稿.zip」已下载完成",
)

@Composable
fun IosNotificationStackConfig(
    packageName: String,
    featureKey: String,
) {
    val context = LocalContext.current
    var count by remember { mutableStateOf(8) }
    var bottomPad by remember {
        mutableStateOf(
            PrefsHelper.getFeatureValue(context, packageName, featureKey, 180).toFloat()
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            sendTestNotifications(context, count)
        } else {
            Toast.makeText(context, "需要通知权限", Toast.LENGTH_SHORT).show()
        }
    }

    val counts = listOf(
        SelectionOption(5, "5"),
        SelectionOption(8, "8"),
        SelectionOption(10, "10"),
        SelectionOption(12, "12"),
        SelectionOption(15, "15"),
    )

    Column {
        AppSlider(
            label = "堆叠锚定高度",
            value = bottomPad,
            onValueChange = { bottomPad = it },
            onValueChangeFinished = {
                PrefsHelper.setFeatureValue(
                    context, packageName, featureKey, bottomPad.toInt()
                )
            },
            valueRange = 40f..280f,
            valueDisplay = "${bottomPad.toInt()} dp"
        )

        SelectionGroup(title = "测试通知数量") {
            SingleSelectionRow(
                options = counts,
                selectedValue = count,
                onSelected = { count = it }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (ensureNotifyPermission(context, permissionLauncher)) {
                        sendTestNotifications(context, count)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text("发送通知")
            }
            TextButton(
                text = "清除",
                onClick = {
                    clearTestNotifications(context)
                    Toast.makeText(context, "已清除测试通知", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun ensureNotifyPermission(
    context: Context,
    launcher: ActivityResultLauncher<String>,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    val ok = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    if (!ok) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    return ok
}

private fun ensureChannel(nm: NotificationManager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val existing = nm.getNotificationChannel(CHANNEL_ID)
    if (existing != null) return
    nm.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            "通知堆叠测试",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "仅用于测试 iOS 通知堆叠，每条独立不分组"
            setShowBadge(false)
        }
    )
}

/**
 * 每条通知：独立 id + 独立 group key，且不发 summary。
 * 避免系统把同应用多条通知折叠成一组。
 */
private fun sendTestNotifications(context: Context, count: Int) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    ensureChannel(nm)
    clearTestNotifications(context)

    val n = count.coerceIn(1, MAX_TEST)
    val now = System.currentTimeMillis()
    for (i in 0 until n) {
        val title = SAMPLE_TITLES[i % SAMPLE_TITLES.size]
        val text = SAMPLE_TEXTS[i % SAMPLE_TEXTS.size]
        // 唯一 group：系统不会把它们并成一个可折叠组
        val uniqueGroup = "ios_stack_solo_${now}_$i"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setWhen(now - i * 60_000L)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(uniqueGroup)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSortKey(String.format("%02d", i))
            .setLocalOnly(true)
            .build()
        notification.flags = notification.flags and Notification.FLAG_GROUP_SUMMARY.inv()
        nm.notify(NOTIF_ID_BASE + i, notification)
    }
    Toast.makeText(context, "已发送 $n 条独立测试通知", Toast.LENGTH_SHORT).show()
}

private fun clearTestNotifications(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    for (i in 0 until MAX_TEST) {
        nm.cancel(NOTIF_ID_BASE + i)
    }
}

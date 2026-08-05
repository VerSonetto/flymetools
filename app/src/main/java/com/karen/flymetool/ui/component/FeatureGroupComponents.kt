package com.karen.flymetool.ui.component

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karen.flymetool.data.HookFeature
import com.karen.flymetool.data.PrefsHelper
import com.karen.flymetool.ui.component.feature.FeatureConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 应用详情页的分组入口卡：点击进入该组功能列表页。
 */
@Composable
fun FeatureGroupCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 统一功能行：开关 + 开启后行内配置。
 * 调用方应先用 [isFeatureVisible] 过滤，避免隐藏项残留分割线。
 */
@Composable
fun FeatureListRow(
    packageName: String,
    feature: HookFeature,
    featureStates: Map<String, MutableState<Boolean>>,
    context: Context,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surface)
    ) {
        FeatureSwitch(
            title = feature.label,
            description = feature.description,
            checked = featureStates[feature.key]?.value ?: false,
            onCheckedChange = { newValue ->
                featureStates[feature.key]?.value = newValue
                PrefsHelper.setFeatureEnabled(context, packageName, feature.key, newValue)
                if (newValue) {
                    feature.exclusiveWith?.let { otherKey ->
                        featureStates[otherKey]?.value = false
                        PrefsHelper.setFeatureEnabled(context, packageName, otherKey, false)
                    }
                }
            }
        )

        AnimatedVisibility(
            visible = featureStates[feature.key]?.value == true,
            enter = expandVertically(tween(180)) + fadeIn(tween(120)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(100))
        ) {
            FeatureConfig(
                featureKey = feature.key,
                packageName = packageName,
                onNavigate = onNavigate
            )
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.dividerLine)
            )
        }
    }
}

fun isFeatureVisible(
    feature: HookFeature,
    featureStates: Map<String, MutableState<Boolean>>
): Boolean {
    val dependsOnKey = feature.dependsOn
    if (dependsOnKey != null && featureStates[dependsOnKey]?.value != true) {
        return false
    }
    val visibleUnlessKey = feature.visibleUnless
    if (visibleUnlessKey != null && featureStates[visibleUnlessKey]?.value == true) {
        return false
    }
    return true
}

fun visibleFeatures(
    features: List<HookFeature>,
    featureStates: Map<String, MutableState<Boolean>>
): List<HookFeature> {
    return features.filter { isFeatureVisible(it, featureStates) }
}

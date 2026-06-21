package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable

@Composable
fun FeatureConfig(
    featureKey: String,
    packageName: String,
    onNavigate: (String) -> Unit = {}
) {
    when (featureKey) {
        "statusbar_weekday" -> WeekdayFormatConfig(packageName, featureKey)
        "notification_card_radius" -> NotificationCardRadiusConfig(packageName, featureKey)
        "media_card_radius" -> MediaCardRadiusConfig(packageName, featureKey)
        "connection_rate_low_speed_hide" -> ConnectionRateConfig(packageName, featureKey)
        "pulldown_area_ratio" -> PulldownAreaRatioConfig(packageName, featureKey)
        "notification_icon_limit" -> NotificationIconLimitConfig(packageName, featureKey)
        "task_card_radius" -> TaskCardRadiusConfig(packageName, featureKey)
        "task_blur_intensity" -> BlurIntensityConfig(packageName, featureKey)
        "memory_display" -> MemoryDisplayConfig(packageName, featureKey)
        "power_display" -> PowerDisplayConfig(packageName, featureKey)
        "hide_status_bar_icon" -> HideStatusBarIconConfig(packageName, featureKey)
        "custom_browser" -> CustomBrowserConfig(packageName, featureKey)
        "capture_update_link" -> CaptureUpdateLinkConfig(packageName, featureKey)
        "aod_notification" -> AODNotificationConfig(packageName, featureKey)
        "folder_icon_blur" -> FolderIconBlurConfig(packageName, featureKey)
        "folder_open_blur" -> FolderOpenBlurConfig(packageName, featureKey)
        "custom_carrier_name" -> CustomCarrierNameConfig(packageName, featureKey)
        "live_notification_force" -> LiveNotificationForceConfig(packageName, featureKey, onNavigate)
        "custom_charge_limit" -> ChargeLimitConfig(packageName, featureKey)
    }
}

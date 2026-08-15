package com.karen.flymetool.ui.component.feature

import androidx.compose.runtime.Composable

/**
 * 是否有行内配置面板。须与 [FeatureConfig] 的 when 分支保持同步。
 */
fun hasFeatureConfig(featureKey: String): Boolean = when (featureKey) {
    "statusbar_weekday",
    "statusbar_clock_period",
    "statusbar_clock_custom_format",
    "notification_card_radius",
    "notification_card_blur",
    "media_card_radius",
    "connection_rate_low_speed_hide",
    "pulldown_area_ratio",
    "control_center_blur_intensity",
    "control_center_radius",
    "control_center_bg_opacity",
    "notification_icon_limit",
    "task_card_radius",
    "task_blur_intensity",
    "memory_display",
    "power_display",
    "hide_status_bar_icon",
    "custom_browser",
    "capture_update_link",
    "aod_lyric",
    "aod_notification",
    "folder_icon_blur",
    "folder_open_blur",
    "custom_carrier_name",
    "custom_charge_limit",
    "force_live_notification",
    "force_camera_circle_battery",
    "circle_battery_status_bar_icon",
    "ios_notification_stack",
    "edge_back_vibrate_intensity",
    "edge_back_hold_previous_app",
    "mback_double_click",
    "slide_gesture_multi_arc",
    "custom_search_engine" -> true
    else -> false
}

@Composable
fun FeatureConfig(
    featureKey: String,
    packageName: String,
    onNavigate: (String) -> Unit = {},
) {
    when (featureKey) {
        "statusbar_weekday" -> WeekdayFormatConfig(packageName, featureKey)
        "statusbar_clock_period" -> StatusBarClockPeriodConfig(packageName, featureKey)
        "statusbar_clock_custom_format" -> StatusBarClockCustomFormatConfig(packageName, featureKey)
        "notification_card_radius" -> NotificationCardRadiusConfig(packageName, featureKey)
        "notification_card_blur" -> NotificationCardBlurConfig(packageName, featureKey)
        "media_card_radius" -> MediaCardRadiusConfig(packageName, featureKey)

        "connection_rate_low_speed_hide" -> ConnectionRateConfig(packageName, featureKey)
        "pulldown_area_ratio" -> PulldownAreaRatioConfig(packageName, featureKey)
        "control_center_blur_intensity" -> ControlCenterBlurConfig(packageName, featureKey)
        "control_center_radius" -> ControlCenterRadiusConfig(packageName, featureKey)
        "control_center_bg_opacity" -> ControlCenterOpacityConfig(packageName, featureKey)
        "notification_icon_limit" -> NotificationIconLimitConfig(packageName, featureKey)
        "task_card_radius" -> TaskCardRadiusConfig(packageName, featureKey)
        "task_blur_intensity" -> BlurIntensityConfig(packageName, featureKey)
        "memory_display" -> MemoryDisplayConfig(packageName, featureKey)
        "power_display" -> PowerDisplayConfig(packageName, featureKey)
        "hide_status_bar_icon" -> HideStatusBarIconConfig(packageName, featureKey)
        "custom_browser" -> CustomBrowserConfig(packageName, featureKey)
        "capture_update_link" -> CaptureUpdateLinkConfig(packageName, featureKey)
        "aod_lyric" -> AODLyricConfig(packageName, featureKey)
        "aod_notification" -> AODNotificationConfig(packageName, featureKey)
        "folder_icon_blur" -> FolderIconBlurConfig(packageName, featureKey)
        "folder_open_blur" -> FolderOpenBlurConfig(packageName, featureKey)
        "custom_carrier_name" -> CustomCarrierNameConfig(packageName, featureKey)
        "custom_charge_limit" -> ChargeLimitConfig(packageName, featureKey)
        "force_live_notification" -> ForceLiveNotificationConfig(packageName, featureKey, onNavigate)
        "force_camera_circle_battery" -> ForceCircleBatteryConfig(packageName, featureKey)
        "circle_battery_status_bar_icon" -> CircleBatteryStatusBarIconConfig(packageName, featureKey)
        "ios_notification_stack" -> IosNotificationStackConfig(packageName, featureKey)
        "edge_back_vibrate_intensity" -> EdgeBackVibrateConfig(packageName, featureKey)
        "edge_back_hold_previous_app" -> EdgeBackHoldPreviousAppConfig(packageName, featureKey)
        "mback_double_click" -> MBackDoubleClickConfig(packageName, featureKey)
        "slide_gesture_multi_arc" -> SlideGestureMultiArcConfig(packageName, featureKey)
        "custom_search_engine" -> CustomSearchEngineConfig(packageName, featureKey)
    }
}

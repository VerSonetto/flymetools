package com.karen.flymetool.data

data class ScopedApp(
    val packageName: String,
    val name: String
)

data class HookFeature(
    val key: String,
    val label: String,
    val description: String = "",
    val dependsOn: String? = null,
    val visibleUnless: String? = null
)

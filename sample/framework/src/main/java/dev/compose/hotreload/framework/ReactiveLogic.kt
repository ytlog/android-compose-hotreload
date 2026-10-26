package dev.compose.hotreload.framework

fun snapshotProjection(value: Int): String = "snapshot-v1:${value / 2}"
fun snapshotDelivery(value: String): String = "collector-v1:$value"
fun derivedProjection(value: Int): String = "derived-v1:${value / 2}"
fun lifecycleProjection(value: Int): String = "lifecycle-v1:$value"

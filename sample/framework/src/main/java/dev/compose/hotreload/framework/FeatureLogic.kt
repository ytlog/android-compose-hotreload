package dev.compose.hotreload.framework

fun overlayLabel(value: String) = "overlay-v1:$value"
fun overlayDismiss(kind: String) = "dismiss-v1:$kind"
fun pagingLabel(id: Int) = "row-v1:$id"
fun pagerLabel(page: Int) = "page-v1:$page"
fun animationFrame(value: Float) = "frame-v1:${value.toInt()}"
fun gestureLabel(value: Int) = "drag-v1:$value"

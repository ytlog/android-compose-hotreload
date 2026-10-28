package dev.compose.hotreload.framework

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.*
import androidx.compose.ui.node.*
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.*

object DrawAudit { var created by mutableIntStateOf(0); var disposed by mutableIntStateOf(0); var attached by mutableIntStateOf(0); var detached by mutableIntStateOf(0) }
class DrawPage {
    val identity = token()
    var draft by mutableStateOf("draw-draft")
    var drawTick by mutableIntStateOf(0)
    var layoutTick by mutableIntStateOf(0)
    var node: Any? = null
    var attaches = 0; var detaches = 0; var caches = 0; var frames = 0
    var x = 0; var y = 0; var width = 0; var height = 0
    var paletteX = 0; var paletteY = 0; var paletteWidth = 0; var paletteHeight = 0
    var report by mutableStateOf("none")
    val measure = MeasurePolicy { measurables, constraints ->
        layoutTick
        val w = constraints.constrainWidth(layoutWidth().dp.roundToPx())
        val h = 80.dp.roundToPx()
        val child = measurables.single().measure(Constraints.fixed(w, h))
        layout(w, h) { child.place(0, 0) }
    }
    fun inspect() { report = "$x:$y:$width:$height:$attaches:$detaches:$caches:$frames:$paletteX:$paletteY:$paletteWidth:$paletteHeight" }
}
private data class DrawElement(val page: DrawPage) : ModifierNodeElement<DrawNode>() {
    override fun create() = DrawNode(page)
    override fun update(node: DrawNode) { check(node.page === page) }
    override fun InspectorInfo.inspectableProperties() { name = "fixtureDraw" }
}
private class DrawNode(val page: DrawPage) : Modifier.Node(), DrawModifierNode {
    override fun onAttach() { page.node = this; page.attaches++; DrawAudit.attached++ }
    override fun onDetach() { page.detaches++; DrawAudit.detached++ }
    override fun ContentDrawScope.draw() {
        page.drawTick
        drawContent()
        drawRect(nodeColor(), Offset(size.width * .75f, 0f), Size(size.width * .25f, size.height))
    }
}
@Composable
fun DrawScreen() {
    val page = remember { DrawPage() }
    DisposableEffect(page) { DrawAudit.created++; onDispose { DrawAudit.disposed++ } }
    Column {
        Text("Draw anchor: ${page.identity}:${System.identityHashCode(page.measure)}", fontSize = 10.sp)
        Text("Draw draft: ${page.draft}", fontSize = 10.sp)
        Text("Draw report: ${page.report}", fontSize = 10.sp)
        Text("Draw node: ${System.identityHashCode(page.node)}", fontSize = 10.sp)
        Row {
            Button(onClick = { page.draft = "edited-draw"; page.drawTick++ }) { Text("Redraw") }
            Button(onClick = { page.layoutTick++ }) { Text("Remeasure") }
            Button(onClick = { page.inspect() }) { Text("Inspect drawing") }
        }
        Layout(content = {
            Canvas(Modifier.then(DrawElement(page)).drawWithCache {
                page.caches++
                onDrawBehind { page.drawTick; drawRect(canvasColor()) }
            }) { page.frames++ }
        }, measurePolicy = page.measure, modifier = Modifier.onGloballyPositioned {
            val p = it.positionInWindow(); page.x = p.x.toInt(); page.y = p.y.toInt()
            page.width = it.size.width; page.height = it.size.height
        })
        // Reference pixels share the screenshot color profile; their code is never hot-edited.
        Canvas(Modifier.size(160.dp, 24.dp).onGloballyPositioned {
            val p = it.positionInWindow(); page.paletteX = p.x.toInt(); page.paletteY = p.y.toInt()
            page.paletteWidth = it.size.width; page.paletteHeight = it.size.height
        }) {
            listOf(Color.Red, Color.Blue, Color.Yellow, Color.Green).forEachIndexed { i, color ->
                drawRect(color, Offset(i * size.width / 4, 0f), Size(size.width / 4, size.height))
            }
        }
    }
}

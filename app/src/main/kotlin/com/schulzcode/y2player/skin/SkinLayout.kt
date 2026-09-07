package com.schulzcode.y2player.skin

import kotlin.math.*

/** Platform-independent geometry: also the contract for editor preview fixtures. */
object SkinLayout {
    fun capacity(node: SkinNode): Int = when (node.listMode) {
        "horizontal", "carousel" -> ((node.bounds.width + node.gap) / (node.rowWidth + node.gap)).toInt()
        "radial" -> node.columns
        else -> ((node.bounds.height + node.gap) / (node.rowHeight + node.gap)).toInt() * node.columns
    }.coerceIn(1, 128)
    fun firstVisible(node: SkinNode, selected: Int): Int {
        val capacity = capacity(node)
        val index = selected.coerceAtLeast(0)
        return when {
            node.listMode == "carousel" || node.scrolling == "center" -> (index - capacity / 2).coerceAtLeast(0)
            node.scrolling == "follow" -> (index - capacity + 1).coerceAtLeast(0)
            else -> index / capacity * capacity
        }
    }
    fun cell(node: SkinNode, slot: Int): SkinRect {
        val b = node.bounds
        return when (node.listMode) {
            "horizontal", "carousel" -> SkinRect(b.x + slot * (node.rowWidth + node.gap), b.y, node.rowWidth, node.rowHeight)
            "radial" -> {
                val angle = -PI / 2 + slot * 2 * PI / capacity(node)
                SkinRect(b.x + (b.width - node.rowWidth) / 2 * (1 + cos(angle)).toFloat(),
                    b.y + (b.height - node.rowHeight) / 2 * (1 + sin(angle)).toFloat(), node.rowWidth, node.rowHeight)
            }
            else -> {
                val width = (b.width - (node.columns - 1) * node.gap) / node.columns
                SkinRect(b.x + (slot % node.columns) * (width + node.gap),
                    b.y + (slot / node.columns) * (node.rowHeight + node.gap), width, node.rowHeight)
            }
        }
    }
    fun hit(node: SkinNode, x: Float, y: Float, selected: Int, count: Int): Int? {
        if (!node.bounds.contains(x, y)) return null
        val first = firstVisible(node, selected)
        // Last drawn wins, including overlapping radial cells.
        return (0 until minOf(capacity(node), count - first)).lastOrNull { cell(node, it).contains(x, y) }?.plus(first)
    }

    fun children(parent: SkinNode): List<SkinNode> {
        val p = parent.padding
        val w = (parent.bounds.width - 2 * p).coerceAtLeast(1f)
        val h = (parent.bounds.height - 2 * p).coerceAtLeast(1f)
        val nodes = parent.children
        var cursor = p
        val horizontal = parent.layout == "row"
        val fixed = nodes.filter { it.weight == 0f }.sumOf { (if (horizontal) it.bounds.width else it.bounds.height).toDouble() }.toFloat()
        val weights = nodes.sumOf { it.weight.toDouble() }.toFloat()
        val available = ((if (horizontal) w else h) - fixed - parent.gap * (nodes.size - 1).coerceAtLeast(0)).coerceAtLeast(0f)
        return nodes.mapIndexed { index, node ->
            val b = node.bounds
            val next = when (parent.layout) {
                "row", "column" -> {
                    val extent = if (node.weight > 0f) (available * node.weight / weights).coerceAtLeast(1f) else if (horizontal) b.width else b.height
                    val placed = if (horizontal) SkinRect(cursor, p, extent, h) else SkinRect(p, cursor, w, extent)
                    cursor += extent + parent.gap
                    placed
                }
                "grid" -> {
                    val columns = parent.columns
                    val rows = ceil(nodes.size.toFloat() / columns).toInt().coerceAtLeast(1)
                    val cw = ((w - parent.gap * (columns - 1)) / columns).coerceAtLeast(1f)
                    val ch = ((h - parent.gap * (rows - 1)) / rows).coerceAtLeast(1f)
                    SkinRect(p + index % columns * (cw + parent.gap), p + index / columns * (ch + parent.gap), cw, ch)
                }
                "stack" -> SkinRect(p, p, w, h)
                else -> when (node.anchor) {
                    "topRight" -> b.copy(x = p + w - b.width - b.x, y = p + b.y)
                    "bottomLeft" -> b.copy(x = p + b.x, y = p + h - b.height - b.y)
                    "bottomRight" -> b.copy(x = p + w - b.width - b.x, y = p + h - b.height - b.y)
                    "center" -> b.copy(x = p + (w - b.width) / 2 + b.x, y = p + (h - b.height) / 2 + b.y)
                    "stretch" -> SkinRect(p + b.x, p + b.y, (w - 2 * b.x).coerceAtLeast(1f), (h - 2 * b.y).coerceAtLeast(1f))
                    else -> b.copy(x = p + b.x, y = p + b.y)
                }
            }
            node.copy(bounds = next)
        }
    }

    fun imageBounds(box: SkinRect, width: Int, height: Int, fit: String, x: Float, y: Float): SkinRect {
        if (fit == "stretch") return box
        val scale = if (fit == "crop") max(box.width / width, box.height / height) else min(box.width / width, box.height / height)
        val w = width * scale; val h = height * scale
        return SkinRect(box.x + (box.width - w) * x, box.y + (box.height - h) * y, w, h)
    }
    fun seekFraction(box: SkinRect, orientation: String, x: Float, y: Float): Float = when (orientation) {
        "vertical" -> 1 - (y - box.y) / box.height
        "circular" -> ((atan2((y - box.y - box.height / 2).toDouble(), (x - box.x - box.width / 2).toDouble()) + PI / 2 + 2 * PI) % (2 * PI) / (2 * PI)).toFloat()
        else -> (x - box.x) / box.width
    }.coerceIn(0f, 1f)
}

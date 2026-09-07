package com.schulzcode.y2player.skin

/** Deterministic wrapping independent of Android. The preview supplies its font measurement adapter. */
object SkinTextLayout {
    fun lines(source: String, width: Float, count: Int, ellipsis: Boolean, measure: (String) -> Float): List<String> {
        var remaining = source
        val result = mutableListOf<String>()
        repeat(count.coerceAtLeast(1)) { line ->
            if (remaining.isEmpty()) return@repeat
            val paragraph = remaining.substringBefore('\n')
            val last = line == count - 1
            if (measure(paragraph) <= width && (!last || paragraph.length == remaining.length)) {
                result += paragraph
                remaining = remaining.drop(paragraph.length + if (paragraph.length < remaining.length) 1 else 0)
            } else {
                val suffix = if (last && ellipsis) "…" else ""
                val available = (width - measure(suffix)).coerceAtLeast(0f)
                var end = 0
                while (end < paragraph.length) {
                    val next = end + Character.charCount(Character.codePointAt(paragraph, end))
                    if (measure(paragraph.substring(0, next)) > available) break
                    end = next
                }
                if (!last && end < paragraph.length) paragraph.lastIndexOf(' ', (end - 1).coerceAtLeast(0)).takeIf { it > 0 }?.let { end = it }
                result += paragraph.take(end) + if (measure(suffix) <= width) suffix else ""
                if (end == 0 && paragraph.isNotEmpty()) end = Character.charCount(Character.codePointAt(paragraph, 0))
                remaining = remaining.drop(end).trimStart(' ', '\n')
            }
        }
        return result
    }
}

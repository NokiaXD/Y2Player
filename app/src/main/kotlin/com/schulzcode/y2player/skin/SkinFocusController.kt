package com.schulzcode.y2player.skin

/** Focus is presentation state. Row selection and player operations stay in the reducer. */
class SkinFocusController {
    var selected: String? = null
        private set
    var editingRows = false
        private set
    private var items: List<SkinFocus> = emptyList()
    private var wrap = true
    fun reset() { selected = null; editingRows = false; items = emptyList() }
    fun update(visible: List<SkinFocus>, navigation: SkinNavigation) {
        items = visible.sortedWith(compareBy<SkinFocus> { it.group }.thenBy { it.order })
        wrap = navigation.wrap
        if (items.none { it.id == selected }) {
            selected = items.firstOrNull { it.id == navigation.initial }?.id ?: items.firstOrNull()?.id
            editingRows = false
        }
    }
    fun select(id: String) { if (items.any { it.id == id }) { selected = id; editingRows = false } }
    fun enterRows() { editingRows = true }
    fun leaveRows(): Boolean = editingRows.also { editingRows = false }
    fun move(delta: Int) {
        if (items.isEmpty() || delta == 0) return
        val current = items.firstOrNull { it.id == selected } ?: items.first()
        val explicit = if (delta > 0) current.next else current.previous
        if (explicit.isNotEmpty() && items.any { it.id == explicit }) { selected = explicit; return }
        val index = items.indexOf(current)
        val next = index.toLong() + delta
        selected = items[if (wrap) ((next % items.size + items.size) % items.size).toInt() else next.coerceIn(0, items.lastIndex.toLong()).toInt()].id
    }
    fun moveGroup(delta: Int) {
        val groups = items.map { it.group }.distinct()
        if (groups.isEmpty()) return
        val current = items.firstOrNull { it.id == selected }?.group
        val next = (groups.indexOf(current) + delta.sign() + groups.size) % groups.size
        selected = items.first { it.group == groups[next] }.id
        editingRows = false
    }
    private fun Int.sign() = if (this < 0) -1 else 1
}

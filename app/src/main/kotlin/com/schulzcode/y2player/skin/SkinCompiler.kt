package com.schulzcode.y2player.skin

import org.json.JSONArray
import org.json.JSONObject

/** Expand typed component parameters and shared styles once, before validation/loading. */
internal object SkinCompiler {
    private val parameter = Regex("\\{param\\.([a-zA-Z][a-zA-Z0-9_]*)\\}")
    fun compile(source: JSONObject): JSONObject {
        val templates = source.optJSONObject("components") ?: JSONObject()
        val styles = source.optJSONObject("styles") ?: JSONObject()
        styles.keys().asSequence().forEach { key ->
            SkinParser.checkFields(styles.getJSONObject(key), SkinParser.visualFields + setOf("font", "lineSpacing", "overflow", "verticalAlign"), "style")
        }
        var budget = 0
        fun substitute(value: Any, props: JSONObject): Any = when (value) {
            is JSONObject -> JSONObject().also { result -> value.keys().asSequence().forEach { result.put(it, substitute(value.get(it), props)) } }
            is JSONArray -> JSONArray().also { result -> for (i in 0 until value.length()) result.put(substitute(value.get(i), props)) }
            is String -> {
                val whole = parameter.matchEntire(value)
                if (whole != null) {
                    val key = whole.groupValues[1]
                    require(props.has(key)) { "Missing component parameter: $key" }
                    props.get(key)
                } else parameter.replace(value) {
                    val key = it.groupValues[1]
                    require(props.has(key)) { "Missing component parameter: $key" }
                    props.get(key).toString()
                }
            }
            else -> value
        }
        fun expand(array: JSONArray, stack: Set<String>, depth: Int): JSONArray {
            require(depth <= 12) { "Component nesting exceeds 12" }
            val result = JSONArray()
            for (i in 0 until array.length()) {
                require(++budget <= 4096) { "Compiled skin exceeds 4096 nodes" }
                val raw = array.getJSONObject(i)
                SkinParser.checkFields(raw, SkinParser.v2Fields + setOf("style", "props"), "node")
                val node = JSONObject()
                if (raw.has("style")) {
                    val style = styles.getJSONObject(raw.getString("style"))
                    SkinParser.checkFields(style, SkinParser.visualFields + setOf("font", "lineSpacing", "overflow", "verticalAlign"), "style")
                    style.keys().asSequence().forEach { node.put(it, style.get(it)) }
                }
                raw.keys().asSequence().filter { it != "style" }.forEach { node.put(it, raw.get(it)) }
                if (node.optString("type") == "component") {
                    val key = node.getString("asset")
                    require(key !in stack) { "Component cycle: $key" }
                    val template = templates.get(key)
                    val defaults = if (template is JSONObject) {
                        SkinParser.checkFields(template, setOf("parameters", "nodes"), "component template")
                        template.optJSONObject("parameters") ?: JSONObject()
                    } else JSONObject()
                    val props = JSONObject(defaults.toString())
                    val overrides = node.optJSONObject("props") ?: JSONObject()
                    overrides.keys().asSequence().forEach { name ->
                        require(defaults.has(name)) { "Unknown component parameter: $name" }
                        val before = defaults.get(name); val after = overrides.get(name)
                        require((before is Number && after is Number) || (before is String && after is String) || (before is Boolean && after is Boolean)) { "Parameter type mismatch: $name" }
                        props.put(name, after)
                    }
                    defaults.keys().asSequence().forEach { name ->
                        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}"))) { "Invalid parameter name" }
                        require(defaults.get(name) is String || defaults.get(name) is Number || defaults.get(name) is Boolean) { "Parameters must be scalar values" }
                    }
                    val nodes = if (template is JSONObject) template.getJSONArray("nodes") else template as JSONArray
                    require(!node.has("children")) { "Component instances cannot supply children" }
                    node.put("type", "group"); node.remove("asset"); node.remove("props")
                    node.put("children", expand(substitute(nodes, props) as JSONArray, stack + key, depth + 1))
                } else {
                    require(!node.has("props")) { "props requires a component" }
                    node.optJSONArray("children")?.let { node.put("children", expand(it, stack, depth + 1)) }
                }
                result.put(node)
            }
            return result
        }
        // Validate unused templates too; broken assets/styles must not hide until first use.
        val components = JSONObject()
        templates.keys().asSequence().forEach { key ->
            val instance = JSONObject().put("type", "component").put("bounds", JSONArray("[0,0,480,360]")).put("asset", key)
            components.put(key, expand(JSONArray().put(instance), emptySet(), 0))
        }
        val screens = JSONObject()
        source.getJSONObject("screens").let { obj -> obj.keys().asSequence().forEach { screens.put(it, expand(obj.getJSONArray(it), emptySet(), 0)) } }
        return JSONObject(source.toString()).apply {
            remove("styles"); put("components", components); put("screens", screens)
        }
    }
}

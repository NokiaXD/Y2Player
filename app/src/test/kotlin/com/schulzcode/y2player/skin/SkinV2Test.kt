package com.schulzcode.y2player.skin

import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.state.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SkinV2Test {
    private fun manifest(nodes: String = "[]") = JSONObject("""{
        "formatVersion":2,"id":"test-v2","name":"Test","viewport":[480,360],
        "colors":{"background":"#000000","surface":"#222222","primaryText":"#FFFFFF",
        "secondaryText":"#CCCCCC","accent":"#FFAA33","focusSurface":"#444444","warning":"#FF3333"},
        "screens":{"default":$nodes}}
    """)
    private fun parse(nodes: String) = SkinParser.parse(manifest(nodes).toString())
    private fun invalid(nodes: String) { assertThrows(IllegalArgumentException::class.java) { parse(nodes) } }
    private fun floats(array: JSONArray) = (0 until array.length()).map { array.getDouble(it).toFloat() }
    private fun bounds(b: SkinRect) = listOf(b.x, b.y, b.width, b.height)
    private fun near(expected: JSONArray, actual: List<Float>) {
        assertEquals(expected.length(), actual.size)
        actual.forEachIndexed { i, f -> assertEquals(expected.getDouble(i).toFloat(), f, .001f) }
    }
    private fun rect(array: JSONArray) = floats(array).let { SkinRect(it[0], it[1], it[2], it[3]) }

    @Test fun sharedStylesAndTypedParametersCompileIntoIndependentInstances() {
        val json = manifest("""[
            {"type":"component","bounds":[0,0,80,40],"asset":"button","props":{"label":"Play","width":80}},
            {"type":"component","bounds":[100,0,120,40],"asset":"button","props":{"width":120}}
        ]""")
        json.put("styles", JSONObject("""{"label":{"color":"accent","size":18}}"""))
        json.put("components", JSONObject("""{"button":{"parameters":{"label":"Default","width":60},"nodes":[
            {"type":"text","style":"label","bounds":[0,0,"{param.width}",40],"text":"{param.label}"}
        ]}}"""))
        val nodes = SkinParser.parse(json.toString()).screen("default")
        assertEquals("Play", nodes[0].children.single().text)
        assertEquals(80f, nodes[0].children.single().bounds.width)
        assertEquals("Default", nodes[1].children.single().text)
        assertEquals(120f, nodes[1].children.single().bounds.width)
        assertEquals("accent", nodes[1].children.single().color)
        json.getJSONObject("screens").getJSONArray("default").getJSONObject(0).getJSONObject("props").put("width", "wide")
        assertThrows(IllegalArgumentException::class.java) { SkinParser.parse(json.toString()) }
    }

    @Test fun richerDrawingPropertiesAndStateOverridesAreValidated() {
        val nodes = parse("""[
            {"type":"circle","bounds":[0,0,80,80],"gradient":{"endColor":"accent"},"borderWidth":2,"opacity":0.5},
            {"type":"path","bounds":[80,0,80,80],"points":[[0,1],[0.5,0],[1,1]],"closed":true},
            {"type":"text","bounds":[0,100,200,60],"font":"serif","lineSpacing":1.5,"overflow":"marquee","states":{"focused":{"color":"accent","size":22}},"transitionMs":150},
            {"type":"progress","bounds":[300,0,20,200],"orientation":"vertical","seekable":true},
            {"type":"keyboard","bounds":[0,200,300,120],"keyStyle":{"radius":5,"gap":3,"focusColor":"#000000","focusBackground":"accent"}}
        ]""").screen("default")
        assertEquals(22f, nodes[2].states.getValue("focused").apply(nodes[2]).size)
        invalid("""[{"type":"rect","bounds":[0,0,10,10],"states":{"focused":{"action":"home"}}}]""")
        invalid("""[{"type":"path","bounds":[0,0,10,10],"points":[[0,0],[2,1]]}]""")
        invalid("""[{"type":"rect","bounds":[0,0,10,10],"opacity":1.1}]""")
        invalid("""[{"type":"rect","bounds":[0,0,10,10],"transitionMs":501}]""")
        invalid("""[{"type":"text","bounds":[0,0,10,10],"font":"../font.ttf"}]""")
    }

    @Test fun navigationRejectsDuplicateDanglingAndRepeatedIds() {
        invalid("""[{"type":"rect","bounds":[0,0,10,10],"action":"home","focus":{"id":"same"}},
            {"type":"rect","bounds":[20,0,10,10],"action":"back","focus":{"id":"same"}}]""")
        invalid("""[{"type":"rect","bounds":[0,0,10,10],"action":"home","focus":{"id":"one","next":"missing"}}]""")
        invalid("""[{"type":"rows","bounds":[0,0,100,100],"children":[{"type":"rect","bounds":[0,0,10,10],"action":"home","focus":{"id":"repeated"}}]}]""")
    }

    @Test fun focusWrapGroupsExplicitNeighborsAndDisappearingControls() {
        val focus = SkinFocusController()
        val items = listOf(SkinFocus("play", "transport", 0, next="next"), SkinFocus("next", "transport", 1), SkinFocus("volume", "audio", 0))
        focus.update(items, SkinNavigation("play"))
        focus.move(1); assertEquals("next", focus.selected)
        focus.moveGroup(1); assertEquals("volume", focus.selected)
        focus.enterRows(); assertTrue(focus.leaveRows()); assertFalse(focus.leaveRows())
        focus.update(items.filterNot { it.id == "volume" }, SkinNavigation("play", false))
        assertEquals("play", focus.selected)
        focus.move(-999); assertEquals("play", focus.selected)
        focus.move(999); assertEquals("next", focus.selected)
        focus.update(emptyList(), SkinNavigation()); assertNull(focus.selected)
    }

    @Test fun seekingClampsAndKeepsSearchAndRadioLocked() {
        val state = AppState(playback = PlaybackSnapshot(currentTrackId=1, positionMs=1000, durationMs=10000))
        assertEquals(listOf(AppEffect.SeekBy(6500)), AppReducer.reduce(state, AppAction.SeekFraction(.75f)).effects)
        assertEquals(listOf(AppEffect.SeekBy(9000)), AppReducer.reduce(state, AppAction.SeekFraction(5f)).effects)
        assertTrue(AppReducer.reduce(state, AppAction.SeekFraction(Float.NaN)).effects.isEmpty())
        for (screen in listOf(Screen.Search(), Screen.FmRadio)) {
            val locked = state.copy(screenStack=listOf(ScreenEntry(screen)))
            assertTrue(AppReducer.reduce(locked, AppAction.SeekFraction(.5f)).effects.isEmpty())
            assertTrue(AppReducer.reduce(locked, AppAction.SkinCommand("shuffle")).effects.isEmpty())
        }
        assertTrue(AppReducer.reduce(state, AppAction.SkinCommand("execute")).effects.isEmpty())
        assertEquals(listOf(AppEffect.AdjustVolume(1)), AppReducer.reduce(state, AppAction.SkinCommand("volumeUp")).effects)
    }

    @Test fun androidAndBrowserConsumeTheSameGeometryAndTextFixtures() {
        val fixtures = JSONObject(File("../docs/skins/preview-fixtures.json").readText())
        fun each(key: String, block: (JSONObject) -> Unit) { val a=fixtures.getJSONArray(key); for(i in 0 until a.length()) block(a.getJSONObject(i)) }
        each("lists") { f ->
            val node = parse(JSONArray().put(f.getJSONObject("node")).toString()).screen("default").single()
            assertEquals(f.getInt("capacity"), SkinLayout.capacity(node))
            assertEquals(f.getInt("first"), SkinLayout.firstVisible(node, f.getInt("selected")))
            near(f.getJSONArray("cell"), bounds(SkinLayout.cell(node, f.getInt("slot"))))
        }
        each("images") { f -> near(f.getJSONArray("result"), bounds(SkinLayout.imageBounds(rect(f.getJSONArray("box")), f.getInt("width"), f.getInt("height"), f.getString("fit"), f.getDouble("x").toFloat(), f.getDouble("y").toFloat()))) }
        each("seek") { f -> assertEquals(f.getDouble("result").toFloat(), SkinLayout.seekFraction(rect(f.getJSONArray("box")), f.getString("orientation"), f.getDouble("x").toFloat(), f.getDouble("y").toFloat()), .001f) }
        each("layouts") { f ->
            val node = parse(JSONArray().put(f.getJSONObject("node")).toString()).screen("default").single()
            SkinLayout.children(node).forEachIndexed { i, n -> near(f.getJSONArray("result").getJSONArray(i), bounds(n.bounds)) }
        }
        each("text") { f ->
            val expected=f.getJSONArray("result").let { a -> (0 until a.length()).map(a::getString) }
            assertEquals(expected, SkinTextLayout.lines(f.getString("source"), f.getDouble("width").toFloat(), f.getInt("count"), f.getBoolean("ellipsis")) { it.codePointCount(0,it.length).toFloat() })
        }
    }

    @Test fun schemaListsEveryEngineNodeProperty() {
        val schema=JSONObject(File("../docs/skins/skin-v2.schema.json").readText())
        val keys=schema.getJSONObject("\$defs").getJSONObject("node").getJSONObject("properties").keys().asSequence().toSet()
        assertEquals(SkinParser.v2Fields + setOf("style", "props"),keys)
    }

    @Test fun resizedContainersCannotHideAnExpandedRowBudget() {
        val children=JSONArray()
        repeat(40) { children.put(JSONObject("""{"type":"rect","bounds":[0,0,10,10]}""")) }
        val rows=JSONObject("""{"type":"rows","bounds":[0,0,20,20],"anchor":"stretch","rowHeight":20,"columns":8,"gap":0}""").put("children",children)
        assertThrows(IllegalArgumentException::class.java) { SkinParser.parse(manifest(JSONArray().put(rows).toString()).toString()) }
    }

    @Test fun documentedExampleCompilesAndHasUniqueFocusTargets() {
        val skin=SkinParser.parse(File("../docs/skins/example-v2/skin.json").readText())
        assertEquals(2,skin.formatVersion)
        assertEquals("play",skin.navigation.getValue("now_playing").initial)
        invalid("""[{"type":"rows","bounds":[0,0,100,48],"children":[{"type":"rows","bounds":[0,0,100,48]}]}]""")
        invalid("""[{"type":"rect","bounds":[0,0,100,48],"disabled":"true"}]""")
    }

    @Test fun bundledSkinsUseV2AndCoverEveryScreen() {
        for (id in listOf("neon-grid", "pixel-garden", "studio-deck")) {
            val skin = SkinParser.parse(File("src/main/assets/skins/$id/skin.json").readText())
            assertEquals(2, skin.formatVersion)
            assertEquals("play", skin.navigation.getValue("now_playing").initial)
            ScreenCatalogue.all().forEach { assertTrue(skin.screen(it.code).isNotEmpty()) }
        }
    }
}

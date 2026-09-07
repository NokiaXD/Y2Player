package com.schulzcode.y2player.skin

import com.schulzcode.y2player.backup.PreferenceBackup
import com.schulzcode.y2player.core.state.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SkinEngineTest {
    private fun source() = File("src/main/assets/skins/neon-grid/skin.json").readText()
    private fun rows(json: JSONObject, screen: String): JSONObject {
        val nodes = json.getJSONObject("screens").getJSONArray(screen)
        return (0 until nodes.length()).map(nodes::getJSONObject).single { it.getString("type") == "rows" }
    }
    private fun descendants(nodes: List<SkinNode>): List<SkinNode> =
        nodes + nodes.flatMap { descendants(it.children) }
    private fun rejects(change: (JSONObject) -> Unit) {
        val json = JSONObject(source()).also(change)
        assertThrows(Exception::class.java) { SkinParser.parse(json.toString()) }
    }

    @Test fun bundledSkinCoversEveryScreenAndUsesItsOwnLayouts() {
        val skin = SkinParser.parse(source())
        assertEquals("neon-grid", skin.id)
        com.schulzcode.y2player.core.state.ScreenCatalogue.all().forEach { screen ->
            assertTrue("${screen.code} has no skin", skin.screen(screen.code).isNotEmpty())
        }
        assertTrue(skin.screens.keys.containsAll(listOf("default", "main_menu", "now_playing", "search", "fm_radio")))
    }

    @Test fun pixelGardenUsesV2CardsStatesAndFocusedPlaybackControls() {
        val skin = SkinParser.parse(File("src/main/assets/skins/pixel-garden/skin.json").readText())
        assertEquals("pixel-garden", skin.id)
        assertEquals(2, skin.formatVersion)
        ScreenCatalogue.all().forEach { screen ->
            assertTrue(skin.screen(screen.code).isNotEmpty())
        }
        val menu = skin.screen("main_menu").single { it.type == "rows" }
        assertEquals(4, SkinLayout.capacity(menu))
        assertEquals("center", menu.scrolling)
        assertTrue(descendants(skin.components.getValue("row")).any { "focused" in it.states })
        assertTrue(descendants(skin.components.getValue("row")).any { it.whenState == "active" })
        assertEquals("play", skin.navigation.getValue("now_playing").initial)
        val focusIds = descendants(skin.screen("now_playing")).mapNotNull { it.focus?.id }.toSet()
        assertEquals(setOf("previous", "play", "next", "shuffle", "repeat", "options"), focusIds)
    }

    @Test fun studioDeckUsesCarouselAndConsoleNavigation() {
        val skin = SkinParser.parse(File("src/main/assets/skins/studio-deck/skin.json").readText())
        assertEquals("studio-deck", skin.id)
        assertEquals(2, skin.formatVersion)
        ScreenCatalogue.all().forEach { assertTrue(skin.screen(it.code).isNotEmpty()) }
        val home = skin.screen("main_menu").single { it.type == "rows" }
        assertEquals("carousel", home.listMode)
        assertEquals(3, SkinLayout.capacity(home))
        assertEquals(5, SkinLayout.firstVisible(home, 6))
        val seventh = SkinLayout.cell(home, 1)
        assertEquals(6, SkinLayout.hit(home, seventh.x + 1, seventh.y + 1, 6, 7))
        val vacant = SkinLayout.cell(home, 2)
        assertNull(SkinLayout.hit(home, vacant.x + 1, vacant.y + 1, 6, 7))
        val playback = descendants(skin.screen("now_playing"))
        assertEquals(setOf("previous", "play", "next", "quieter", "louder", "options"),
            playback.mapNotNull { it.focus?.id }.toSet())
        assertTrue(playback.any { it.type == "progress" && it.orientation == "circular" && it.seekable })
        val search = skin.screen("search")
        val keyboard = search.single { it.type == "keyboard" }.bounds
        val results = search.single { it.type == "rows" }.bounds
        assertTrue(results.y + results.height <= keyboard.y)
    }

    @Test fun rejectsUnsupportedVersionsBindingsAndComponentCycles() {
        rejects { it.put("formatVersion", 3) }
        rejects { it.put("id", "../bad") }
        rejects { it.put("typo", true) }
        rejects { it.getJSONObject("components").getJSONArray("header").getJSONObject(1).put("text", "{track.typo}") }
        rejects { it.getJSONObject("components").put("header", org.json.JSONArray("""[{"type":"component","bounds":[0,0,480,360],"asset":"header"}]""")) }
        rejects { it.getJSONObject("screens").remove("default") }
    }

    @Test fun rejectsInvisibleOrInvalidRowGeometry() {
        rejects { rows(it, "main_menu").put("rowHeight", 500) }
        rejects { rows(it, "main_menu").put("columns", 0) }
        rejects { rows(it, "main_menu").put("rowHeight", "NaN") }
    }

    @Test fun gridDrawingAndHitTestingAgreeAcrossPagesAndGaps() {
        val node = SkinNode("rows", SkinRect(12f, 48f, 456f, 264f), columns = 2, rowHeight = 78f, gap = 12f)
        assertEquals(6, SkinLayout.capacity(node))
        assertEquals(6, SkinLayout.firstVisible(node, 6))
        for (selected in 0 until 19) {
            val first = SkinLayout.firstVisible(node, selected)
            for (slot in 0 until minOf(6, 19 - first)) {
                val cell = SkinLayout.cell(node, slot)
                assertEquals(first + slot, SkinLayout.hit(node, cell.x + cell.width / 2, cell.y + cell.height / 2, selected, 19))
            }
        }
        assertNull(SkinLayout.hit(node, 236f, 60f, 0, 19))
        assertNull(SkinLayout.hit(node, 20f, 130f, 0, 19))
        assertNull(SkinLayout.hit(node, -1f, 60f, 0, 19))
        assertNull(SkinLayout.hit(node, 300f, 60f, 18, 19))
    }

    @Test fun selectingAndReloadingSkinsUsesValidatedCatalog() {
        val catalog = SkinCatalogState(available = SkinCatalogState().available + SkinSummary("neon-grid", "Neon Grid"))
        var state = AppState(screenStack = listOf(ScreenEntry(Screen.Skins)), skins = catalog)
        fun select(key: String): Reduction {
            val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
            assertTrue(index >= 0)
            return AppReducer.reduce(AppReducer.reduce(state, AppAction.SelectIndex(index)).state, AppAction.Confirm)
        }
        assertEquals(listOf(AppEffect.SetSkin("neon-grid")), select("skin:neon-grid").effects)
        assertEquals(listOf(AppEffect.ReloadSkins), select("reload_skins").effects)
        state = AppReducer.reduce(state, AppAction.SkinsChanged(SkinCatalogState())).state
        assertFalse(ScreenContent.rows(state).any { (it as? ScreenRow.Action)?.key == "skin:neon-grid" })
    }

    @Test fun backupsMigrateOldLightThemeAndPreserveMissingCustomSkinIds() {
        val old = PreferenceBackup.encode(PlayerPreferencesState(lightTheme = true)).toMutableMap().apply { remove("skin_id") }
        assertEquals("classic-light", PreferenceBackup.decode(old).skinId)
        val custom = PlayerPreferencesState(skinId = "community.example")
        assertEquals(custom, PreferenceBackup.decode(PreferenceBackup.encode(custom)))
        old["skin_id"] = "../invalid"
        assertThrows(IllegalArgumentException::class.java) { PreferenceBackup.decode(old) }
    }

    @Test fun assetPathsCannotEscapePack() {
        listOf("../a.png", "/tmp/a.png", "a/../b", "a\\b", "./a").forEach { assertFalse(SkinParser.safeAsset(it)) }
        assertTrue(SkinParser.safeAsset("images/background.png"))
    }
    @Test fun expandedComponentGraphsHaveABoundedRenderingCost() {
        rejects { json ->
            val components = json.getJSONObject("components")
            for (level in 0..11) {
                val children = org.json.JSONArray()
                repeat(2) {
                    children.put(JSONObject().put("type", "component").put("bounds", org.json.JSONArray("[0,0,480,360]")).put("asset", if (level == 11) "row" else "branch${level + 1}"))
                }
                components.put("branch$level", children)
            }
            json.getJSONObject("screens").put("default", org.json.JSONArray("""[{"type":"component","bounds":[0,0,480,360],"asset":"branch0"}]"""))
        }
    }

    @Test fun symlinkedAssetsCannotReadOutsideTheSkinFolder() {
        val root = java.nio.file.Files.createTempDirectory("skin-path-test").toFile()
        try {
            val pack = File(root, "pack").apply { mkdir() }
            val outside = File(root, "outside.png").apply { writeText("test") }
            java.nio.file.Files.createSymbolicLink(File(pack, "escape.png").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { SkinRepository.assetFile(pack, "escape.png") }
            val valid = File(pack, "inside.png").apply { writeText("test") }
            assertEquals(valid.canonicalFile, SkinRepository.assetFile(pack, "inside.png"))
        } finally { root.deleteRecursively() }
    }

}

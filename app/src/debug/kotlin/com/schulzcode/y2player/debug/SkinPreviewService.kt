package com.schulzcode.y2player.debug

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.IBinder
import com.schulzcode.y2player.core.model.*
import com.schulzcode.y2player.core.state.*
import com.schulzcode.y2player.skin.*
import org.json.JSONObject
import java.io.File

/** Debug-only device contract probe. Uses synthetic state and never dispatches to the player. */
class SkinPreviewService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread({
            val output = File(getExternalFilesDir(null), "skin-probe").apply { mkdirs() }
            val report = runCatching {
                val skin = SkinParser.parse(File(output, "skin.json").readText())
                val fonts = listOf("sans", "serif", "monospace").associateWith { Typeface.create(it, Typeface.NORMAL) }
                val loaded = LoadedSkin(skin, fonts.getValue("sans"), emptyMap(), fonts)
                val bitmap = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val actions = mutableListOf<AppAction>()
                val renderer = SkinRenderer(actions::add).apply { animationsEnabled = false }
                val track = Track(1, "test", "/test.flac", "test.flac", "A Different Kind of Blue", "The Evening Quartet", "After Hours", null, 1, 1, 240000, 0, 0, codec="FLAC", sampleRate=44100, bitDepth=16)
                val state = AppState(library=LibraryState(listOf(track)), playback=PlaybackSnapshot(currentTrackId=1, durationMs=240000, positionMs=60000, status=PlaybackStatus.PLAYING), screenStack=listOf(ScreenEntry(Screen.NowPlaying)))
                fun draw(value: AppState=state) { renderer.draw(canvas, 480, 360, loaded, value, ScreenContent.rows(value), null, 65) }
                draw()
                File(output, "now-playing.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                check(renderer.handleInput(AppAction.Confirm)); check(actions.removeAt(0)==AppAction.PlayPause)
                check(renderer.handleInput(AppAction.WheelMoved(1))); draw()
                check(renderer.handleInput(AppAction.Confirm)); check(actions.removeAt(0)==AppAction.MediaNext)
                renderer.press(390f,240f); draw(); renderer.release(390f,240f)
                check(actions.removeAt(0)==AppAction.MediaNext)
                renderer.press(110f,200f); renderer.release(110f,200f)
                check(kotlin.math.abs((actions.removeAt(0) as AppAction.SeekFraction).fraction-.5f)<.01f)
                check(!renderer.handleInput(AppAction.Back))
                draw(state.copy(screenStack=listOf(ScreenEntry(Screen.Search()))))
                check(!renderer.handleInput(AppAction.WheelMoved(1)))
                File(output,"search.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                val catalog = SkinRepository.load(this)
                check(catalog.skins.keys.containsAll(listOf("neon-grid","pixel-garden","studio-deck")))
                for ((id, entry) in catalog.skins) for (screen in listOf(Screen.MainMenu, Screen.NowPlaying, Screen.Songs, Screen.Search(), Screen.FmRadio)) {
                    val value=state.copy(screenStack=listOf(ScreenEntry(screen)))
                    renderer.draw(canvas,480,360,entry,value,ScreenContent.rows(value),null)
                    File(output, "$id-${screen.code}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                bitmap.recycle()
                JSONObject().put("passed",true).put("checks","v2 render, wheel focus, confirm, touch across redraw, circular seek, search navigation, 15 bundled v2 screen renders")
            }.getOrElse { JSONObject().put("passed",false).put("error",it.stackTraceToString()) }
            File(output,"report.json").writeText(report.toString(2))
            stopSelf(startId)
        }, "skin-preview-probe").start()
        return START_NOT_STICKY
    }
}

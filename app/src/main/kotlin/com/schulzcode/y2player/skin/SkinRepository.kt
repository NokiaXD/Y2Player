package com.schulzcode.y2player.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.io.File
import java.io.InputStream

class LoadedSkin(val definition: Skin, val font: Typeface, val images: Map<String, Bitmap>, val fonts: Map<String, Typeface> = emptyMap())
data class SkinLoadResult(val skins: Map<String, LoadedSkin>, val errors: List<String>)

/** Loaded on a worker, published atomically on the UI thread. No disk reads during drawing. */
object SkinRepository {
    var skins: Map<String, LoadedSkin> = emptyMap()
        private set
    var catalog = SkinCatalogState()
        private set

    fun publish(result: SkinLoadResult): SkinCatalogState {
        skins = result.skins
        catalog = SkinCatalogState(SkinCatalogState().available + skins.values.map {
            SkinSummary(it.definition.id, it.definition.name, it.definition.author)
        }, result.errors, catalog.revision + 1)
        return catalog
    }

    fun load(context: Context): SkinLoadResult {
        val loaded = linkedMapOf<String, LoadedSkin>()
        val errors = mutableListOf<String>()
        fun loadOne(label: String, open: (String) -> InputStream, font: (String) -> Typeface) {
            try {
                val source = open("skin.json").use { readBounded(it, SkinParser.MAX_BYTES).toString(Charsets.UTF_8) }
                val skin = SkinParser.parse(source)
                require(skin.id !in loaded) { "Duplicate id: ${skin.id}" }
                val images = linkedMapOf<String, Bitmap>()
                var decodedBytes = 0L
                val fonts = linkedMapOf<String, Typeface>()
                fun loadFont(name: String) {
                    if (name.isEmpty() || name in fonts) return
                    require(fonts.size < 16) { "At most 16 fonts per skin" }
                    fonts[name] = if (name in setOf("sans", "sans-serif", "serif", "monospace")) Typeface.create(name, Typeface.NORMAL) else font(name)
                }
                loadFont(skin.font)
                fun collect(nodes: List<SkinNode>) {
                    nodes.forEach { node ->
                        loadFont(node.font)
                        if (node.type == "image" && node.asset !in images) {
                            val bytes = open(node.asset).use { readBounded(it, 2 * 1024 * 1024) }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) { "Image ${node.asset} exceeds 1024 pixels or is invalid" }
                            decodedBytes += bounds.outWidth.toLong() * bounds.outHeight * 4
                            require(decodedBytes <= 8 * 1024 * 1024) { "Images exceed 8 MiB decoded" }
                            images[node.asset] = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Cannot decode ${node.asset}")
                        }
                        collect(node.children)
                    }
                }
                skin.screens.values.forEach(::collect)
                skin.components.values.forEach(::collect)
                val face = fonts.getValue(skin.font)
                val totalBytes = loaded.values.sumOf { entry -> entry.images.values.sumOf { it.byteCount.toLong() } } + decodedBytes
                require(totalBytes <= 24 * 1024 * 1024) { "Installed skin images exceed the 24 MiB budget" }
                loaded[skin.id] = LoadedSkin(skin, face, images, fonts)
            } catch (error: Exception) {
                errors += "$label: ${error.message ?: "Invalid skin"}"
            }
        }
        loadOne("Neon Grid", { context.assets.open("skins/neon-grid/$it") }, { Typeface.createFromAsset(context.assets, "skins/neon-grid/$it") })
        loadOne("Pixel Garden", { context.assets.open("skins/pixel-garden/$it") }, { Typeface.createFromAsset(context.assets, "skins/pixel-garden/$it") })
        loadOne("Studio Deck", { context.assets.open("skins/studio-deck/$it") }, { Typeface.createFromAsset(context.assets, "skins/studio-deck/$it") })
        val directories = Y2StoragePaths.availableRoots().flatMap { root ->
            File(root.directory, "Y2Player/Skins").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        }
        directories.take(32).forEach { directory ->
            loadOne(directory.name, { name -> assetFile(directory, name).inputStream() }, { name ->
                val file = assetFile(directory, name)
                require(file.length() <= 2 * 1024 * 1024) { "Font exceeds 2 MiB" }
                Typeface.createFromFile(file)
            })
        }
        if (directories.size > 32) errors += "Only the first 32 external skin folders were loaded"
        return SkinLoadResult(loaded, errors)
    }

    internal fun assetFile(directory: File, name: String): File {
        require(SkinParser.safeAsset(name)) { "Invalid asset path" }
        val root = directory.canonicalFile
        val file = File(root, name).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Asset escapes skin directory" }
        require(file.isFile) { "Missing asset: $name" }
        return file
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "File exceeds $limit bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

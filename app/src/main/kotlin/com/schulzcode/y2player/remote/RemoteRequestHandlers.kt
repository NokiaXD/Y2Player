package com.schulzcode.y2player.remote

import android.graphics.Bitmap
import android.util.Base64
import com.schulzcode.y2player.artwork.AlbumArtworkLoader
import com.schulzcode.y2player.core.model.LibraryOrganization
import com.schulzcode.y2player.core.model.LibraryScope
import com.schulzcode.y2player.queue.QueueSnapshot
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.core.model.YearSortOrder
import com.schulzcode.y2player.library.LibraryRepository
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

interface PlaybackQueueProxy {
    fun getQueueSnapshot(): QueueSnapshot
    fun getQueueRevision(): Long
    fun replaceQueue(trackIds: List<Long>, startIndex: Int, shuffled: Boolean)
    fun playNext(trackIds: List<Long>)
    fun addToUpNext(trackIds: List<Long>)
    fun removeQueueEntry(entryId: Long)
    fun moveQueueEntry(entryId: Long, delta: Int)
    fun promoteQueueEntry(entryId: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun clearUpNext()
    fun clearRemaining()
    fun clearQueue()
}

class RemoteRequestHandlers(
    private val libraryRepository: LibraryRepository,
    private val artworkLoader: AlbumArtworkLoader,
    private val queueProxy: PlaybackQueueProxy?
) {

    private data class PageCacheKey(val scope: String, val sort: String, val query: String)
    private val pageCache = ConcurrentHashMap<PageCacheKey, List<TrackRow>>()

    init {
        libraryRepository.addListener(LibraryRepository.Listener { pageCache.clear() }, emitImmediately = false)
    }

    fun Track.toTrackRow(): TrackRow = TrackRow(
        id = id,
        title = title,
        artist = displayArtist,
        album = displayAlbum,
        durationMs = durationMs,
        favorite = favorite,
        hasArtwork = hasArtwork
    )

    private fun parseScope(scopeStr: String, org: LibraryOrganization): LibraryScope {
        val trimmed = scopeStr.trim()
        return when {
            trimmed.isEmpty() || trimmed.equals("all", ignoreCase = true) -> LibraryScope.All
            trimmed.startsWith("genre:", ignoreCase = true) -> {
                val key = trimmed.substring(6)
                org.genres().firstOrNull { it.key.equals(key, ignoreCase = true) || it.label.equals(key, ignoreCase = true) }
                    ?.let { LibraryScope.Genre(it.key, it.label) }
                    ?: LibraryScope.Genre(key, key)
            }
            trimmed.startsWith("year:", ignoreCase = true) -> {
                val year = trimmed.substring(5).toIntOrNull()
                LibraryScope.Year(year)
            }
            else -> LibraryScope.All
        }
    }

    fun handleLibraryPage(req: RemoteMessage.LibraryPage): RemoteMessage.LibraryPageResult {
        val cacheKey = PageCacheKey(req.scope, req.sort, req.query.trim().lowercase())
        val sorted = pageCache.getOrPut(cacheKey) {
            val snapshot = libraryRepository.snapshot()
            val org = LibraryOrganization(snapshot.tracks)
            val libraryScope = parseScope(req.scope, org)
            val sortOrder = TrackSortOrder.fromStorage(req.sort)

            var tracks = org.tracks(libraryScope)
            tracks = org.sortTracks(tracks, sortOrder)

            if (req.query.isNotBlank()) {
                val q = req.query.trim().lowercase()
                tracks = tracks.filter { track ->
                    track.title.lowercase().contains(q) ||
                        track.displayArtist.lowercase().contains(q) ||
                        track.displayAlbum.lowercase().contains(q)
                }
            }
            tracks.map { it.toTrackRow() }
        }

        val total = sorted.size
        val offset = req.offset.coerceIn(0, total)
        val limit = req.limit.coerceIn(1, 100)
        val end = (offset + limit).coerceAtMost(total)
        val sliced = if (offset < total) sorted.subList(offset, end) else emptyList()

        return RemoteMessage.LibraryPageResult(
            requestId = req.requestId,
            total = total,
            hasMore = end < total,
            rows = sliced
        )
    }

    fun handleLibrarySummary(req: RemoteMessage.LibrarySummaryRequest): RemoteMessage.LibrarySummary {
        val snapshot = libraryRepository.snapshot()
        val org = LibraryOrganization(snapshot.tracks)

        val genres = org.genres().map { GenreCount(key = it.key, label = it.label, count = it.tracks.size) }
        val years = org.years(YearSortOrder.NEWEST_FIRST).map { YearCount(year = it.year, count = it.tracks.size) }

        return RemoteMessage.LibrarySummary(
            genres = genres,
            years = years,
            total = snapshot.tracks.size,
            requestId = req.requestId
        )
    }

    fun handleLibraryArtworkAsync(
        req: RemoteMessage.LibraryArtworkRequest,
        onComplete: (RemoteMessage.LibraryArtworkResult) -> Unit
    ) {
        val track = libraryRepository.findTrack(req.trackId)
        if (track == null || !track.hasArtwork) {
            onComplete(RemoteMessage.LibraryArtworkResult(
                trackId = req.trackId,
                base64 = "",
                width = 0,
                height = 0,
                requestId = req.requestId
            ))
            return
        }

        artworkLoader.load(track.absolutePath, 128) { _, bitmap ->
            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                onComplete(RemoteMessage.LibraryArtworkResult(
                    trackId = req.trackId,
                    base64 = base64,
                    width = bitmap.width,
                    height = bitmap.height,
                    requestId = req.requestId
                ))
            } else {
                onComplete(RemoteMessage.LibraryArtworkResult(
                    trackId = req.trackId,
                    base64 = "",
                    width = 0,
                    height = 0,
                    requestId = req.requestId
                ))
            }
        }
    }

    fun handlePlaylistsList(req: RemoteMessage.PlaylistsListRequest): RemoteMessage.PlaylistsList {
        val snapshot = libraryRepository.snapshot()
        val items = snapshot.playlists.map { PlaylistRow(it.id, it.name, it.trackCount) }
        return RemoteMessage.PlaylistsList(items = items, requestId = req.requestId)
    }

    fun handlePlaylistsTracks(req: RemoteMessage.PlaylistsTracksRequest): RemoteMessage.PlaylistsTracks {
        val snapshot = libraryRepository.snapshot()
        val trackIds = snapshot.playlistTrackIds[req.playlistId].orEmpty()
        val tracks = trackIds.mapNotNull { libraryRepository.findTrack(it) }
        val total = tracks.size
        val offset = req.offset.coerceIn(0, total)
        val limit = req.limit.coerceIn(1, 100)
        val end = (offset + limit).coerceAtMost(total)
        val sliced = if (offset < total) tracks.subList(offset, end) else emptyList()

        return RemoteMessage.PlaylistsTracks(
            playlistId = req.playlistId,
            rows = sliced.map { it.toTrackRow() },
            total = total,
            hasMore = end < total,
            requestId = req.requestId
        )
    }

    fun handlePlaylistsCreate(req: RemoteMessage.PlaylistsCreateRequest): RemoteMessage.PlaylistsMutate {
        libraryRepository.createPlaylist(req.name)
        val updated = libraryRepository.snapshot().playlists.firstOrNull { it.name == req.name }
        return RemoteMessage.PlaylistsMutate(
            ok = true,
            playlist = updated?.let { PlaylistRow(it.id, it.name, it.trackCount) },
            requestId = req.requestId
        )
    }

    fun handlePlaylistsRename(req: RemoteMessage.PlaylistsRenameRequest): RemoteMessage.PlaylistsMutate {
        libraryRepository.renamePlaylist(req.playlistId, req.name)
        val updated = libraryRepository.snapshot().playlists.firstOrNull { it.id == req.playlistId }
        return RemoteMessage.PlaylistsMutate(
            ok = true,
            playlist = updated?.let { PlaylistRow(it.id, it.name, it.trackCount) },
            requestId = req.requestId
        )
    }

    fun handlePlaylistsDelete(req: RemoteMessage.PlaylistsDeleteRequest): RemoteMessage.PlaylistsMutate {
        libraryRepository.deletePlaylist(req.playlistId)
        return RemoteMessage.PlaylistsMutate(
            ok = true,
            playlist = null,
            requestId = req.requestId
        )
    }

    fun handlePlaylistsAddTrack(req: RemoteMessage.PlaylistsAddTrackRequest): RemoteMessage.PlaylistsMutate {
        libraryRepository.addTrackToPlaylist(req.playlistId, req.trackId)
        val updated = libraryRepository.snapshot().playlists.firstOrNull { it.id == req.playlistId }
        return RemoteMessage.PlaylistsMutate(
            ok = true,
            playlist = updated?.let { PlaylistRow(it.id, it.name, it.trackCount) },
            requestId = req.requestId
        )
    }

    fun handlePlaylistsRemoveTrack(req: RemoteMessage.PlaylistsRemoveTrackRequest): RemoteMessage.PlaylistsMutate {
        libraryRepository.removeTrackFromPlaylist(req.playlistId, req.trackId)
        val updated = libraryRepository.snapshot().playlists.firstOrNull { it.id == req.playlistId }
        return RemoteMessage.PlaylistsMutate(
            ok = true,
            playlist = updated?.let { PlaylistRow(it.id, it.name, it.trackCount) },
            requestId = req.requestId
        )
    }

    fun handleQueueState(req: RemoteMessage.QueueStateRequest): RemoteMessage.QueueState {
        val proxy = queueProxy
        if (proxy == null) {
            return RemoteMessage.QueueState(
                requestId = req.requestId,
                entries = emptyList(),
                currentEntryId = null,
                repeatMode = "OFF",
                shuffleEnabled = false,
                revision = 0L,
                totalCount = 0,
                offset = 0
            )
        }
        val queueSnapshot = proxy.getQueueSnapshot()
        val revision = proxy.getQueueRevision()
        val visible = if (queueSnapshot.visibleEntries.isNotEmpty()) queueSnapshot.visibleEntries else queueSnapshot.entries
        val totalCount = visible.size
        val limit = if (req.limit in 1..200) req.limit else 20
        val offset = req.offset.coerceIn(0, totalCount)
        val end = (offset + limit).coerceAtMost(totalCount)
        val window = if (offset < totalCount) visible.subList(offset, end) else emptyList()

        val entries = window.map { entry ->
            val track = libraryRepository.findTrack(entry.trackId)
            QueueEntryRow(
                entryId = entry.id,
                trackId = entry.trackId,
                origin = entry.origin.name.lowercase(),
                track = track?.toTrackRow()
            )
        }
        return RemoteMessage.QueueState(
            requestId = req.requestId,
            entries = entries,
            currentEntryId = queueSnapshot.currentEntryId,
            repeatMode = queueSnapshot.repeatMode.name,
            shuffleEnabled = queueSnapshot.shuffleEnabled,
            revision = revision,
            totalCount = totalCount,
            offset = offset
        )
    }

    fun handleQueueReplace(req: RemoteMessage.QueueReplaceRequest): RemoteMessage.QueueMutate {
        queueProxy?.replaceQueue(req.trackIds, req.startIndex, req.shuffled)
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueuePlayNext(req: RemoteMessage.QueuePlayNextRequest): RemoteMessage.QueueMutate {
        queueProxy?.playNext(req.trackIds)
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueAddToUpNext(req: RemoteMessage.QueueAddToUpNextRequest): RemoteMessage.QueueMutate {
        queueProxy?.addToUpNext(req.trackIds)
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueRemoveEntry(req: RemoteMessage.QueueRemoveEntryRequest): RemoteMessage.QueueMutate {
        queueProxy?.removeQueueEntry(req.entryId)
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueMoveEntry(req: RemoteMessage.QueueMoveEntryRequest): RemoteMessage.QueueMutate {
        queueProxy?.moveQueueEntry(req.entryId, req.delta)
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueuePromoteEntry(req: RemoteMessage.QueuePromoteEntryRequest): RemoteMessage.QueueMutate {
        queueProxy?.promoteQueueEntry(req.entryId)
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueShuffleToggle(req: RemoteMessage.QueueShuffleToggleRequest): RemoteMessage.QueueMutate {
        queueProxy?.toggleShuffle()
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueRepeatCycle(req: RemoteMessage.QueueRepeatCycleRequest): RemoteMessage.QueueMutate {
        queueProxy?.cycleRepeat()
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueClearUpNext(req: RemoteMessage.QueueClearUpNextRequest): RemoteMessage.QueueMutate {
        queueProxy?.clearUpNext()
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueClearRemaining(req: RemoteMessage.QueueClearRemainingRequest): RemoteMessage.QueueMutate {
        queueProxy?.clearRemaining()
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }

    fun handleQueueClear(req: RemoteMessage.QueueClearRequest): RemoteMessage.QueueMutate {
        queueProxy?.clearQueue()
        return RemoteMessage.QueueMutate(
            ok = true,
            revision = queueProxy?.getQueueRevision() ?: 0L,
            requestId = req.requestId
        )
    }
}

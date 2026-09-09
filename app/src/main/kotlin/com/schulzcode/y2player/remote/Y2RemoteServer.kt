package com.schulzcode.y2player.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class Y2RemoteServer(
    private val logger: DiagnosticLogger,
    private val stateProvider: () -> Triple<PlaybackSnapshot, Track?, Int>,
    private val onCommandReceived: (RemoteCommand) -> Unit,
    private val requestHandlers: RemoteRequestHandlers? = null,
    private val onClientConnectionChanged: (Boolean) -> Unit = {}
) {
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: AcceptThread? = null
    @Volatile private var connectedWorker: ConnectedWorker? = null
    private val requestExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "y2-remote-handler").apply { isDaemon = true }
    }

    val isClientConnected: Boolean
        get() = connectedWorker?.isConnected == true

    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) return
        logger.info("RemoteServer", "starting RFCOMM remote server")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            logger.warn("RemoteServer", "BluetoothAdapter is null, cannot start server")
            isRunning.set(false)
            return
        }
        val thread = AcceptThread(adapter)
        acceptThread = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        logger.info("RemoteServer", "stopping RFCOMM remote server")
        acceptThread?.cancel()
        acceptThread = null
        connectedWorker?.cancel()
        connectedWorker = null
        requestExecutor.shutdown()
        onClientConnectionChanged(false)
    }

    fun update(snapshot: PlaybackSnapshot, track: Track?, volumePercent: Int = 100) {
        val worker = connectedWorker ?: return
        if (!worker.isConnected) return

        val stateJson = RemoteProtocol.encodeSnapshot(snapshot, track, volumePercent)
        worker.send(stateJson)
    }

    fun sendArtwork(base64: String) {
        val worker = connectedWorker ?: return
        if (!worker.isConnected) return
        val json = RemoteProtocol.encodeArtwork(base64)
        worker.send(json)
    }

    fun pushEventQueueChanged(reason: String, revision: Long) {
        val worker = connectedWorker ?: return
        if (!worker.isConnected) return
        val json = RemoteProtocol.encodeEventQueueChanged(reason, revision)
        worker.send(json)
    }

    fun pushEventLibraryChanged(revision: Long) {
        val worker = connectedWorker ?: return
        if (!worker.isConnected) return
        val json = RemoteProtocol.encodeEventLibraryChanged(revision)
        worker.send(json)
    }

    private inner class AcceptThread(private val adapter: BluetoothAdapter) : Thread("y2-remote-accept") {
        private var serverSocket: BluetoothServerSocket? = null

        override fun run() {
            while (isRunning.get()) {
                try {
                    logger.info("RemoteServer", "listening for RFCOMM connection on UUID=${RemoteProtocol.SERVICE_UUID}")
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                        RemoteProtocol.SERVICE_NAME,
                        RemoteProtocol.SERVICE_UUID
                    )
                } catch (e: IOException) {
                    logger.error("RemoteServer", "failed to listen for RFCOMM: ${e.message}")
                    try { sleep(2000) } catch (_: InterruptedException) { break }
                    continue
                }

                val socket: BluetoothSocket = try {
                    serverSocket?.accept() ?: continue
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        logger.warn("RemoteServer", "accept failed: ${e.message}")
                    }
                    continue
                } finally {
                    try { serverSocket?.close() } catch (_: IOException) {}
                    serverSocket = null
                }

                logger.info("RemoteServer", "client connected from ${socket.remoteDevice?.address}")

                connectedWorker?.cancel()
                val worker = ConnectedWorker(socket)
                connectedWorker = worker
                worker.start()
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                logger.warn("RemoteServer", "error closing server socket: ${e.message}")
            }
        }
    }

    private inner class ConnectedWorker(
        private val socket: BluetoothSocket
    ) : Thread("y2-remote-worker") {
        @Volatile var isConnected = true
            private set

        private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
        private val writeLock = Any()

        override fun run() {
            try {
                val helloJson = RemoteProtocol.encodeHello(
                    device = "Y2 Player",
                    protocol = RemoteProtocol.PROTOCOL_VERSION
                )
                send(helloJson)

                val (currentSnapshot, currentTrack, currentVolume) = stateProvider()
                update(currentSnapshot, currentTrack, currentVolume)
                onClientConnectionChanged(true)

                while (isRunning.get() && isConnected) {
                    val line = reader?.readLine() ?: break
                    if (line.isEmpty()) continue

                    val message = RemoteProtocol.decode(line) ?: continue
                    when (message) {
                        is RemoteMessage.Hello -> {
                            logger.info("RemoteServer", "received Hello from client: ${message.device}, protocol=${message.protocol}")
                        }
                        is RemoteMessage.Command -> {
                            onCommandReceived(message.command)
                        }
                        is RemoteMessage.PlayerState -> {
                            logger.info("RemoteServer", "received unexpected state message from client")
                        }
                        is RemoteMessage.Artwork -> Unit

                        // V2 Library Requests
                        is RemoteMessage.LibraryPage -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleLibraryPage(message)
                                send(RemoteProtocol.encodeLibraryPageResult(result.requestId, result.total, result.hasMore, result.rows))
                            }
                        }
                        is RemoteMessage.LibrarySummaryRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleLibrarySummary(message)
                                send(RemoteProtocol.encodeLibrarySummary(result.genres, result.years, result.total, result.requestId))
                            }
                        }
                        is RemoteMessage.LibraryArtworkRequest -> {
                            val handlers = requestHandlers ?: continue
                            handlers.handleLibraryArtworkAsync(message) { result ->
                                send(RemoteProtocol.encodeLibraryArtworkResult(result.trackId, result.base64, result.width, result.height, result.requestId))
                            }
                        }

                        // V2 Playlist Requests
                        is RemoteMessage.PlaylistsListRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handlePlaylistsList(message)
                                send(RemoteProtocol.encodePlaylistsList(result.items, result.requestId))
                            }
                        }
                        is RemoteMessage.PlaylistsTracksRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handlePlaylistsTracks(message)
                                send(RemoteProtocol.encodePlaylistsTracks(result.playlistId, result.rows, result.total, result.hasMore, result.requestId))
                            }
                        }
                        is RemoteMessage.PlaylistsCreateRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handlePlaylistsCreate(message)
                                send(RemoteProtocol.encodePlaylistsMutate(result.ok, result.playlist, result.requestId))
                            }
                        }
                        is RemoteMessage.PlaylistsRenameRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handlePlaylistsRename(message)
                                send(RemoteProtocol.encodePlaylistsMutate(result.ok, result.playlist, result.requestId))
                            }
                        }
                        is RemoteMessage.PlaylistsDeleteRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handlePlaylistsDelete(message)
                                send(RemoteProtocol.encodePlaylistsMutate(result.ok, result.playlist, result.requestId))
                            }
                        }
                        is RemoteMessage.PlaylistsAddTrackRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handlePlaylistsAddTrack(message)
                                send(RemoteProtocol.encodePlaylistsMutate(result.ok, result.playlist, result.requestId))
                            }
                        }
                        is RemoteMessage.PlaylistsRemoveTrackRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handlePlaylistsRemoveTrack(message)
                                send(RemoteProtocol.encodePlaylistsMutate(result.ok, result.playlist, result.requestId))
                            }
                        }

                        // V2 Queue Requests
                        is RemoteMessage.QueueStateRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueState(message)
                                send(RemoteProtocol.encodeQueueState(result.requestId, result.entries, result.currentEntryId, result.repeatMode, result.shuffleEnabled, result.revision, result.totalCount, result.offset))
                            }
                        }
                        is RemoteMessage.QueueReplaceRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueReplace(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueuePlayNextRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueuePlayNext(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueAddToUpNextRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueAddToUpNext(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueRemoveEntryRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueRemoveEntry(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueMoveEntryRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueMoveEntry(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueuePromoteEntryRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueuePromoteEntry(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueShuffleToggleRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueShuffleToggle(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueRepeatCycleRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueRepeatCycle(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueClearUpNextRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueClearUpNext(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueClearRemainingRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueClearRemaining(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }
                        is RemoteMessage.QueueClearRequest -> {
                            val handlers = requestHandlers ?: continue
                            requestExecutor.execute {
                                val result = handlers.handleQueueClear(message)
                                send(RemoteProtocol.encodeQueueMutate(result.ok, result.revision, result.requestId))
                            }
                        }

                        else -> {
                            logger.info("RemoteServer", "received unhandled message: $line")
                        }
                    }
                }
            } catch (e: IOException) {
                if (isRunning.get() && isConnected) {
                    logger.info("RemoteServer", "client connection closed or error: ${e.message}")
                }
            } finally {
                isConnected = false
                runCatching { reader?.close() }
                runCatching { writer?.close() }
                runCatching { socket.close() }
                onClientConnectionChanged(false)
            }
        }

        fun send(payload: String) {
            if (!isConnected) return
            synchronized(writeLock) {
                try {
                    writer?.write(payload)
                    writer?.newLine()
                    writer?.flush()
                } catch (e: IOException) {
                    logger.warn("RemoteServer", "error sending to client: ${e.message}")
                    cancel()
                }
            }
        }

        fun cancel() {
            isConnected = false
            try {
                socket.close()
            } catch (e: IOException) {
                logger.warn("RemoteServer", "error closing client socket: ${e.message}")
            }
        }
    }
}

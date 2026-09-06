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
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class Y2RemoteServer(
    private val logger: DiagnosticLogger,
    private val stateProvider: () -> Triple<PlaybackSnapshot, Track?, Int>,
    private val onCommandReceived: (RemoteCommand) -> Unit,
    private val onClientConnectionChanged: (Boolean) -> Unit = {}
) {
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: AcceptThread? = null
    @Volatile private var connectedWorker: ConnectedWorker? = null

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
        onClientConnectionChanged(false)
    }

    fun update(snapshot: PlaybackSnapshot, track: Track?, volumePercent: Int = 100) {
        val worker = connectedWorker ?: return
        val json = RemoteProtocol.encodeSnapshot(snapshot, track, volumePercent)
        worker.send(json)
    }

    fun sendArtwork(base64: String) {
        val worker = connectedWorker ?: return
        val json = RemoteProtocol.encodeArtwork(base64)
        worker.send(json)
    }

    private inner class AcceptThread(private val adapter: BluetoothAdapter) : Thread("y2-remote-accept") {
        private var serverSocket: BluetoothServerSocket? = null

        private fun createServerSocket(): BluetoothServerSocket? {
            if (!adapter.isEnabled) return null
            return runCatching {
                adapter.listenUsingInsecureRfcommWithServiceRecord(
                    RemoteProtocol.SERVICE_NAME,
                    RemoteProtocol.UUID_Y2_REMOTE
                )
            }.recoverCatching {
                adapter.listenUsingRfcommWithServiceRecord(
                    RemoteProtocol.SERVICE_NAME,
                    RemoteProtocol.UUID_Y2_REMOTE
                )
            }.getOrNull()
        }

        override fun run() {
            logger.info("RemoteServer", "listening for incoming RFCOMM connections")
            while (isRunning.get()) {
                if (!adapter.isEnabled) {
                    try { sleep(2000) } catch (ignored: InterruptedException) {}
                    continue
                }
                if (serverSocket == null) {
                    serverSocket = createServerSocket()
                    if (serverSocket == null) {
                        try { sleep(2000) } catch (ignored: InterruptedException) {}
                        continue
                    }
                }
                val ss = serverSocket ?: continue
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        logger.warn("RemoteServer", "accept() failed, resetting socket: ${e.message}")
                    }
                    runCatching { ss.close() }
                    serverSocket = null
                    try { sleep(1000) } catch (ignored: InterruptedException) {}
                    continue
                }
                if (socket != null) {
                    logger.info("RemoteServer", "incoming connection accepted from ${socket.remoteDevice?.address}")
                    handleNewConnection(socket)
                }
            }
            runCatching { serverSocket?.close() }
            logger.info("RemoteServer", "accept thread terminated")
        }

        fun cancel() {
            interrupt()
            runCatching { serverSocket?.close() }
        }
    }

    @Synchronized
    private fun handleNewConnection(socket: BluetoothSocket) {
        connectedWorker?.cancel()
        val worker = ConnectedWorker(socket)
        connectedWorker = worker
        worker.start()
        onClientConnectionChanged(true)
    }

    private inner class ConnectedWorker(
        private val socket: BluetoothSocket
    ) : Thread("y2-remote-worker") {
        private val writeLock = Any()
        @Volatile private var writer: BufferedWriter? = null
        @Volatile var isConnected = true
            private set

        override fun run() {
            var reader: BufferedReader? = null
            try {
                val out = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                writer = out
                reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

                // Send Hello handshake
                val hello = RemoteProtocol.encodeHello()
                send(hello)

                // Send current playback state immediately
                val (snapshot, track, vol) = stateProvider()
                val initialState = RemoteProtocol.encodeSnapshot(snapshot, track, vol)
                send(initialState)

                while (isRunning.get() && isConnected) {
                    val line = reader.readLine() ?: break
                    val message = RemoteProtocol.parseMessage(line) ?: continue
                    when (message) {
                        is RemoteMessage.Hello -> {
                            logger.info("RemoteServer", "client hello: device=${message.device} protocol=${message.protocol}")
                        }
                        is RemoteMessage.Command -> {
                            logger.info("RemoteServer", "received command: ${message.command}")
                            onCommandReceived(message.command)
                        }
                        is RemoteMessage.PlayerState -> {
                            logger.info("RemoteServer", "received unexpected state message from client")
                        }
                        is RemoteMessage.Artwork -> Unit
                        is RemoteMessage.Unknown -> {
                            logger.info("RemoteServer", "received unknown message: ${message.raw}")
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
                synchronized(this@Y2RemoteServer) {
                    if (connectedWorker == this) {
                        connectedWorker = null
                        onClientConnectionChanged(false)
                    }
                }
                logger.info("RemoteServer", "client worker finished")
            }
        }

        fun send(message: String) {
            if (!isConnected) return
            try {
                synchronized(writeLock) {
                    val w = writer ?: return
                    w.write(message)
                    w.write("\n")
                    w.flush()
                }
            } catch (e: IOException) {
                logger.warn("RemoteServer", "failed to send message: ${e.message}")
                cancel()
            }
        }

        fun cancel() {
            isConnected = false
            interrupt()
            runCatching { socket.close() }
        }
    }
}

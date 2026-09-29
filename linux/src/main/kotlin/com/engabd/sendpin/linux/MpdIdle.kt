package com.engabd.sendpin.linux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * MPD's push channel: one long-lived connection parked in `idle player playlist`,
 * calling [onChange] the moment MPD plays, pauses, seeks, changes track or has its
 * queue edited — by this panel, the phone, `mpc`, anyone.
 *
 * Without it every one of those waits for the next 1 Hz poll, and HueGhost adds up
 * to its own 0.5 s poll on top: a pause from the phone reached the lights 1–1.5 s
 * late. With it, the poll runs at once and the report goes out within
 * milliseconds.
 *
 * [MpdClient][com.engabd.sendpin.mpd.MpdClient] is connection-per-command and
 * shared with the Android app, so this lives here as its own small socket rather
 * than as an idle mode bolted onto it. Losing the connection only costs latency —
 * the 1 Hz poll still runs — so errors are swallowed and the connection retried.
 */
class MpdIdle(
    private val address: String,
    private val password: String = "",
    private val onChange: () -> Unit,
) {

    /** Runs until the calling coroutine is cancelled. */
    suspend fun run() {
        var backoffMs = 1_000L
        while (coroutineContext.isActive) {
            val ran = runCatching { session() }.isSuccess
            if (ran) backoffMs = 1_000L
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /**
     * One connection, idling until MPD drops it (or a read fails). A blocked read
     * can't see a coroutine cancel, so a sibling closes the socket on cancellation,
     * which throws the read out — otherwise a reconnect would strand this thread
     * until MPD's next event.
     */
    private suspend fun session() = coroutineScope {
        val socket = Socket()
        val closer = launch {
            try { awaitCancellation() } finally { runCatching { socket.close() } }
        }
        try {
            idleOn(socket)
        } finally {
            closer.cancel()
        }
    }

    private suspend fun idleOn(socket: Socket) = withContext(Dispatchers.IO) {
        val (host, port) = hostPort(address)
        socket.use {
            socket.connect(InetSocketAddress(host, port), 5_000)
            // `idle` blocks for as long as nothing happens; MPD's own
            // connection_timeout does not apply while idling.
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            check(reader.readLine()?.startsWith("OK MPD") == true) { "not an MPD server" }
            if (password.isNotBlank()) {
                writer.write("password \"${password.replace("\\", "\\\\").replace("\"", "\\\"")}\"\n")
                writer.flush()
                check(reader.readLine() == "OK") { "MPD rejected the password" }
            }
            while (isActive) {
                writer.write("idle player playlist\n")
                writer.flush()
                var changed = false
                while (true) {
                    val line = reader.readLine() ?: error("MPD closed the connection")
                    if (line.startsWith("changed:")) changed = true
                    if (line == "OK") break
                    if (line.startsWith("ACK")) error(line)
                }
                if (changed) onChange()
            }
        }
    }

    companion object {
        /** Host and port the same way `MpdClient` reads them: optional scheme, default 6600. */
        internal fun hostPort(address: String): Pair<String, Int> {
            val clean = address.trim().trimEnd('/')
                .removePrefix("http://").removePrefix("https://")
            return clean.substringBefore(':') to (clean.substringAfter(':', "6600").toIntOrNull() ?: 6600)
        }
    }
}

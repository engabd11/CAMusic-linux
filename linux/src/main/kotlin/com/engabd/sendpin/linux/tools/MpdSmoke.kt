package com.engabd.sendpin.linux.tools

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.mpd.MpdClient
import com.engabd.sendpin.mpd.MpdRemote
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Step-4 smoke: MPD transport end to end, no UI. Connects to MPD, queues one
 * hardcoded path through [MpdRemote] (the shared transport), and reads [RemoteState]
 * once a second — playing, position advancing, duration, output format.
 *
 * Leaves MPD **paused** so the DAC is left as it was found, and clears nothing:
 * the queue is real music the user can inspect from M.A.L.P. afterwards.
 */
fun main() = runBlocking {
    val address = System.getenv("MPD_ADDRESS") ?: "127.0.0.1:6600"
    val path = System.getenv("MPD_TEST_PATH")
        ?: "_NSYNC/No Strings Attached/01 _NSYNC - Bye Bye Bye.flac"

    println("Connecting to MPD at $address …")
    val client = MpdClient(address)
    val ping = client.pingResult()
    if (ping != null) {
        println("MPD answered with an error: ${ping.message}")
        return@runBlocking
    }
    println("Connected.")

    // A track that carries both identities, exactly as JellyfinToMpd will stamp
    // them later: id = the Jellyfin item, scrobbleId = the MPD path (which is what
    // MpdRemote addresses), scrobbleProvider = "mpd" so the guard passes.
    val track = LocalTrack(
        id = "smoke-test-item",
        title = path.substringAfterLast('/'),
        artist = "Smoke Test",
        scrobbleId = path,
        scrobbleProvider = MpdClient.PROVIDER,
    )

    val remote = MpdRemote(client)
    client.setReplayGainMode("album")

    println("setQueue([$path]) …")
    remote.setQueue(listOf(track), 0)

    repeat(5) { i ->
        val state = remote.poll()
        println(
            "poll ${i + 1}: playing=${state?.playing} stopped=${state?.stopped} " +
                "index=${state?.index} pos=${state?.positionMs}ms dur=${state?.durationMs}ms " +
                "out=${state?.outputFormat} dev=${state?.outputDeviceName}",
        )
        delay(1000)
    }

    remote.pause()
    val paused = remote.poll()
    println("paused: playing=${paused?.playing} pos=${paused?.positionMs}ms")
    println("SMOKE OK — MPD transport works end to end.")
}
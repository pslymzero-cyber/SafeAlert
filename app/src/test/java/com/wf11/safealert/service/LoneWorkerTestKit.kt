package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/*
 * Shared helpers for the lone worker logic tests. Times are ms on one clock. Accelerometer windows
 * are 1 s long and end on whole seconds; a step at t is covered by the window that ends at the next
 * whole second after t.
 *
 * Words used across these tests:
 *  - rule 1 / rule 2: the fall rule / the no-motion (still) rule.
 *  - Rest NONE / DOCKED / WAIT: carried (the still count runs) / lying on a charger (no still count) /
 *    waiting for the first distinct motion after a start or an unplug.
 *  - distinct motion: 5 walking-shaped steps within 10 s (no step sensor: a 3 s run of walking-shaped windows).
 *  - floor: the time after which steps or windows count toward a distinct motion (a start, an unplug edge, the
 *    opening of a check); a step exactly at the floor does not count.
 *  - power wait: a deadline waits while a raw power change that started at or before it is not confirmed yet.
 *  - held check: a check restored after a service restart, kept hidden until the restart power is confirmed.
 *  - bounce: the charger contact flips back and forth around a restart.
 *  - sticky: the 10 s power poll (powerRaw(sticky = true)), as opposed to a power broadcast.
 * Timing constants: PowerDebounce.DEBOUNCE_MS = 2 s, PowerDebounce.CONFIRM_MS = 2.05 s,
 * RestartHold.POWER_HOLD_MS = 3.05 s, LoneWorkerLogic.LATE_MS = 6 s, LoneWorkerLogic.UNPLUG_FALL_MS = 10 s.
 */

/** carried = not charging and already past the first-motion wait. */
internal fun newLogic(charging: Boolean = false, zoneInside: Boolean = false, carried: Boolean = false) =
    LoneWorkerLogic("SAFEALERT_WALKER_ME").apply {
        start(0L, zoneInside, charging)
        if (carried) sensorSilent(0L)
    }

/** Carried, with the still count pushed out to 1 h so only the fall rule acts. */
internal fun fallRuleOnly(charging: Boolean = false, zoneInside: Boolean = false) =
    newLogic(charging, zoneInside, carried = true).apply { stillMs = 3_600_000L }

/**
 * The raw power changes to on at `at` and stays for the debounce; the monitor's confirm tick (CONFIRM_MS later)
 * applies it from `at`, judges and replays held inputs (the product order).
 */
internal fun LoneWorkerLogic.reportPower(on: Boolean, at: Long) {
    powerRaw(on, at)
    tick(at + PowerDebounce.CONFIRM_MS)
}

private const val WIN = MotionAnalyzer.WINDOW_MS

internal fun coverEnd(t: Long) = t / WIN * WIN + WIN

/** One step at t, then the window covering it closes (walking-shaped or not). */
internal fun LoneWorkerLogic.step(t: Long, walking: Boolean = true, vibrating: Boolean = false) {
    onStep(t, vibrating)
    onWindow(MotionAnalyzer.Window(coverEnd(t), walking))
}

/** n steps 500 ms apart, the last at lastMs, covered by walking-shaped windows. */
internal fun LoneWorkerLogic.walk(lastMs: Long, n: Int) {
    for (i in n - 1 downTo 0) step(lastMs - i * 500L)
}

/** Same steps, but the covering windows are not walking-shaped (device shaken, lying, docked). */
internal fun LoneWorkerLogic.shuffle(lastMs: Long, n: Int) {
    for (i in n - 1 downTo 0) step(lastMs - i * 500L, walking = false)
}

/** count consecutive walking-shaped windows, the first ending at firstEnd. */
internal fun LoneWorkerLogic.strongRun(firstEnd: Long, count: Int) {
    for (i in 0 until count) onWindow(MotionAnalyzer.Window(firstEnd + i * WIN, true))
}

internal fun LoneWorkerLogic.strongWindows(vararg ends: Long) {
    for (e in ends) onWindow(MotionAnalyzer.Window(e, true))
}

/** Sensor data up to t has arrived: a still window closes at t and the step sensor is flushed to t. */
internal fun LoneWorkerLogic.sensed(t: Long) {
    onWindow(MotionAnalyzer.Window(t, false))
    stepsFlushed(t)
}

/** One sensor callback (sense) or one raw power value (on) at `at`, for drive. */
internal class Feed(val at: Long, val on: Boolean? = null, val sense: (LoneWorkerLogic.() -> Unit)? = null)

/**
 * Monitor model: tick at from, the scheduled ticks (nextCheckAt) before each feed, a sensor callback then its end and
 * an immediate tick when a passed deadline is due, a raw power value then a tick when it changed. Sensor feeds
 * with `at` in late are held back and delivered in order at deliverAt, after the ticks and power feeds up to that
 * time. Returns each observed change of "mode trigger @modeSinceMs", then "end mode trigger rest".
 */
internal fun LoneWorkerLogic.drive(from: Long, feeds: List<Feed>, until: Long,
                                   late: LongRange = LongRange.EMPTY, deliverAt: Long = Long.MAX_VALUE): List<String> {
    val seen = mutableListOf<String>()
    var now = from
    fun note() {
        val s = "$mode $trigger @$modeSinceMs"
        if (seen.lastOrNull() != s) seen += s
    }
    fun upTo(t: Long) {
        while (true) {
            val n = nextCheckAt(now) ?: break
            if (n > t) break
            now = n
            tick(n)
            note()
        }
        now = maxOf(now, t)
    }
    fun sense(f: Feed, t: Long) {
        f.sense!!.invoke(this)
        sensorEventEnd(t)
        if (dueNow(t)) tick(t)
        note()
    }
    val parked = mutableListOf<Feed>()
    var delivered = deliverAt == Long.MAX_VALUE
    fun deliver() {
        upTo(deliverAt)
        for (f in parked) sense(f, deliverAt)
        delivered = true
    }
    tick(from)
    note()
    for (f in feeds) {
        if (!delivered && (f.at > deliverAt || f.at == deliverAt && f.on == null && f.at !in late)) deliver()
        upTo(f.at)
        when {
            f.on != null -> { if (powerRaw(f.on, f.at)) tick(f.at); note() }
            f.at in late -> parked += f
            else -> sense(f, f.at)
        }
    }
    if (!delivered) deliver()
    upTo(until)
    tick(until)
    note()
    return seen + "end $mode $trigger $rest"
}

/** Runs body for both arrival orders (late = false, then true) with an assertion message naming the order. */
internal fun bothOrders(body: (late: Boolean, m: String) -> Unit) {
    for (late in listOf(false, true)) body(late, "late=$late")
}

/** Acknowledge every peer entry (the [OK] button on all of them). */
internal fun LoneWorkerLogic.ackAll(now: Long) = silencePeers(now, peers.associate { it.id to it.epId })

/** Acknowledge the listed peer items (item id to episode id). */
internal fun LoneWorkerLogic.ack(now: Long, vararg targets: Pair<String, String>) = silencePeers(now, mapOf(*targets))

/** The one peer entry of bleId id (and episode ep when given). */
internal fun LoneWorkerLogic.peer(id: String, ep: Int? = null) =
    peers.single { it.bleId == id && (ep == null || it.episode == ep) }

/**
 * One server SOS record (role WALKER, trigger still) received at now. resolvedAt and created are server times,
 * converted with serverNow (or the wall clock wall when serverNow is unknown) like the monitor does.
 */
internal fun LoneWorkerLogic.srv(
    key: String, id: String, ep: Int, active: Boolean, created: Long, now: Long,
    name: String = "n", beacon: String = "",
    resolvedAt: Long = 0L, serverNow: Long? = null, wall: Long = 0L, auto: Boolean = false,
    inScope: Boolean = true, floor: String = "", proc: String = ""
) = onPeerServer(
    LoneWorkerPeers.ServerRec(key, id, name, "WALKER", "still", beacon, created, active, ep,
        LoneWorkerPeers.resolvedLocalMs(resolvedAt, serverNow, wall, now),
        LoneWorkerPeers.startLocalMs(created, serverNow, now), auto,
        floor = floor, proc = proc, inScope = inScope),
    now
)

/**
 * A monitor tick at t with no new sensor data: a deadline at or before t that the data does not cover yet waits
 * (at most LATE_MS).
 */
internal fun LoneWorkerLogic.modeAt(t: Long): Mode { tick(t); return mode }

/** Sensor data up to t has arrived (sensed), then a tick at t: every deadline up to t is judged now. */
internal fun LoneWorkerLogic.seenAt(t: Long): Mode { sensed(t); return modeAt(t) }

/** Charging, then carried by 10 steps ending at 10 s. */
internal fun carriedWhileCharging() = newLogic(charging = true).apply { walk(10_000, 10) }

/** Peer id sounds its siren every second from..to and the logic keeps watching with sensor data each second. */
internal fun LoneWorkerLogic.peerSiren(from: Long, to: Long, id: String = "P") {
    for (t in from..to step 1_000L) {
        onPeerBle(id, true, t)
        assertEquals(Mode.WATCHING, seenAt(t))
    }
}

// -- 50 Hz accelerometer traces: m/s^2 with gravity, one sample every 20 ms --

internal val UPRIGHT_STILL = floatArrayOf(0f, 0f, 9.81f)
internal val LYING_STILL = floatArrayOf(9.81f, 0f, 0f)
/** The low G of a free fall. */
internal val FREE_FALL_SAMPLE = floatArrayOf(0f, 0f, 1.5f)
/** About 3 G: an impact. */
internal val IMPACT_SAMPLE = floatArrayOf(0f, 0f, 30f)

/** Feeds f(t) every 20 ms for from <= t < to; returns every signal other than NONE with its time. */
internal fun MotionAnalyzer.feed(from: Long, to: Long, masked: Boolean = false,
                                 f: (Long) -> FloatArray): List<Pair<Long, MotionAnalyzer.Signal>> {
    val out = ArrayList<Pair<Long, MotionAnalyzer.Signal>>()
    var t = from
    while (t < to) {
        val v = f(t)
        val s = add(t, v[0], v[1], v[2], masked)
        if (s != MotionAnalyzer.Signal.NONE) out += t to s
        t += 20
    }
    return out
}

/** In-memory SosKv for tests. puts counts put() calls. */
internal class Kv : SosKv {
    val m = HashMap<String, String>()
    var puts = 0
    override fun get(k: String): String? = m[k]
    override fun put(changes: Map<String, String?>) {
        puts++
        for ((k, v) in changes) if (v == null) m.remove(k) else m[k] = v
    }
}

/** A repository file by its path from the root, LF line ends (the test runs from the module or the root). */
internal fun repoFile(rel: String): String =
    listOf(File(rel), File("../$rel")).first { it.exists() }.readText().replace("\r\n", "\n")

/** Source of a file in 03_service. */
internal fun serviceSource(name: String): String = repoFile("app/src/main/java/com/wf11/safealert/03_service/$name")

/** From head to the end of its 4-space-indented block. */
internal fun sourceBlock(s: String, head: String): String {
    val i = s.indexOf(head)
    assertTrue(head, i >= 0)
    return s.substring(i, s.indexOf("\n    }", i))
}

/**
 * Restart like the monitor: startFrom feeds the current raw power to the logic's own debounce, and every
 * tick first applies a change stable for 2 s (modeAt = a monitor tick without sensor data, seenAt = with
 * data up to t).
 */
abstract class RestartKit {
    protected val wall0 = 1_000_000_000L
    protected val boot = 7

    /** Modes seen by the two ticks before the debounce reports (the monitor ticks on sensor data and its 10 s loop). */
    protected var beforePower: List<Mode> = emptyList()

    /** Save old at savedAt (wall wall0, bootSaved), then decode at elapsed now with wall0 + wallGap on bootNow. */
    protected fun saved(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long,
                        bootNow: Int = boot + 1, bootSaved: Int = boot): LoneWorkerResume.State {
        val raw = LoneWorkerResume.encode(old.snapshot(savedAt), savedAt, wall0, bootSaved)
        val s = LoneWorkerResume.decode(raw, now, wall0 + wallGap, bootNow)
        assertNotNull(s)
        return s!!
    }

    /** Carried, fall at 1 s, accident check open at 31 s. */
    protected fun accidentCheck(): LoneWorkerLogic = newLogic(carried = true).apply {
        onAccident(1_000)
        assertEquals(Mode.CHECKING, seenAt(31_000))
        assertEquals("fall", trigger)
    }

    /** startFrom with the current raw power (charging). No tick. */
    protected fun restart(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long, charging: Boolean = false,
                          bootNow: Int = boot + 1, bootSaved: Int = boot, zoneInside: Boolean = false): LoneWorkerLogic =
        LoneWorkerLogic("SAFEALERT_WALKER_ME").apply {
            startFrom(now, zoneInside, charging, saved(old, savedAt, now, wallGap, bootNow, bootSaved))
        }

    /**
     * restart, tick at the restart, flip the raw power at each of flips (between now and the next tick),
     * tick 1 ms before the debounce can report, then tick at its confirm check (CONFIRM_MS).
     */
    protected fun reboot(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long, charging: Boolean = false,
                         bootNow: Int = boot + 1, bootSaved: Int = boot, zoneInside: Boolean = false,
                         flips: List<Long> = emptyList()): LoneWorkerLogic {
        val l = restart(old, savedAt, now, wallGap, charging, bootNow, bootSaved, zoneInside)
        val first = l.modeAt(now)
        var raw = charging
        for (f in flips) { raw = !raw; l.powerRaw(raw, f) }
        beforePower = listOf(first, l.modeAt(now + PowerDebounce.DEBOUNCE_MS - 1))
        l.modeAt(now + PowerDebounce.CONFIRM_MS)
        return l
    }

    /**
     * The raw power at the restart (now) bounces back to the saved value at 5.5 s and to now again at 5.8 s;
     * with gapTick the logic ticks at 5.6 s in between. Watching throughout.
     */
    protected fun LoneWorkerLogic.bounceRestartPower(now: Boolean, gapTick: Boolean, m: String) {
        assertEquals(m, Mode.WATCHING, modeAt(5_000))
        powerRaw(!now, 5_500)
        if (gapTick) assertEquals(m, Mode.WATCHING, modeAt(5_600))
        powerRaw(now, 5_800)
    }
}

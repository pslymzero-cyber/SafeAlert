package com.wf11.safealert.service

/**
 * Lone-worker accident / no-motion SOS state machine.
 *
 * Pure logic with no Android dependency. All times are elapsedRealtime-based ms passed in by the caller
 * (the caller converts sensor timestamps to this base).
 *
 *   WATCHING --accident 30 s still / no-motion stillMs--> CHECKING --no reply 1 min / responseMs--> SOS --"괜찮아요"--> WATCHING
 *
 * Rule 1 (accident): a single fall signal raises accident suspicion for 5 min from its impact time (no prior-movement condition).
 * If there is no clear movement for 30 s within that time, the accident check window ("fall", 1 min) opens. It does not depend
 * on docking or the safe zone, except that the fall is ignored if at the moment of impact (not when FALL arrived) the phone was
 * inside the safe zone (from the moment of entry, raw inside; outside if an exit is confirmed within 12 s after the impact) and
 * charging (an actual unplug after the impact means it was charging, as does an actual unplug within 10 s before the impact —
 * dropped from the cradle — excluding unplugs applied at restart), regardless of when FALL is processed. A fall inside the safe
 * zone counts only if it exceeds every zoneFall threshold (height, impact, posture; developer setting; unknown posture counts
 * as exceeded). An actual power connection within 10 s before the trigger (or after it), excluding connections applied at
 * restart, is treated as placing the phone in the cradle and discards that trigger.
 * An actual connection during suspicion means a person is present, so it ends the suspicion.
 * Closing the accident check window with "괜찮아요" ends the suspicion; if clear movement closes it, watching continues until the
 * 5 min are over.
 *
 * Rule 2 (no-motion): only while carried (Rest.NONE), stillMs without motion opens the no-motion check window ("still", responseMs).
 * When not charging, the phone is carried from the first clear movement after start or unplug (or after 1 min of sensor
 * silence); before that it is waiting (WAIT).
 * When charging, it is carried from 10 steps within the last 30 s (without a step sensor, 5 walk-like windows within 30 s) until
 * the next connection; before that it is docked (DOCKED) — an equipment-mode (forklift / EPJ) mount never pauses and is
 * Rest.NONE. In equipment mode, steps or walk-like windows while charging never make it carried — only an unplug does.
 * An equipment mount restarts the count on MOVED or a turn, and an open no-motion window closes on a turn, a 3 s walking-level
 * shake or "괜찮아요" (steps are not used). Fall rules and walker-mode docking are unchanged.
 * After an SOS ended by the one-hour limit (holdStill) carrying is counted again from then, as after an unplug, and a mount
 * waits as well until it moves (MOVED, a turn, a carry or a power change).
 * In a settled safe zone (raw inside for 60 s straight) no-motion is not counted; counting starts when the phone leaves.
 * Settling withdraws an open no-motion check window.
 * Counting also pauses while a peer siren vibrates on this device and continues from the accrued time afterwards (once the
 * pause reaches stillMs counting resumes even if the siren keeps sounding; devices without a vibrator never pause).
 *
 * Steps: sensor steps whose covering 1 s accelerometer window is walk-like and outside app vibration spans (WalkingSteps).
 * Clear movement: 5 steps within the last 10 s; without a usable step sensor, walk-like windows lasting 3 s or more. Closing a
 * check window counts only movement after the window appeared (an unplug does not restart this count); carrying after an
 * unplug is counted separately, from movement after the unplug time.
 * Check windows (both kinds) close on "괜찮아요", clear movement, or an actual power connection. An actual connection also ends
 * accident suspicion.
 * Deadline judgments (opening no-motion / accident windows, SOS) wait until sensor data up to the deadline has arrived (or
 * LATE_MS has passed) and until any power change that started before it (same time included) is confirmed or discarded;
 * sensor input arriving meanwhile is applied after the judgment, and changes that started after the deadline are applied
 * after the judgment — ordering lives only in JudgeOrder. Turns are real-time values stamped at polling time, so they only
 * arrive late; a turn or shake later than an already-passed SOS deadline does not close that window.
 * SOS never ends by zone entry, feature off or a power change — only by cancelSos.
 * Post-restart power / zone holds: RestartHold.
 * Peer SOS reception is handled by LoneWorkerPeers as entries per episode (bleId, ep) (a server record and a BLE bit of the
 * same episode are one entry).
 *
 * LoneWorkerSosSync filters out my own server records by author uid, and the BLE scanner never receives its own
 * advertisement — so this class does not filter me out by bleId (phones sharing one equipment ID still alert each other).
 */
class LoneWorkerLogic(var myBleId: String) {

    enum class Mode { WATCHING, CHECKING, SOS }

    /**
     * Why no-motion checking is paused, with its notice text (main screen banner,
     * notification sentence fragment). Accident detection never pauses.
     */
    enum class Rest(val banner: String?, val keepText: String) {
        NONE(null, "움직임이 없는 것으로 보고 확인을 계속하며"),
        DOCKED("거치 중 — 무동작 감시 쉼 (걸으면 다시 시작, 사고 감지는 계속)", "거치 중이라 무동작 확인은 쉬며"),
        WAIT("움직임 대기 — 무동작 감시 쉼 (움직이면 다시 시작, 사고 감지는 계속)", "첫 움직임을 기다리는 중이라 무동작 확인은 쉬며")
    }

    companion object {
        const val ZONE_SETTLE_MS = 60_000L
        const val ACCIDENT_WATCH_MS = 300_000L
        const val ACCIDENT_STILL_MS = 30_000L
        const val ACCIDENT_RESPONSE_MS = 60_000L
        /** An actual power connection within this time before a trigger is the act of placing the phone in the cradle. */
        const val PLUG_EXCEPT_MS = 10_000L
        /**
         * An actual unplug within this time before the impact also counts as charging at impact (pulled out while
         * falling from the cradle). An unplug after the impact means it was charging at impact.
         */
        const val UNPLUG_FALL_MS = 10_000L
        /**
         * Steps are counted over sliding time windows: clear movement = 5 steps in the
         * last 10 s, carried while charging = 10 steps in the last 30 s.
         */
        const val DISTINCT_STEPS = 5
        const val DISTINCT_STEP_WINDOW_MS = 10_000L
        const val CARRY_STEPS = 10
        const val CARRY_STEP_WINDOW_MS = 30_000L
        /**
         * Without a usable step sensor: clear movement = 3 consecutive walk-like windows,
         * carried while charging = 5 walk-like windows in the last 30 s.
         */
        const val STRONG_RUN_MS = 3 * MotionAnalyzer.WINDOW_MS
        const val CARRY_FALLBACK_WINDOWS = 5
        /** Maximum sensor batch latency — basis for the accelerometer / step registration values and LATE_MS. */
        const val MAX_BATCH_MS = 5_000L
        /**
         * If sensor data up to the deadline has still not arrived after this long, judge with what has arrived (batch latency + one window).
         */
        const val LATE_MS = MAX_BATCH_MS + MotionAnalyzer.WINDOW_MS
        /**
         * How long the restored inside-safe-zone state is kept after a restart without a
         * zone report (same rule as BleService's 10 s signal-loss exit).
         */
        const val ZONE_RESUME_HOLD_MS = 10_000L
    }

    /** Changed live from settings (default 3 min / 2 min). No-motion check only — the accident check is fixed at 30 s / 1 min. */
    var stillMs = 180_000L
    var responseMs = 120_000L
    var zoneFall = MotionAnalyzer.ZoneFall() // Safe-zone fall thresholds (height/impact/posture), dev setting set by monitor
    /**
     * The step sensor is registered (sensor present, physical activity permission granted). Otherwise walk-like windows stand in.
     */
    var stepsAvailable = true
    /** This device has a vibrator — without one there is no siren vibration and no no-motion count pause during it. */
    var canVibrate = true

    var mode = Mode.WATCHING
        private set
    /** "still" or "fall"; empty string in WATCHING. */
    var trigger = ""
        private set
    var modeSinceMs = 0L
        private set
    var zoneSettled = false
        private set

    val sosActive: Boolean get() = mode == Mode.SOS
    val peers: Collection<LoneWorkerPeers.Peer> get() = peerStore.all

    /** Monitoring on (feature enabled in developer settings and sensor present; set by the monitor via setEnabled). */
    var enabled = true
        private set
    /**
     * Baseline for counting no-motion time: the latest of start, movement, check window close,
     * re-enable, leaving a settled zone, carry start, equipment mount start (plug-in or equipment
     * mode) and a turn while mounted. Steps do not raise it while mounted.
     */
    private var stillBase = 0L
    private val zone = ZoneHistory() // Raw safe-zone in/out and entry time; falls use the state at impact

    // Carried: if not charging, the wait is over; if charging, steps confirmed carrying
    private var charging = false // Applied power; differs from PowerDebounce.reported while JudgeOrder defers
    private var chargeAt = 0L
    private var carried = false
    /**
     * Set by holdStill: a mount rests as Rest.WAIT too until it moves (MOVED, a turn, a carry or a power change). In memory
     * only; an unmounted phone needs no flag, since holdStill clears carried and that state is saved for a restart.
     */
    private var stillHeld = false
    private var equipment = false // Equipment mode (forklift/EPJ selected; set by the monitor from the role)
    val mounted: Boolean get() = equipment && charging && !carried // Equipment mount = equipment mode + charging + not carried

    // Step / walk-like window history. floorAt = step-count floor for closing windows and the
    // accident reset (start, trigger, window open, clear movement); carrying uses chargeAt
    private val walk = WalkingSteps()
    private var floorAt = Long.MIN_VALUE
    private var shakeFloorAt = Long.MIN_VALUE // Mount 3 s shake-close floor: set only on window open, never moved by steps
    private var lastDistinctAt = Long.MIN_VALUE

    // Accident suspicion
    private var accidentFrom = Long.MIN_VALUE
    private var accidentUntil = Long.MIN_VALUE
    private var lastPlugAt = Long.MIN_VALUE
    private var lastUnplugAt = Long.MIN_VALUE
    /**
     * No-motion count pause during peer siren vibration, post-restart power / zone
     * holds, and judge ordering (power debounce included, JudgeOrder).
     */
    private val siren = SirenPause()
    private val hold = RestartHold()
    private val order = JudgeOrder(hold, ::deadlines, ::sensedTo, ::setCharging)

    /** Why no-motion checking is paused. */
    val rest: Rest get() = when {
        mounted && stillHeld -> Rest.WAIT // Held after an SOS ended by the one-hour limit (holdStill)
        carried || mounted -> Rest.NONE // An equipment mount never pauses otherwise
        charging -> Rest.DOCKED
        else -> Rest.WAIT
    }
    /**
     * The open no-motion window closes by a turn, a 3 s shake or "괜찮아요" instead of steps
     * (equipment mount) — drives the how-to-close hint on screen and in the notification.
     */
    val closesByTurn: Boolean get() = mode == Mode.CHECKING && trigger == "still" && mounted

    internal val peerStore = LoneWorkerPeers()

    internal val beacons = BeaconHints()

    // ── Own state ──────────────────────────────────────────────

    /** charging is the power state at start: starts docked if charging, otherwise waiting for the first clear movement. */
    fun start(nowMs: Long, zoneInside: Boolean, charging: Boolean = false) {
        stillBase = nowMs
        this.charging = charging
        chargeAt = nowMs
        carried = false
        stillHeld = false
        walk.reset()
        floorAt = nowMs
        shakeFloorAt = nowMs
        clearAccident()
        lastPlugAt = Long.MIN_VALUE
        lastUnplugAt = Long.MIN_VALUE
        siren.reset()
        order.reset(charging)
        hold.reset()
        mode = Mode.WATCHING
        trigger = ""
        modeSinceMs = nowMs
        zoneSettled = false
        zone.reset(zoneInside, nowMs)
    }

    /**
     * Feature off: withdraws open check windows (including a held restored window) and accident suspicion; an active SOS is kept.
     */
    fun setEnabled(on: Boolean, nowMs: Long) {
        if (on == enabled) return
        enabled = on
        if (on) {
            raiseStillBase(nowMs)
        } else {
            clearAccident()
            closeCheck(nowMs)
        }
    }
    /**
     * Equipment mode (forklift / EPJ selected), set by the monitor from the role — a change restarts
     * counting from that time, so the old baseline cannot open a window right away.
     */
    fun setEquipment(on: Boolean, nowMs: Long) { if (on != equipment) { equipment = on; raiseStillBase(nowMs) } }

    /**
     * Actual power change confirmed by the debounce (JudgeOrder applies it in deadline order; atMs = first change time before
     * the debounce). Plug-in: a new dock — clears carried, treats an open check window as answered and closes it,
     * and ends accident suspicion unless in SOS (plugging in = a person is present). Unplug: wait for the
     * first clear movement (if steps after the unplug already established it before confirmation, carried from
     * those steps; the step-count floor is unchanged). SOS never ends on a power change.
     */
    private fun setCharging(on: Boolean, atMs: Long) {
        val restart = hold.powerSettled(atMs)
        charging = on
        chargeAt = atMs
        carried = false
        stillHeld = false
        // A change applied at restart is not a docking action or a cradle-drop reference
        if (on) {
            if (!restart) lastPlugAt = atMs
            if (mounted) raiseStillBase(atMs) // Mounting on equipment starts counting equipment idle time from then
            closeCheck(atMs)
            if (mode != Mode.SOS) clearAccident()
        } else {
            if (!restart) lastUnplugAt = atMs
            // If steps after the unplug, received before confirmation (3 s of walk-like windows without a step
            // sensor), already made clear movement, carried from then; the step-count floor (floorAt) stays
            firstDistinct(atMs)?.let { carry(it) }
        }
    }

    /**
     * A closed 1 s accelerometer window (endMs already converted to this time base). Judges
     * steps, substituting walk-like windows without a step sensor. A mounted no-motion window
     * closes on 3 s of continuous shaking after the window opened (shakeFloorAt).
     */
    fun onWindow(w: MotionAnalyzer.Window) {
        if (order.keep { onWindow(w) }) return
        val accepted = walk.onWindow(w.endMs, w.strong) ?: return
        for (t in accepted) acceptStep(t)
        // Equipment mount: 3 s of continuous walking-level shaking after the window opened (shakeFloorAt, not
        // moved by steps) closes the no-motion window (steps unused; not after a passed SOS deadline)
        if (mounted && w.strong) walk.firstRunEnd(shakeFloorAt, STRONG_RUN_MS)?.let { closeMounted(it) }
        if (stepsAvailable || !w.strong) return
        val end = w.endMs
        if (!carried && !mounted) (if (charging) end.takeIf { walk.strongIn(maxOf(chargeAt, end - CARRY_STEP_WINDOW_MS), end) >= CARRY_FALLBACK_WINDOWS } else firstDistinct(chargeAt))?.let { carry(it) }
        firstDistinct(floorAt)?.let { onDistinct(it) }
    }

    /**
     * One detected step (converted sensor time). vibrating = that time falls in an app vibration span. Counted only when walk-like.
     */
    fun onStep(tMs: Long, vibrating: Boolean = false) {
        if (!order.keep { onStep(tMs, vibrating) }) walk.onStep(tMs, vibrating)?.let { acceptStep(it) }
    }

    /** Step sensor flush complete: all steps up to the request time (tMs) have arrived. */
    fun stepsFlushed(tMs: Long) { if (!order.keep { stepsFlushed(tMs) }) walk.stepsFlushed(tMs) }

    /**
     * One sensor callback finished (the monitor calls this per callback) — if a deadline got
     * blocked, later input is held; once unblocked, it is replayed after the judgment.
     */
    fun sensorEventEnd(nowMs: Long) = order.eventEnd(nowMs, ::decide)

    /**
     * An accepted step. Carrying counts steps after chargeAt (10 steps in 30 s while charging, first clear
     * movement after an unplug); window closing and the accident reset count steps after floorAt.
     */
    private fun acceptStep(t: Long) {
        if (!carried && !mounted) (if (charging) t.takeIf { walk.within(chargeAt, t, CARRY_STEPS, CARRY_STEP_WINDOW_MS) } else firstDistinct(chargeAt))?.let { carry(it) }
        firstDistinct(floorAt)?.let { onDistinct(it) }
    }

    /**
     * Time at which clear movement (5 steps in 10 s; without a step sensor, 3 s of consecutive
     * walk-like windows) first holds using only records after the given time (after) — single
     * definition (window closing uses floorAt, carrying uses chargeAt).
     */
    private fun firstDistinct(after: Long): Long? =
        if (stepsAvailable) walk.firstWithin(after, DISTINCT_STEPS, DISTINCT_STEP_WINDOW_MS) else walk.firstRunEnd(after, STRONG_RUN_MS)

    /** Movement (MOVED) only refreshes the no-motion timer. It does not close an open check window. */
    fun onMoved(nowMs: Long) { if (!order.keep { onMoved(nowMs) }) { stillHeld = false; raiseStillBase(nowMs) } }
    /**
     * Equipment turn (BleService TX polling, default 0.5 s, only when not going straight): when mounted, restarts the
     * no-motion count and closes the no-motion window. Being a real-time value stamped with the polling time, it is
     * applied in real-time order on receipt, so every turn at or before a deadline is reflected before that deadline's
     * judgment, and a turn later than an already-passed SOS deadline does not close that window. It does not feed
     * sensedTo; like MOVED, it is held behind a blocked deadline and replayed after the judgment.
     */
    fun onTurn(nowMs: Long) { if (!order.keep { onTurn(nowMs) } && mounted) { stillHeld = false; raiseStillBase(nowMs); closeMounted(nowMs) } }

    /**
     * Closes the mounted no-motion window on a turn or 3 s shake (time t); input later than an already-passed SOS
     * deadline does not close it, since the deadline judgment (waiting for sensor data) comes first.
     */
    private fun closeMounted(t: Long) { if (t <= (sosAt() ?: Long.MAX_VALUE)) closeCheck(t, "still") }

    /** The accelerometer has not responded for 1 min: end the movement wait and count as carried. */
    fun sensorSilent(nowMs: Long) {
        if (order.keep { sensorSilent(nowMs) } || charging || carried) return
        carried = true
        raiseStillBase(nowMs)
    }

    /**
     * Fall (trigMs = impact sample time). Discarded when disabled, in SOS or during an accident check;
     * other ignore conditions are in rule 1 (class KDoc). A new trigger during suspicion only extends
     * the suspicion end. If inside the safe zone at impact and no exit was confirmed within 12 s after
     * it (EXIT_LAG_MS), it counts only if shape exceeds zoneFall.
     */
    fun onAccident(trigMs: Long, shape: MotionAnalyzer.FallShape = MotionAnalyzer.FallShape.ANY) {
        if (order.keep { onAccident(trigMs, shape) } || !enabled || mode == Mode.SOS) return
        if (mode == Mode.CHECKING && trigger == "fall") return
        hold.zoneExpired(trigMs + ZoneHistory.EXIT_LAG_MS)?.let { leaveZone(it) } // FALL comes 12 s after impact; first apply a restart zone hold (exit) that ended
        if (zone.insideAtImpact(trigMs) && (charging || !zoneFall.passes(shape) ||
                (lastUnplugAt != Long.MIN_VALUE && trigMs - lastUnplugAt <= UNPLUG_FALL_MS))) return
        if (lastPlugAt != Long.MIN_VALUE && trigMs - lastPlugAt <= PLUG_EXCEPT_MS) return
        if (accidentUntil == Long.MIN_VALUE) accidentFrom = trigMs
        accidentUntil = maxOf(accidentUntil, trigMs + ACCIDENT_WATCH_MS)
        // Recount clear movement from post-impact records only — movement that relied on pre-impact
        // steps is excluded from the accident baseline; closed windows stay closed
        var base = trigMs
        while (true) base = firstDistinct(base) ?: break
        lastDistinctAt = if (base > trigMs) base else minOf(lastDistinctAt, trigMs)
        if (mode != Mode.CHECKING || floorAt <= trigMs) floorAt = base
    }

    /**
     * Zone report. Releases the restart zone hold; an inside report that just released the hold within the limit judges
     * settling from the saved entry time (if the limit has passed, the phone first leaves at the limit time).
     */
    fun onZone(inside: Boolean, nowMs: Long) {
        updateSettle(nowMs)
        hold.zoneReported()
        if (!inside) {
            leaveZone(nowMs)
            return
        }
        if (!zone.inside) zone.mark(true, nowMs)
        updateSettle(nowMs)
    }

    private fun leaveZone(t: Long) {
        if (!zone.inside) return
        zone.mark(false, t)
        if (zoneSettled) {
            zoneSettled = false
            raiseStillBase(t)
        }
    }

    fun tick(nowMs: Long) {
        settlePower(nowMs) // Apply stable power changes that need no deferral first (JudgeOrder)
        stillBase = siren.update(alarmVibrates, nowMs, stillBase, stillMs)
        updateSettle(nowMs)
        // No no-motion counting in a settled safe zone (the baseline keeps moving to now)
        if (zoneSettled) raiseStillBase(nowMs)
        order.judge(nowMs, ::decide)
        peerStore.tick(nowMs)
    }

    /**
     * One judgment pass: hold gate, opening accident / no-motion windows, and the SOS
     * deadline — ordering, repetition and replay live in JudgeOrder.judge.
     */
    private fun decide(nowMs: Long) {
        // Hold gate: no new check window during the restart power hold or before the held window opens
        val gate = hold.gate(nowMs)
        if (gate?.let { order.due(it, nowMs) } != false) {
            if (gate != null) hold.takeCheck()?.let { if (enabled && mode == Mode.WATCHING) toChecking(it, gate, nowMs) }
            accidentTick(nowMs)
            stillOpenAt()?.let { if (order.due(it, nowMs)) toChecking("still", it, nowMs) }
        }
        // Check window → SOS ignores docking, waiting and the safe zone
        sosAt()?.let { if (order.due(it, nowMs)) toSos(nowMs) }
    }

    /** "괜찮아요": closes the open check window and also ends any ongoing accident suspicion. */
    fun ackWorking(nowMs: Long): Boolean {
        if (mode != Mode.CHECKING) return false
        closeCheck(nowMs)
        clearAccident()
        return true
    }

    /** Suspicion was already cleared on SOS entry and falls during SOS are discarded, so there is no suspicion to end. */
    fun cancelSos(nowMs: Long): Boolean {
        if (mode != Mode.SOS) return false
        toWatching(nowMs)
        return true
    }

    /**
     * After an SOS ended by the one-hour limit (here or before a restart): the no-motion watch waits for the phone to move
     * again, carrying counted from now as after an unplug, so a phone left lying still raises no new SOS minutes later.
     * Falls are still judged.
     */
    fun holdStill(nowMs: Long) {
        carried = false
        chargeAt = nowMs
        stillHeld = true
    }

    /**
     * Restores from the saved own SOS (after a service restart or process death). Call after start().
     * SOS ends only via cancelSos, so zone settling, feature off and ackWorking never leave it. Held or open check windows are discarded.
     */
    fun restoreSos(trigger: String, nowMs: Long) {
        closeCheck(nowMs)
        this.trigger = trigger
        toSos(nowMs)
    }

    /**
     * Restart-resume snapshot. SOS is not saved as a check window (SosLedger restores the own SOS); during
     * the power hold the held restored window is saved. The baseline excludes the siren pause.
     */
    fun snapshot(nowMs: Long): LoneWorkerResume.State {
        val suspected = accidentUntil != Long.MIN_VALUE
        return LoneWorkerResume.State(
            if (suspected) maxOf(accidentFrom, lastDistinctAt) else null,
            if (suspected) accidentUntil else null,
            when (mode) { Mode.CHECKING -> trigger; Mode.WATCHING -> hold.check; else -> "" },
            charging, carried, siren.base(stillBase, nowMs, stillMs), zoneSettled,
            if (zone.inside) zone.since else null, mounted
        )
    }

    /**
     * Starts and resumes from the saved state if any. If the saved charging value differs from
     * the current power (plugged), a restart power hold applies (RestartHold).
     */
    fun startFrom(nowMs: Long, zoneInside: Boolean, plugged: Boolean, saved: LoneWorkerResume.State?) {
        start(nowMs, zoneInside, saved?.charging ?: plugged)
        if (saved != null && saved.charging != plugged) hold.holdPower(nowMs)
        powerRaw(plugged, nowMs)
        saved?.let { resume(it, nowMs) }
    }

    /**
     * Raw power value (sticky = sticky battery correction, dropped while a change is pending). True
     * if applied or the pending state changed (the monitor then judges and renders).
     */
    fun powerRaw(on: Boolean, tMs: Long, sticky: Boolean = false): Boolean = order.raw(on, tMs, sticky)

    /** True if a power change stable for 2 s that needs no deferral was applied (JudgeOrder.settle). */
    fun settlePower(t: Long): Boolean = order.settle(t)

    /**
     * Drops accident suspicion past its end (5 min after the trigger); an open check window restarts its response time. When
     * restoring into an equipment mount from a save not made while mounted, no-motion counts from the restart.
     * If inside the zone when saved, the inside state, entry time and settling carry over; if
     * outside the zone at start, a zone report is awaited for up to ZONE_RESUME_HOLD_MS.
     */
    private fun resume(s: LoneWorkerResume.State, nowMs: Long) {
        if (s.accidentHold != null && s.accidentUntil != null && s.accidentUntil > nowMs) {
            accidentFrom = s.accidentHold
            lastDistinctAt = s.accidentHold
            accidentUntil = s.accidentUntil
        }
        carried = s.carried
        stillBase = if (mounted && !s.mounted) nowMs else s.stillBase // Unsaved mount count (v2 or unmounted save): ignore baseline, count from restart
        s.zoneSince?.let {
            if (!zone.inside) hold.holdZone(nowMs + ZONE_RESUME_HOLD_MS)
            zone.reset(true, it)
            zoneSettled = s.zoneSettled
        }
        if (hold.powerHeld(nowMs)) hold.holdCheck(s.check) else if (s.check.isNotEmpty()) toChecking(s.check, nowMs, nowMs)
        if (zoneSettled) closeCheck(nowMs, "still") // Restoring a settled state neither opens nor holds a no-motion window
    }

    fun responseLeftMs(nowMs: Long): Long = sosAt()?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L
    fun responseTotalMs(): Long = respFor(trigger)  // Full response time of current window (ring basis, same rule as deadline)

    /**
     * Monitor scheduling (nextCheckAt), wake lock (waitingToJudge), flush (waitingOnSensors), immediate judgment (dueNow) — JudgeOrder.
     */
    fun nextCheckAt(nowMs: Long): Long? = order.nextCheckAt(nowMs)
    fun waitingToJudge(nowMs: Long): Boolean = order.waitingToJudge(nowMs)
    fun waitingOnSensors(nowMs: Long): Boolean = order.waitingOnSensors(nowMs)
    fun dueNow(nowMs: Long): Boolean = order.dueNow(nowMs)

    private fun respFor(trig: String): Long = if (trig == "fall") ACCIDENT_RESPONSE_MS else responseMs

    /**
     * Time up to which sensor data has arrived: the end of the last closed accelerometer window, or
     * the earlier of that and the step delivery time when the step sensor is used.
     */
    private fun sensedTo(): Long = if (stepsAvailable) minOf(walk.closedTo, walk.stepSeenTo) else walk.closedTo

    /**
     * Deadline to open the accident check window: 30 s after the last clear movement
     * (or the trigger if none). None beyond the 5-minute suspicion.
     */
    private fun accidentOpenAt(): Long? {
        if (accidentUntil == Long.MIN_VALUE) return null
        val at = maxOf(accidentFrom, lastDistinctAt) + ACCIDENT_STILL_MS
        return if (at <= accidentUntil) at else null
    }

    /** Check window → SOS deadline. */
    private fun sosAt(): Long? = if (mode == Mode.CHECKING) modeSinceMs + respFor(trigger) else null

    /**
     * Deadline to open the accident check window: only while watching, or while a no-motion check window is open and the
     * accident window's SOS deadline (open time + ACCIDENT_RESPONSE_MS) is earlier than that window's SOS deadline (a deadline
     * that cannot open is neither judged nor waited on).
     */
    private fun fallOpenAt(nowMs: Long): Long? {
        val at = accidentOpenAt() ?: return null
        val open = mode == Mode.WATCHING || (trigger == "still" &&
            sosAt()?.let { maxOf(nowMs, at) + respFor("fall") < it } == true)
        return if (open) at else null
    }

    /**
     * Deadline to open the no-motion check window: baseline + stillMs, when carried, outside a settled zone, not
     * masked by the siren pause (a deadline passed before the pause is still judged), and watching.
     */
    private fun stillOpenAt(): Long? = (stillBase + stillMs).takeIf {
        enabled && !zoneSettled && !siren.covers(it) && rest == Rest.NONE && mode == Mode.WATCHING }

    /**
     * Deadlines awaiting judgment: only the hold gate time if there is one (RestartHold.gate),
     * otherwise the no-motion / accident window openings and the SOS deadline.
     */
    private fun deadlines(nowMs: Long): List<Long> = hold.gate(nowMs)?.let { listOf(it) } ?: listOfNotNull(stillOpenAt(), fallOpenAt(nowMs), sosAt())

    /**
     * Accident suspicion judgment. At the accident deadline, opens the accident check window. If a no-motion check window is
     * already open, the earlier of the two deadlines is kept. If not reached within 5 min, the suspicion ends.
     */
    private fun accidentTick(nowMs: Long) {
        if (accidentUntil == Long.MIN_VALUE) return
        if (accidentOpenAt() == null) {
            if (nowMs >= accidentUntil) clearAccident()
            return
        }
        val openAt = fallOpenAt(nowMs) ?: return
        if (order.due(openAt, nowMs)) toChecking("fall", openAt, nowMs)
    }

    /**
     * Clear movement: closes the accident window and moves the accident count baseline; unless equipment-mounted, also
     * restarts the no-motion count and closes an open no-motion window (an equipment mount does not use steps). Accident
     * suspicion continues; the end of waiting (carried) is handled separately by the chargeAt rule.
     */
    private fun onDistinct(t: Long) {
        if (t > lastDistinctAt) lastDistinctAt = t
        floorAt = t
        if (mounted) return closeCheck(t, "fall") // Mounted: steps don't reset/close no-motion; they still close the accident window
        raiseStillBase(t)
        closeCheck(t)
    }

    private fun carry(t: Long) {
        carried = true
        stillHeld = false
        raiseStillBase(t)
    }

    private fun raiseStillBase(t: Long) {
        if (t > stillBase) stillBase = t
    }

    /**
     * Single close rule: closes the open check window (only that kind if kind is given) together with the restored window
     * held during the power hold. SOS ends only via cancelSos; accident suspicion is ended by the caller.
     */
    private fun closeCheck(t: Long, kind: String = "") {
        hold.dropCheck(kind)
        if (mode == Mode.CHECKING && (kind.isEmpty() || trigger == kind)) toWatching(t)
    }

    private fun clearAccident() {
        accidentFrom = Long.MIN_VALUE
        accidentUntil = Long.MIN_VALUE
    }

    /**
     * If the restart zone hold limit has passed, first report leaving at that time without a settle
     * calculation; no promotion to settled during the hold — settling comes only from inside reports.
     */
    private fun updateSettle(nowMs: Long) {
        hold.zoneExpired(nowMs)?.let { leaveZone(it) }
        if (!hold.zoneHeld && zone.inside &&!zoneSettled && nowMs - zone.since >= ZONE_SETTLE_MS) {
            zoneSettled = true
            closeCheck(nowMs, "still")
        }
    }

    private fun toWatching(nowMs: Long) {
        raiseStillBase(nowMs)
        mode = Mode.WATCHING
        trigger = ""
        modeSinceMs = nowMs
    }

    /** floor = step-count floor (the deadline in sensor time), nowMs = actual open time (start of the response time). */
    private fun toChecking(trig: String, floor: Long, nowMs: Long) {
        mode = Mode.CHECKING
        trigger = trig
        modeSinceMs = nowMs
        floorAt = floor
        shakeFloorAt = floor
    }

    private fun toSos(nowMs: Long) {
        mode = Mode.SOS
        modeSinceMs = nowMs
        clearAccident()
    }

    /**
     * Alarm vibration only for the peer rescue-request siren, when a vibrator exists. A device that may need rescue
     * itself (check window, own SOS, accident suspicion) uses sound and screen only, without vibration.
     */
    val alarmVibrates: Boolean
        get() = canVibrate && mode == Mode.WATCHING && accidentUntil == Long.MIN_VALUE && peerStore.audible().isNotEmpty()
}

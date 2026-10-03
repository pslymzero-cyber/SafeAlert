package com.wf11.safealert.ble

import kotlin.math.roundToInt

/**
 * RSSI pre-processing — asymmetric proportional-control (Asymmetric P-Control) EMA LPF.
 *
 * A single first-order proportional-control low-pass filter based on an exponential moving average (EMA).
 *   Formula:  S_t = S_{t-1} + α · (R_t − S_{t-1})   (current estimate = previous estimate + gain × error)
 *
 * Asymmetric P-gain: RSSI is negative (closer to 0 = stronger = nearer).
 *   ① R_t ≥ S_{t-1} (signal rising = approach/danger)           → α = ALPHA_RISE (0.3)  fast tracking
 *   ② R_t <  S_{t-1} (signal falling = noise, e.g. steel racks) → α = ALPHA_FALL (0.12) slow (ignores noise)
 *      ※ The fall α also slows real departures (signal decrease) and can delay the SAFE transition
 *        (intended trade-off). The raw-based second line of defense in AlertStateMachine.processAlert (avg1sec,
 *        medianValue) — ⒜ forced-SAFE distance guard, ⒝ fade-out vs peak, ⒞ reverse/loading hybrid
 *        cross-check — compensates, so on a real
 *        departure the alert is released/blocked quickly via the raw path.
 *
 * Derivative (velocity)-linked D-Boost: the approach velocity refined by the 2D Kalman (prevVel, dBm/s) is
 * fed back to vary α.
 *   ※ Sign rule (KalmanFilter): vel>0 = RSSI rising = approaching (rush). In RSSI space the D-Boost
 *      condition is prevVel > +VEL_DBOOST_DBM (a distance-space 'velocity < −2.0' has the opposite sign).
 *   prevVel > VEL_DBOOST_DBM(+2.0) (strong rush) → even if the signal dips briefly (R<S), ignore the noise
 *      defense floor α=ALPHA_FALL and open the filter latch fully with α = ALPHA_DBOOST(0.4), minimizing
 *      filter lag to keep the survival reaction speed.
 *
 * Only the refined output (smoothedRssi) is fed to the 2D Kalman filter as its measurement (never raw).
 *
 * Reuse with parameters: the same asymmetric EMA core is also reused as the Kalman post-processing P-EMA.
 *   · Default constructor (no args) = front EMA: rise 0.3 / fall 0.12 / D-Boost 0.4, D-Boost ON.
 *   · Post-processing P-EMA: RssiPreFilter(alphaRise=0.4, alphaFall=0.15, dBoostEnabled=false).
 *     The Kalman already reflects velocity (D), so D-Boost is off in the P-EMA stage (distance P-term only).
 *
 * Warm-up symmetrization: fixes the between-session drift where the settled RSSI differs on every app
 *   restart at the same spot with the same device. Cause: the first sample is trusted as the anchor, but raw
 *   BLE RSSI spreads ±8~12dB even at a fixed distance because of the 3 advertising channels and multipath,
 *   so the anchor itself is luck of the draw per session. If the anchor happens to read high (strong), the
 *   slow fall α takes much longer than a rise would to return to the true value, and this residue makes the
 *   level decisions of the first few seconds differ per session.
 *   Fix: for each device, during the first warmupSymmetricPushes pushes (default 10, about 3.3 s at 3Hz) the
 *   fall alpha equals the rise alpha (symmetric), so a bad anchor is corrected quickly at the same speed in
 *   both directions. The rise and D-Boost paths are unchanged → approach (danger) tracking is never slower.
 *   After warm-up it returns fully to the asymmetric behavior (noise defense). 0 = off.
 */
class RssiPreFilter(
    // The three alphas are var — tunable live from developer settings (DevSettings);
    // applied immediately while keeping emaState.
    var alphaRise:     Double  = ALPHA_RISE,
    var alphaFall:     Double  = ALPHA_FALL,
    var alphaDBoost:   Double  = ALPHA_DBOOST,
    // Warm-up symmetric push count — var: tunable live in DevSettings (0 = off).
    // Shared by the front and post-processing filters.
    var warmupSymmetricPushes: Int = WARMUP_SYMMETRIC_PUSHES,
    private val dBoostEnabled: Boolean = true,
) {

    companion object {
        // Asymmetric proportional gains (α) — front EMA defaults
        const val ALPHA_RISE     = 0.3    // Signal rising (approach/danger): fast tracking
        const val ALPHA_FALL     = 0.12   // Faster departure (release) tracking — less flapping (simulation-verified)
        const val ALPHA_DBOOST   = 0.4    // Strong rush (D-Boost): latch fully open
        // D-Boost threshold: Kalman-estimated approach velocity (dBm/s). In RSSI space, positive (+) = approaching.
        const val VEL_DBOOST_DBM = 2.0
        // Default warm-up symmetric push count — about 3.3 s at 3Hz advertising
        const val WARMUP_SYMMETRIC_PUSHES = 10
        // Fall-alpha boost for shadow-IMU-fusion departure-confirmed frames — faster DANGER release (-42% simulated)
        const val FALL_BOOST_ALPHA = 0.4
    }

    // Per-device EMA state S_{t-1} (kept in Double precision; only the output is quantized to Int)
    private val emaState = mutableMapOf<String, Double>()
    // Per-device cumulative push count — detects the warm-up (symmetric fall) phase. Anchor (first sample) = 1.
    private val pushCount = mutableMapOf<String, Int>()

    /**
     * Refines a new RSSI sample with the asymmetric EMA and returns it.
     *
     * @param deviceId device identifier (independent state per device)
     * @param rssi     raw RSSI (dBm, negative)
     * @param prevVel  previous-frame Kalman velocity (dBm/s). + approaching / − leaving. 0.0 on the first frame.
     * @param fallBoost departure-confirmed frame from shadow IMU fusion — boosts only the fall (R<S) alpha to
     *                  FALL_BOOST_ALPHA (0.4) to speed release (departure) tracking. Rise and D-Boost unchanged.
     * @return refined RSSI (smoothedRssi) to feed the 2D Kalman filter
     */
    fun push(deviceId: String, rssi: Int, prevVel: Double = 0.0, fallBoost: Boolean = false): Int {
        // First sample: initialize the state (removes cold-start delay) — trust the raw value as is
        val prev = emaState[deviceId] ?: run {
            emaState[deviceId] = rssi.toDouble()
            pushCount[deviceId] = 1   // Anchor push = 1. Warm-up restarts on rediscovery (after clear).
            return rssi
        }

        // Warm-up count — pushes after the anchor count up 2, 3, ...
        val n = (pushCount[deviceId] ?: 1) + 1
        pushCount[deviceId] = n
        // During warm-up (n ≤ warmupSymmetricPushes) the fall also tracks with the rise alpha (symmetric):
        //   quickly corrects an anchor that happened to read high (in about 3.3 s).
        //   Rise and D-Boost branches are unchanged.
        val fallEff = if (n <= warmupSymmetricPushes) alphaRise else alphaFall

        val r = rssi.toDouble()
        val alpha = when {
            // D-Boost: strong rush (steep approach velocity) → open the latch even on a brief signal dip (R<S)
            dBoostEnabled && prevVel > VEL_DBOOST_DBM -> alphaDBoost
            // Signal rising (R ≥ S): danger direction → fast tracking
            r >= prev                                 -> alphaRise
            // Shadow departure confirmed (DANGER release): boost fall frames only (rise is matched first, so unchanged)
            fallBoost                                 -> FALL_BOOST_ALPHA
            // Signal falling (R < S): suspected noise such as steel-rack interference →
            //   very slow tracking (symmetric during warm-up)
            else                                      -> fallEff
        }

        val s = prev + alpha * (r - prev)
        emaState[deviceId] = s
        return s.roundToInt()
    }

    fun clear(deviceId: String) { emaState.remove(deviceId); pushCount.remove(deviceId) }
    fun clearAll() { emaState.clear(); pushCount.clear() }
}

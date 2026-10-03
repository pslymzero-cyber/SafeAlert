package com.wf11.safealert.ble

import com.wf11.safealert.utils.DevSettings
import kotlin.math.pow

/**
 * 2D Kalman filter — tracks RSSI (dBm) and its rate of change (dBm/s) together.
 *
 * State vector: x = [rssi, vel]^T
 *   rssi : estimated RSSI (dBm)
 *   vel  : RSSI rate of change (dBm/s)
 *          ★ Sign rule: positive (+) = RSSI rising = pedestrian approaching
 *                       negative (-) = RSSI falling = pedestrian leaving
 *          RSSI is stronger the closer it is to 0 (-80 → -40),
 *          so vel > 0 means the signal is getting stronger = approaching.
 *
 * Input: refined RSSI from RssiPreFilter (never feed raw data directly)
 * Transition model (constant velocity): F = [[1, dt], [0, 1]]
 * Observation model: H = [1, 0]
 * Process noise: Q = q × [[dt⁴/4, dt³/2], [dt³/2, dt²]]
 *
 * Per-preset parameters:
 *   FAST   — q=0.50, R=2.0   fast response, noisy velocity estimate
 *   NORMAL — q=0.15, R=5.0   balanced (default)
 *   SMOOTH — q=0.05, R=10.0  slow response, stable velocity estimate
 */
class KalmanFilter(
    private var preset: Int = DevSettings.KALMAN_PRESET_NORMAL,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    // ── State variables ─────────────────────────────────────────────────────
    private var rssi: Double = 0.0    // Estimated RSSI (dBm)
    private var vel:  Double = 0.0    // RSSI rate (dBm/s), positive = approaching / negative = leaving
    // 2×2 covariance matrix (symmetric: pRV == pVR, so stored as 3 values)
    private var pRR: Double = 100.0   // Variance: rssi-rssi
    private var pRV: Double = 0.0     // Covariance: rssi-vel
    private var pVV: Double = 100.0   // Variance: vel-vel
    private var initialized: Boolean = false
    private var lastTsMs:    Long    = 0L
    private var updateCnt:   Int     = 0      // Cumulative update count — for the geometry-check warm-up gate

    // ── Per-preset parameters ─────────────────────────────────────────────
    /** Process noise q ((dBm/s²)²) */
    private val processNoise: Double
        get() = when (preset) {
            DevSettings.KALMAN_PRESET_FAST   -> 0.50
            DevSettings.KALMAN_PRESET_NORMAL -> 0.15
            else                             -> 0.05
        }
    /** Observation noise R (dBm²) */
    private val measureNoise: Double
        get() = when (preset) {
            DevSettings.KALMAN_PRESET_FAST   -> 2.0
            DevSettings.KALMAN_PRESET_NORMAL -> 5.0
            else                             -> 10.0
        }

    fun updatePreset(p: Int) { preset = p }

    // ── Public read-only state ────────────────────────────────────────────────
    /** Estimated RSSI (dBm). 0.0 when uninitialized */
    val estimatedRssi:  Double  get() = if (initialized) rssi else 0.0
    /** Estimated rate (dBm/s). Positive = approaching / negative = leaving. 0.0 when uninitialized */
    val estimatedVel:   Double  get() = if (initialized) vel  else 0.0
    val isInitialized:  Boolean get() = initialized
    /** Cumulative update count. Used to defer false side-course verdicts while cold (vel ≈ initial 0.0) */
    val updateCount:    Int     get() = updateCnt

    /**
     * Updates the filter with a new refined RSSI sample.
     *
     * @param filteredRssi RssiPreFilter output (refined RSSI, dBm)
     * @param imuQScale    IMU adaptive Q scale (ImuFusion.adaptiveQFactor)
     *                     still≈0.3 / normal≈1.0 / fast movement≈2.0
     * @return Pair(estimated RSSI dBm, estimated rate dBm/s)
     *         vel > 0 = approaching / vel < 0 = leaving
     */
    fun update(filteredRssi: Int, imuQScale: Double = 1.0): Pair<Double, Double> {
        val meas  = filteredRssi.toDouble()
        val now = nowMs()
        updateCnt++   // Incremented on both the init and normal paths

        if (!initialized) {
            rssi        = meas
            vel         = 0.0
            pRR         = 5.0
            pRV         = 0.0
            pVV         = 5.0
            initialized = true
            lastTsMs    = now
            return Pair(rssi, vel)
        }

        val dt = ((now - lastTsMs) / 1000.0).coerceIn(0.05, 2.0)
        lastTsMs = now

        // ── Predict step ─────────────────────────────────────────────────
        val predRssi = rssi + vel * dt
        val predVel  = vel

        val qs   = processNoise * imuQScale
        val qRR  = qs * dt.pow(4) / 4.0
        val qRV  = qs * dt.pow(3) / 2.0
        val qVV  = qs * dt.pow(2)

        val pRRP = pRR + 2.0 * pRV * dt + pVV * dt * dt + qRR
        val pRVP = pRV + pVV * dt + qRV
        val pVVP = pVV + qVV

        // ── Update step (H = [1, 0]) ────────────────────────────────────
        val s     = pRRP + measureNoise   // S = H·P'·H^T + R
        val kR    = pRRP / s              // RSSI Kalman gain
        val kV    = pRVP / s              // Velocity Kalman gain
        val innov = meas - predRssi

        rssi = predRssi + kR * innov
        vel  = predVel  + kV * innov
        pRR  = (1.0 - kR) * pRRP
        pRV  = (1.0 - kR) * pRVP
        pVV  = pVVP - kV * pRVP

        return Pair(rssi, vel)
    }

    /**
     * Cold-start warm-up injection — AlertStateMachine calls this with the first raw RSSI right after it creates a
     * device KalmanFilter. It initializes the state from the first sample at once to remove the cold-start
     * delay, but sets the initial covariance honestly: a single raw BLE RSSI sample has a standard deviation of
     * about 5dB because of the 3 advertising channels and multipath, so pRR=25 (= 5 squared), pVV=5.
     * An overconfident initial covariance makes the filter cling to a first sample that happened to read high
     * or low, which shifts the early baseline on every app restart (session). The honest initial variance
     * raises the Kalman gain so later observations quickly wash out the luck of the first sample.
     * (The regular init path in update, !initialized, uses pRR=5 — this path anchors at creation time with
     *  more uncertainty, so a larger initial variance is justified.)
     * initVel — reseeds the departure velocity captured just before a SAFE/departure cleanup (negative, capped
     *   at -1.5), so re-registering right after a shallow SAFE dip does not restart from velocity 0 and replay
     *   the departure check from scratch, which reduces flapping. Default 0.0.
     */
    fun injectWarmup(rssiVal: Int, initVel: Double = 0.0) {
        rssi        = rssiVal.toDouble()
        vel         = initVel
        pRR         = 25.0   // Honest single-sample variance (no overtrust in the first sample)
        pRV         = 0.0
        pVV         = 5.0    // Honest initial velocity uncertainty
        initialized = true
        lastTsMs    = nowMs()
    }

    /** Resets the state (on device loss / SAFE transition) */
    fun reset() {
        initialized = false
        vel         = 0.0
        updateCnt   = 0   // Reset the warm-up counter
    }
}

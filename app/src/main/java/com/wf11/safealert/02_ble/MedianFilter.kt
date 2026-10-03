package com.wf11.safealert.ble

/**
 * Nonlinear rank-statistic RSSI pre-processing — sliding-window median filter.
 *
 * Design intent: the asymmetric EMA and the 2D Kalman are both linear filters, so a single impulse (a value
 *   that jumps toward + for just 1 frame from multipath reflection off steel racks) is absorbed into the
 *   mean/covariance and leaves an echo. The median filter is a rank-statistic nonlinear filter: a single
 *   outlier in the window is never selected, so impulses are removed structurally. It therefore sits in
 *   front of the linear stages (EMA→Kalman) and blocks impulses before they contaminate the Kalman
 *   velocity (kfVel).
 *
 * Trade-off: the group delay of window N = (N−1)/2 samples; N=3 adds about 1 frame of rising-edge delay.
 *   The kfVel bypass in AlertStateMachine (Time-Gate fed directly by kfVel) and the conditional Kalman
 *   FAST promotion on a rush compensate for it to keep the survival reaction speed.
 *
 * Partial buffer: during cold start (window not full) it returns the median of the available samples
 *   (1 sample = raw pass-through). Contamination in this phase is blocked by the AlertStateMachine
 *   warm-up guard (alerts held until the window fills). With an even count (2 samples) it returns the
 *   weaker one (more negative), not the average: an average would turn a single spike [-50,-90] into -70,
 *   crossing the warning line and bypassing the warm-up guard via the fastContact path (e_singleSpike).
 */
class MedianFilter(private val windowSize: Int = DEFAULT_WINDOW) {

    companion object {
        const val DEFAULT_WINDOW = 3   // Balance of impulse removal vs group delay (~1 frame of delay)
    }

    // Per-device sliding window (FIFO). Keeps only the latest windowSize samples.
    private val buffers = mutableMapOf<String, ArrayDeque<Int>>()

    /**
     * Pushes a new RSSI sample into the window and returns the current window median.
     *
     * @param deviceId device identifier (independent window per device)
     * @param rssi     raw RSSI (dBm, negative)
     * @return window median (impulse-free RSSI). With an even count, the weaker (more negative) of the middle two.
     */
    fun push(deviceId: String, rssi: Int): Int {
        val buf = buffers.getOrPut(deviceId) { ArrayDeque() }
        if (buf.size >= windowSize) buf.removeFirst()   // Drop the oldest sample (FIFO)
        buf.addLast(rssi)

        val sorted = buf.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
               else sorted[n / 2 - 1]   // Partial buffer, even count: the weaker (more negative) sample
    }

    /** Whether the window is full. false = cold-start (warm-up) phase → used to hold alerts. */
    fun isFull(deviceId: String): Boolean = (buffers[deviceId]?.size ?: 0) >= windowSize

    fun clear(deviceId: String) { buffers.remove(deviceId) }
    fun clearAll() { buffers.clear() }
}

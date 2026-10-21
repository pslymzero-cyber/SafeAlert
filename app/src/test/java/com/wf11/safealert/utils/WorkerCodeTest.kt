package com.wf11.safealert.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Worker code (pure, no Android). */
class WorkerCodeTest {

    @Test
    fun worker_code_is_one_letter_and_up_to_nine_digits() {
        assertEquals("G6628767", WorkerCode.code("G6628767"))
        assertEquals("G6628767", WorkerCode.code("g6628767"))
        assertEquals("G1", WorkerCode.code(" G1 "))
        assertEquals("G123456789", WorkerCode.code("G123456789"))
        for (bad in listOf("G1234567890", "GG123", "G", "1234", "", "G12-3")) assertEquals(bad, "", WorkerCode.code(bad))
        assertEquals("", WorkerCode.code(null))
        assertEquals(WorkerCode.MAX_LEN, "G123456789".length)
    }

    @Test
    fun start_id_is_the_worker_code_for_a_walker_and_the_equipment_for_a_device() {
        val auto = { "SA-AUTO0001" }
        assertEquals("G6628767", WorkerCode.startId(true, "G6628767", "RT-07", auto))
        assertEquals("RT-07", WorkerCode.startId(false, "G6628767", "RT-07", auto))
        assertEquals("SA-AUTO0001", WorkerCode.startId(true, "", "RT-07", auto))
        assertEquals("SA-AUTO0001", WorkerCode.startId(false, "G6628767", "", auto))
        assertEquals("G12", WorkerCode.startId(true, "g12", "", auto))
    }
}

package com.novadrive.app.vehicle

import com.novadrive.vehicle.CabinState
import com.novadrive.vehicle.SeatId
import com.novadrive.vehicle.WindowId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC-015 B5: the spoken confirmation is derived from the state read back. */
class ActionAnnouncementTest {
    private val all = WindowId.entries.toSet()
    private fun windows(fl: Int, fr: Int = fl, rl: Int = fl, rr: Int = fl) = CabinState.DEFAULT.copy(
        windows = mapOf(WindowId.FRONT_LEFT to fl, WindowId.FRONT_RIGHT to fr, WindowId.REAR_LEFT to rl, WindowId.REAR_RIGHT to rr),
    )

    @Test
    fun windowSentences() {
        assertEquals("车窗都开了一半", ActionAnnouncement.window("set", all, windows(50), false))
        assertEquals("车窗都打开了", ActionAnnouncement.window("open", all, windows(100), false))
        assertEquals("车窗都关好了", ActionAnnouncement.window("close", all, windows(0), false))
        assertEquals("车窗都开到了30%", ActionAnnouncement.window("set", all, windows(30), false))
        assertEquals("主驾车窗开到了40%", ActionAnnouncement.window("adjust", setOf(WindowId.FRONT_LEFT), windows(40, 0, 0, 0), false, 20))
        assertEquals(
            "前排车窗开到了20%",
            ActionAnnouncement.window("adjust", setOf(WindowId.FRONT_LEFT, WindowId.FRONT_RIGHT), windows(20, 20, 0, 0), false, 20),
        )
        assertEquals("主驾车窗开到了40%，副驾车窗开到了20%",
            ActionAnnouncement.window("adjust", setOf(WindowId.FRONT_LEFT, WindowId.FRONT_RIGHT), windows(40, 20, 0, 0), false, 20))
    }

    @Test
    fun windowLimits() {
        assertEquals("车窗已经全开了", ActionAnnouncement.window("adjust", all, windows(100), true, 20))
        assertEquals("车窗已经关到底了", ActionAnnouncement.window("adjust", all, windows(0), true, -20))
        assertEquals("后排车窗已经关到底了",
            ActionAnnouncement.window("adjust", setOf(WindowId.REAR_LEFT, WindowId.REAR_RIGHT), windows(50, 50, 0, 0), true, -20))
    }

    @Test
    fun windowState() {
        assertEquals("车窗现在都关着", ActionAnnouncement.window("get_state", all, windows(0), false))
        assertEquals("现在主驾车窗开着40%，副驾车窗关着，左后车窗关着，右后车窗关着",
            ActionAnnouncement.window("get_state", all, windows(40, 0, 0, 0), false))
    }

    @Test
    fun seatSentences() {
        assertEquals("座椅降低了一档，现在是4档", ActionAnnouncement.seat("adjust_height", SeatId.DRIVER, 4, false, -1))
        assertEquals("座椅升高了一档，现在是6档", ActionAnnouncement.seat("adjust_height", SeatId.DRIVER, 6, false, 1))
        assertEquals("座椅升高了2档，现在是7档", ActionAnnouncement.seat("adjust_height", SeatId.DRIVER, 7, false, 2))
        assertEquals("座椅已经是最低了", ActionAnnouncement.seat("adjust_height", SeatId.DRIVER, 0, true, -1))
        assertEquals("副驾座椅已经是最高了", ActionAnnouncement.seat("adjust_height", SeatId.PASSENGER, 10, true, 1))
        assertEquals("座椅调到了3档", ActionAnnouncement.seat("set_height", SeatId.DRIVER, 3, false))
        assertEquals("座椅现在是5档", ActionAnnouncement.seat("get_state", SeatId.DRIVER, 5, false))
    }
}

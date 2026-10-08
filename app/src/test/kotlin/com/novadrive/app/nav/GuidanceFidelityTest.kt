package com.novadrive.app.nav

import com.novadrive.app.nav.GuidanceFidelity.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuidanceFidelityTest {
    @Test
    fun blankOrMissingTranscriptIsUnknown() {
        assertEquals(Result.UNKNOWN, GuidanceFidelity.compare("前方二百米左转", null))
        assertEquals(Result.UNKNOWN, GuidanceFidelity.compare("前方二百米左转", "  "))
    }

    @Test
    fun verbatimMatches() {
        assertEquals(Result.MATCH, GuidanceFidelity.compare("前方二百米左转", "前方二百米左转"))
    }

    @Test
    fun arabicAndChineseNumeralsAreEquivalent() {
        assertEquals(Result.MATCH, GuidanceFidelity.compare("前方二百米左转", "前方200米左转。"))
        assertEquals(Result.MATCH, GuidanceFidelity.compare("前方两百米右转", "前方200米右转"))
        assertEquals(Result.MATCH, GuidanceFidelity.compare("一点五公里后靠右", "1.5公里后靠右"))
        assertEquals(Result.MATCH, GuidanceFidelity.compare("前方二百零五米掉头", "前方205米掉头"))
        assertEquals(Result.MATCH, GuidanceFidelity.compare("十五米后直行", "15米后直行"))
    }

    @Test
    fun leftForRightIsADirectionMismatch() {
        assertEquals(Result.MISMATCH_DIRECTION, GuidanceFidelity.compare("前方二百米左转", "前方二百米右转"))
    }

    @Test
    fun missingDirectionIsAMismatch() {
        assertEquals(Result.MISMATCH_DIRECTION, GuidanceFidelity.compare("前方左转进入匝道", "前方左转"))
        assertEquals(Result.MISMATCH_DIRECTION, GuidanceFidelity.compare("前方左转后再左转", "前方左转"))
    }

    @Test
    fun keepLeftIsNotTurnLeft() {
        assertEquals(listOf("靠左"), GuidanceFidelity.directions("靠左行驶"))
        assertEquals(Result.MISMATCH_DIRECTION, GuidanceFidelity.compare("前方左转", "前方靠左"))
        assertEquals(Result.MISMATCH_DIRECTION, GuidanceFidelity.compare("前方靠左", "前方左转"))
    }

    @Test
    fun wrongNumberIsANumberMismatch() {
        assertEquals(Result.MISMATCH_NUMBER, GuidanceFidelity.compare("前方二百米左转", "前方三百米左转"))
        assertEquals(Result.MISMATCH_NUMBER, GuidanceFidelity.compare("一点五公里后靠右", "1公里后靠右"))
        assertEquals(Result.MISMATCH_NUMBER, GuidanceFidelity.compare("第二个出口", "出口"))
    }

    @Test
    fun numberParsing() {
        assertEquals(listOf(200.0), GuidanceFidelity.numbers("二百米"))
        assertEquals(listOf(1.5), GuidanceFidelity.numbers("一点五公里"))
        assertEquals(listOf(1.5), GuidanceFidelity.numbers("1.5公里"))
        assertEquals(listOf(10.0), GuidanceFidelity.numbers("十米"))
        assertEquals(listOf(12000.0), GuidanceFidelity.numbers("一万二千米"))
        assertEquals(listOf(305.0), GuidanceFidelity.numbers("三零五国道"))
    }
}

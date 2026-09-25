package com.novadrive.app.ui

import com.novadrive.app.ui.AffordanceMatcher.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** SPEC-010 A1 (every name alone, with verb and particles) and A2 (never inside a sentence). */
class AffordanceMatcherTest {

    @ParameterizedTest
    @MethodSource("spoken")
    fun everyNameMatchesAloneWithEachVerbAndParticle(case: Pair<String, String>) {
        val (utterance, id) = case
        val result = AffordanceMatcher.match(utterance, SCREEN)
        assertEquals(id, (result as? Result.Match)?.affordance?.id) { "「$utterance」 -> $result" }
    }

    @ParameterizedTest
    @MethodSource("sentences")
    fun aNameInsideALongerSentenceDoesNotMatch(utterance: String) {
        assertEquals(Result.None, AffordanceMatcher.match(utterance, SCREEN)) { "「$utterance」" }
    }

    @Test
    fun theVerbSaidIsReported() {
        assertEquals(Result.Match(CLIMATE, "关掉"), AffordanceMatcher.match("关掉空调", SCREEN))
        assertEquals(Result.Match(CLIMATE, null), AffordanceMatcher.match("空调", SCREEN))
    }

    @Test
    fun positionResolvesOnlyWhileANumberedListIsShown() {
        val rows = listOf(Affordance("i1", listOf("甲"), 1), Affordance("i2", listOf("乙"), 2))
        assertEquals("i2", (AffordanceMatcher.match("第二个", SCREEN + rows) as Result.Match).affordance.id)
        assertEquals("i2", (AffordanceMatcher.match("选第2个", SCREEN + rows) as Result.Match).affordance.id)
        assertEquals(Result.None, AffordanceMatcher.match("第二个", SCREEN))
        assertEquals(Result.None, AffordanceMatcher.match("第三个", SCREEN + rows))
    }

    @Test
    fun aSharedNameIsAmbiguous() {
        val twin = Affordance("other", listOf("空调"))
        assertEquals(Result.Ambiguous(listOf("climate", "other")), AffordanceMatcher.match("空调", SCREEN + twin))
    }

    @Test
    fun nothingOnScreenMatchesNothing() {
        assertEquals(Result.None, AffordanceMatcher.match("暂停", emptyList()))
        assertEquals(Result.None, AffordanceMatcher.match("", SCREEN))
        assertEquals(Result.None, AffordanceMatcher.match("小诺", SCREEN))
    }

    @Test
    fun registryPublishesAndWithdrawsBySource() {
        val registry = ScreenAffordances()
        registry.publish("bar", SCREEN)
        registry.publish("picker", listOf(Affordance("i1", listOf("甲"), 1)))
        assertEquals(SCREEN.size + 1, registry.current.value.size)
        registry.withdraw("picker")
        assertEquals(SCREEN, registry.current.value)
    }

    companion object {
        private val CLIMATE = Affordance("climate", listOf("空调"))
        private val SCREEN = listOf(
            Affordance("music_restart", listOf("上一首", "重新播放")),
            Affordance("music_play_pause", listOf("播放", "暂停", "停止")),
            Affordance("temp_down", listOf("温度减")),
            Affordance("temp_up", listOf("温度加")),
            CLIMATE,
            Affordance("recenter", listOf("回到当前位置", "定位")),
            Affordance("camera", listOf("摄像头", "相机")),
            Affordance("cancel", listOf("取消")),
        )

        @JvmStatic
        fun spoken(): List<Pair<String, String>> {
            val forms = listOf(
                "%s", "%s。", "小诺，%s", "点%s", "按%s", "打开%s", "关掉%s", "选%s",
                "帮我%s", "请%s", "%s一下", "%s吧", "帮我打开%s一下", "你好小诺，请按%s吧",
            )
            return SCREEN.flatMap { a -> a.names.flatMap { n -> forms.map { it.format(n) to a.id } } }
        }

        @JvmStatic
        fun sentences() = listOf(
            "把温度调低一点然后去公司", "暂停一下音乐", "空调开到二十四度", "我想听上一首歌",
            "导航去取消的地方", "打开相机拍一下前面", "定位到公司", "别暂停",
        )
    }
}

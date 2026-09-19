package com.novadrive.app

/**
 * What the driver hears when a tool call does not run.
 *
 * One place to read every sentence the model will be steered into saying after a refusal. It lives
 * apart from the dispatcher because the dispatcher decides *whether* something runs, and this
 * decides *how the driver is told* - and the second is edited far more often than the first.
 *
 * Every code a guard can return must appear here. A code with no wording reaches the model as a
 * bare string and gets improvised around, which is how a refusal turns into a false claim.
 */
object ToolFailureAdvice {
    private val ADVICE = mapOf(
        "NO_MATCH" to "屏幕上的候选里没有这个名字。请如实说没有这个选项，并请用户说第几个。",
        "AMBIGUOUS" to "有多个候选都符合这个名字。请如实说有多个，并请用户说第几个。",
        "OUT_OF_RANGE" to "屏幕上没有这一项。请如实说没有这一项，并说明一共有几个。",
        "NO_OPTIONS_ON_SCREEN" to "现在屏幕上没有候选列表。请如实说明，不要假装已经选择。",
        "OPTIONS_NOT_READY" to "候选还在计算中。请让用户稍等，不要假装已经选择。",
        "DISTANCE_UNKNOWN" to "这些候选没有距离信息，无法判断最近的。请用户说第几个。",
        "PREFERENCE_NOT_FOR_DESTINATIONS" to "这个偏好只能用于路线，不能用于地点。请用户说第几个。",
        "PREFERENCE_NOT_FOR_ROUTES" to "这个偏好只能用于地点，不能用于路线。请用户说第几条。",
        "NO_OPTIONS" to "现在没有可选的内容。请如实说明。",
        "AMBIGUOUS_REFERENT" to
            "用户这句话没有说明要调的是温度还是风量，之前的记录也无法确定，所以没有执行。" +
            "请只用一句话反问用户是温度还是风量，不要再调用任何工具，也不要说已经调好了。",
        "DUPLICATE_IN_TURN" to
            "这个操作在本轮已经执行过一次，没有重复执行。请根据上一次的结果回答，不要说又调了一次。",
        "MEDIA_LIBRARY_UNSUPPORTED" to
            "车上只有一首内置曲目，没有音乐库，无法搜索或指定歌曲。" +
            "请用一句话如实告诉用户放不了他要的那首歌，不要谎称已经播放，也不要改放其它曲子。",
        ToolCallGuards.HOME_NOT_SET to
            "用户还没有设置家的地址，所以没有导航。" +
            "请用一句话如实说还不知道他家在哪里，请他直接说出地址，不要猜一个地方。",
        ToolCallGuards.WORK_NOT_SET to
            "用户还没有设置公司的地址，所以没有导航。" +
            "请用一句话如实说还不知道他公司在哪里，请他直接说出地址，不要猜一个地方。",
    )

    fun forCode(code: String): String? = ADVICE[code]

    /** Not a failure: the call succeeded, but the driver will not feel it (SPEC-006 D1). */
    const val CLIMATE_OFF =
        "设定已经改了，但空调现在是关着的，用户感受不到任何变化。" +
            "请调用 control_climate{action=power_on} 把空调打开，成功后再用一句话告诉用户；" +
            "不要直接说已经调好了。"
}


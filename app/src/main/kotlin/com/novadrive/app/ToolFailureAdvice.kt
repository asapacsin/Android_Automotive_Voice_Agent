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
        "OPTIONS_STALE" to
            "这个列表已经显示了一段时间（或刚从休眠中醒来），为了不选错，这次没有选择。" +
                "请用一句话按 options_on_screen 简要重新说一遍有哪几项，再请用户说要第几个。不要说已经选好了。",
        "CONFIRM_CANDIDATE" to
            "没有完全同名的选项，但第 candidate_position 项「candidate_name」读音相近，没有选择任何一项。" +
                "请只用一句话问用户是不是这一项（说出序号和名称），等用户确认；不要说已经选好了。",
        "DISTANCE_UNKNOWN" to "这些候选没有距离信息，无法判断最近的。请用户说第几个。",
        "PREFERENCE_NOT_FOR_DESTINATIONS" to "这个偏好只能用于路线，不能用于地点。请用户说第几个。",
        "PREFERENCE_NOT_FOR_ROUTES" to "这个偏好只能用于地点，不能用于路线。请用户说第几条。",
        "NO_OPTIONS" to "现在没有可选的内容。请如实说明。",
        "AMBIGUOUS_REFERENT" to
            "用户这句话没有说明要调的是温度还是风量，之前的记录也无法确定，所以没有执行。" +
            "请只用一句话反问用户是温度还是风量，不要再调用任何工具，也不要说已经调好了。",
        "DUPLICATE_IN_TURN" to
            "这个操作在本轮已经执行过一次，没有重复执行。请根据上一次的结果回答，不要说又调了一次。",
        PhoneCallTool.NO_TELEPHONY to
            "这辆车上现在没有可用的通话功能（没有 SIM 卡），所以打不了电话。" +
            "请用一句话如实告诉用户，不要谎称已经拨号。",
        PhoneCallTool.UNCONFIRMED to
            "用户还没有确认要给这个人打电话，所以没有拨号。" +
            "请先用一句话问清楚是否要打给谁，得到答复后再调用一次。",
        PhoneCallTool.CONFIRMATION_STALE to
            "用户的确认已经是很久以前的了，不能当作现在的同意，所以没有拨号。" +
            "请重新问一次是否要打电话。",
        PhoneCallTool.CONTACT_NOT_FOUND to
            "通讯录里没有找到这个人，没有拨号。" +
            "请用一句话如实说没找到，请用户说完整的名字，不要猜一个人。",
        PhoneCallTool.CONTACTS_PERMISSION_DENIED to
            "没有通讯录权限，所以没有查找，也没有拨号。" +
            "请用一句话如实告诉用户需要通讯录权限才能打电话，不要说没找到这个人。",
        PhoneCallTool.CALL_FAILED to
            "拨号没有成功，没有接通。" +
            "请用一句话如实告诉用户没打出去，不要谎称已经接通。",
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

    /** Found one match and did not dial. A call is the one action here that cannot be taken back. */
    const val CONFIRM_CALL =
        "已经找到联系人，但还没有拨号。" +
            "请用一句话问用户是否要给这个人打电话；" +
            "用户确认后，再用 confirmed=true 再调用一次 place_call。" +
            "不要说已经打了。"

    /** More than one contact matched; the driver picks, the model does not guess. */
    const val CHOOSE_CONTACT =
        "有多个联系人符合这个名字，没有拨号。" +
            "请用一句话请用户说清楚是哪一位，不要自己选一个。"

    /** Not a failure: the call succeeded, but the driver will not feel it (SPEC-006 D1). */
    const val CLIMATE_OFF =
        "设定已经改了，但空调现在是关着的，用户感受不到任何变化。" +
            "请调用 control_climate{action=power_on} 把空调打开，成功后再用一句话告诉用户；" +
            "不要直接说已经调好了。"
}


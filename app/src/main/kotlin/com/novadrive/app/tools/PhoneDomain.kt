package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `phone` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object PhoneDomain : ToolDomain {
    override val id = "phone"

    override fun specs(): List<ToolSpec> {
        val placeCall = spec(
            name = "place_call",
            description = "给联系人打电话。用户说「打电话给XX」「给XX打个电话」时调用，contact 填人名。" +
                "第一次调用不要填 confirmed：工具只会找人，不会拨号。" +
                "按返回的 status 回答：confirm_required=请问用户是否要打；" +
                "ambiguous=有多个同名，请用户说清楚是哪一位。" +
                "只有用户明确答应后，才能用 confirmed=true 再调用一次。" +
                "Finds the contact first and asks; only dials on a second call with confirmed=true.",
            properties = JSONObject()
                .put(
                    "contact",
                    JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 40),
                )
                .put(
                    "confirmed",
                    JSONObject().put("type", "string").put("enum", JSONArray(listOf("true", "false")))
                        .put("description", "只有用户明确同意后才填 true"),
                ),
            required = listOf("contact"),
        )
        return listOf(placeCall.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        return null // No argument rules beyond the schema; the handler checks its own arguments.
    }
}

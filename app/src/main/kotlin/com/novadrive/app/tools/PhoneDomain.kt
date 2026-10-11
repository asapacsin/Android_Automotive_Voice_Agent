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
            description = "打电话：「打电话给XX」，contact 填人名。首次不填 confirmed，只找人不拨号。按 status：confirm_required=问是否要打；ambiguous=多个同名，问哪一位。用户明确答应后才用 confirmed=true 再调用。",
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

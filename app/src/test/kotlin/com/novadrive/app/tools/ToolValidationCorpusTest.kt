package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Characterisation (SPEC-016 B): validation codes captured from the catalogue before it was split
 * into domains. null = valid.
 */
class ToolValidationCorpusTest {
    private val long121 = "x".repeat(121)
    private val long201 = "x".repeat(201)
    private val long41 = "x".repeat(41)

    private val corpus: List<Triple<String, String, String?>> = listOf(
        // navigate_to
        Triple("navigate_to", """{"destination":"机场"}""", null),
        Triple("navigate_to", """{}""", "INVALID_FIELDS"),
        Triple("navigate_to", """{"destination":"a","x":1}""", "INVALID_FIELDS"),
        Triple("navigate_to", """{"destination":5}""", "INVALID_FIELD_TYPE"),
        Triple("navigate_to", """{"destination":"  "}""", "BLANK_DESTINATION"),
        Triple("navigate_to", """{"destination":"$long121"}""", "DESTINATION_TOO_LONG"),
        // open_app
        Triple("open_app", """{"app":"maps"}""", null),
        Triple("open_app", """{"app":"settings"}""", null),
        Triple("open_app", """{}""", "INVALID_FIELDS"),
        Triple("open_app", """{"app":"maps","x":"y"}""", "INVALID_FIELDS"),
        Triple("open_app", """{"app":1}""", "INVALID_FIELD_TYPE"),
        Triple("open_app", """{"app":"music"}""", "APP_NOT_ALLOWED"),
        // control_music
        Triple("control_music", """{"action":"play"}""", null),
        Triple("control_music", """{}""", "INVALID_FIELDS"),
        Triple("control_music", """{"action":"play","v":1}""", "INVALID_FIELDS"),
        Triple("control_music", """{"action":true}""", "INVALID_FIELD_TYPE"),
        Triple("control_music", """{"action":"pause"}""", "ACTION_NOT_ALLOWED"),
        // control_climate
        Triple("control_climate", """{"action":"power_on"}""", null),
        Triple("control_climate", """{"action":"set_temperature","value":22}""", null),
        Triple("control_climate", """{"action":"adjust_fan","value":-1}""", null),
        Triple("control_climate", """{"value":22}""", "INVALID_FIELDS"),
        Triple("control_climate", """{"action":"power_on","x":1}""", "INVALID_FIELDS"),
        Triple("control_climate", """{"action":3}""", "INVALID_FIELD_TYPE"),
        Triple("control_climate", """{"action":"explode"}""", "ACTION_NOT_ALLOWED"),
        Triple("control_climate", """{"action":"set_fan","value":"3"}""", "INVALID_FIELD_TYPE"),
        Triple("control_climate", """{"action":"set_fan"}""", "MISSING_VALUE"),
        // describe_camera_view
        Triple("describe_camera_view", """{"question":"前面有什么"}""", null),
        Triple("describe_camera_view", """{}""", "INVALID_FIELDS"),
        Triple("describe_camera_view", """{"question":"a","b":"c"}""", "INVALID_FIELDS"),
        Triple("describe_camera_view", """{"question":1}""", "INVALID_FIELD_TYPE"),
        Triple("describe_camera_view", """{"question":" "}""", "BLANK_QUESTION"),
        Triple("describe_camera_view", """{"question":"$long201"}""", "QUESTION_TOO_LONG"),
        // exit_navigation_mode / end_conversation
        Triple("exit_navigation_mode", """{}""", null),
        Triple("exit_navigation_mode", """{"a":"b"}""", "INVALID_FIELDS"),
        Triple("end_conversation", """{}""", null),
        Triple("end_conversation", """{"a":1}""", "INVALID_FIELDS"),
        // set_speech_output
        Triple("set_speech_output", """{"mode":"silent"}""", null),
        Triple("set_speech_output", """{"mode":"spoken"}""", null),
        Triple("set_speech_output", """{}""", "INVALID_FIELDS"),
        Triple("set_speech_output", """{"mode":"silent","x":1}""", "INVALID_FIELDS"),
        Triple("set_speech_output", """{"mode":0}""", "INVALID_FIELD_TYPE"),
        Triple("set_speech_output", """{"mode":"loud"}""", "MODE_NOT_ALLOWED"),
        // query_live_info
        Triple("query_live_info", """{"kind":"weather","where":"here","day":"tomorrow"}""", null),
        Triple("query_live_info", """{"kind":"along_route","category":"fuel"}""", null),
        Triple("query_live_info", """{"where":"here"}""", "INVALID_FIELDS"),
        Triple("query_live_info", """{"kind":"weather","x":"y"}""", "INVALID_FIELDS"),
        Triple("query_live_info", """{"kind":"weather","day":1}""", "INVALID_FIELD_TYPE"),
        Triple("query_live_info", """{"kind":"news"}""", "INVALID_KIND"),
        Triple("query_live_info", """{"kind":"weather","where":"${"x".repeat(21)}"}""", "INVALID_ARGUMENT"),
        // choose_navigation_option
        Triple("choose_navigation_option", """{"index":2}""", null),
        Triple("choose_navigation_option", """{"index":"3"}""", null),
        Triple("choose_navigation_option", """{"preference":"fastest"}""", null),
        Triple("choose_navigation_option", """{"name":"万达广场"}""", null),
        Triple("choose_navigation_option", """{}""", "INVALID_FIELDS"),
        Triple("choose_navigation_option", """{"index":1,"name":"a"}""", "INVALID_FIELDS"),
        Triple("choose_navigation_option", """{"other":1}""", "INVALID_FIELDS"),
        Triple("choose_navigation_option", """{"index":0}""", "INVALID_INDEX"),
        Triple("choose_navigation_option", """{"index":11}""", "INVALID_INDEX"),
        Triple("choose_navigation_option", """{"index":1.5}""", "INVALID_INDEX"),
        Triple("choose_navigation_option", """{"index":true}""", "INVALID_INDEX"),
        Triple("choose_navigation_option", """{"preference":"scenic"}""", "PREFERENCE_NOT_ALLOWED"),
        Triple("choose_navigation_option", """{"preference":1}""", "PREFERENCE_NOT_ALLOWED"),
        Triple("choose_navigation_option", """{"name":" "}""", "INVALID_NAME"),
        Triple("choose_navigation_option", """{"name":"$long41"}""", "INVALID_NAME"),
        Triple("choose_navigation_option", """{"name":7}""", "INVALID_NAME"),
        // save_place and place_call have no validate() branch: the catalogue accepts any shape.
        Triple("save_place", """{"slot":"home","address":"a"}""", null),
        Triple("save_place", """{"slot":"garage"}""", null),
        Triple("place_call", """{"contact":"张三"}""", null),
        Triple("place_call", """{"x":1}""", null),
        // unknown tool
        Triple("open_window", """{"which":"left"}""", null),
    )

    @Test
    fun `corpus has at least forty cases`() {
        assert(corpus.size >= 40)
    }

    @Test
    fun `every corpus case keeps its pre-split code`() {
        val mismatches = corpus.mapNotNull { (name, args, expected) ->
            val actual = RealtimeToolCatalog.validate(name, JSONObject(args))
            if (actual != expected) "$name $args expected=$expected actual=$actual" else null
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}

package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONObject

/**
 * Assembles the car domains into one tool list (SPEC-016 B, ADR-015). Each tool name belongs to
 * exactly one domain; a new domain is added to [PRODUCT], not to a central list.
 */
class ToolRegistry(domains: List<ToolDomain>) {
    private val domains: List<ToolDomain> = domains.toList()
    private val owners: Map<String, ToolDomain>

    init {
        val ids = this.domains.map { it.id }
        require(ids.size == ids.toSet().size) { "duplicate domain id" }
        val map = LinkedHashMap<String, ToolDomain>()
        this.domains.forEach { domain ->
            domain.specs().forEach { spec ->
                require(map.put(spec.name, domain) == null) { "tool ${spec.name} declared by two domains" }
            }
        }
        owners = map
    }

    /** All tools, flattened in domain order; fresh schema instances on every call. */
    fun tools(): List<ToolSpec> = domains.flatMap { it.specs() }

    /** Rule-violation code, or null when valid or the tool is unknown (dispatcher answers UNKNOWN_TOOL). */
    fun validate(name: String, args: JSONObject): String? = owners[name]?.validate(name, args)

    fun domainOf(name: String): ToolDomain? = owners[name]

    fun spec(name: String): ToolSpec? = owners[name]?.specs()?.firstOrNull { it.name == name }

    companion object {
        val PRODUCT: ToolRegistry by lazy {
            ToolRegistry(
                listOf(
                    NavigationDomain,
                    AppsDomain,
                    MediaDomain,
                    ClimateDomain,
                    VisionDomain,
                    PhoneDomain,
                    LiveInfoDomain,
                    SpeechDomain,
                ),
            )
        }
    }
}

package com.novadrive.contracts

/**
 * China-first product defaults for Nova Drive / 小诺.
 * Later ingress/entity-resolution layers may add Chinese-English code switching;
 * core logic stays locale/provider-neutral except for these documented defaults.
 */
object ChinaFirstDefaults {
    const val LOCALE: String = "zh-CN"
    const val PERSONA_NAME: String = "小诺"
    const val WAKE_PHRASE: String = "你好小诺"
    val unitSystem: UnitSystem = UnitSystem.METRIC
    val preferredCoordinateSystem: CoordinateSystem = CoordinateSystem.GCJ02
}

enum class UnitSystem {
    METRIC,
}

data class VoiceAssistantConfig(
    val locale: String = ChinaFirstDefaults.LOCALE,
    val personaName: String = ChinaFirstDefaults.PERSONA_NAME,
    val wakePhrase: String = ChinaFirstDefaults.WAKE_PHRASE,
    val unitSystem: UnitSystem = ChinaFirstDefaults.unitSystem,
)

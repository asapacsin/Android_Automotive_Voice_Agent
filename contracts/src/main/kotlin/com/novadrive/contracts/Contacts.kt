package com.novadrive.contracts

data class ContactQuery(
    val spokenName: String? = null,
    val pinyin: String? = null,
    val alias: String? = null,
    val phoneNumber: String? = null,
) {
    fun hasAnyIdentity(): Boolean =
        !spokenName.isNullOrBlank() ||
            !pinyin.isNullOrBlank() ||
            !alias.isNullOrBlank() ||
            !phoneNumber.isNullOrBlank()
}

data class ResolvedContact(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String,
    val aliases: List<String> = emptyList(),
    val pinyin: String? = null,
)

enum class ContactMatchKind {
    UNIQUE,
    AMBIGUOUS,
    NONE,
}

data class ContactResolution(
    val kind: ContactMatchKind,
    val candidates: List<ResolvedContact> = emptyList(),
)

object CommandBounds {
    const val HVAC_TEMP_MIN_C: Double = 16.0
    const val HVAC_TEMP_MAX_C: Double = 32.0
    const val FAN_MIN: Int = 0
    const val FAN_MAX: Int = 7
    const val VOLUME_MIN: Int = 0
    const val VOLUME_MAX: Int = 100
}

package dev.jellyboost.core.common.model

/** Only fetched on the detail path (the `PEOPLE` item field); list requests stay lean, so cards never carry this. */
data class Person(
    val id: String,
    val name: String,
    /** The character an [PersonKind.ACTOR] plays, when the server knows it. */
    val role: String? = null,
    val kind: PersonKind = PersonKind.OTHER,
    val primaryImageUrl: String? = null,
)

/**
 * Everything the server can return that this app has no use for collapses into [OTHER] rather than leaking an
 * SDK enum into the UI.
 */
enum class PersonKind {
    ACTOR,
    DIRECTOR,
    WRITER,
    PRODUCER,
    GUEST_STAR,
    OTHER,
}

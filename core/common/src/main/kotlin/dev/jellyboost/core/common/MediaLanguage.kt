package dev.jellyboost.core.common

/**
 * ISO 639-2 "undetermined" — what a stream or a sidecar's language is called when nothing on the
 * server named one (DUP-13).
 *
 * Shared between the player's stream matching and the download engine's sidecar naming: both have
 * to agree on this exact spelling, or a locally-muxed subtitle whose language is unknown will not
 * match the same stream re-resolved online.
 */
const val UNDEFINED_LANGUAGE = "und"

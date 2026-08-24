package dev.jellyboost.core.common

/**
 * ISO 639-2 "undetermined". Shared between the player's stream matching and the download engine's sidecar
 * naming: both must agree on this exact spelling, or a locally-muxed subtitle of unknown language will not
 * match the same stream re-resolved online.
 */
const val UNDEFINED_LANGUAGE = "und"

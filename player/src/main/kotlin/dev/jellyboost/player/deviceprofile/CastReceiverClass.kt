package dev.jellyboost.player.deviceprofile

/**
 * No API reports a receiver's real capabilities (`CastDevice`'s flags say nothing about codecs, and
 * CAF's `canDisplayType()` runs inside Google's Default Media Receiver), so this is a model-name
 * allowlist and every unrecognised name must land on [LEGACY_1080P].
 *
 * The ceilings are published decoder specs. `CastSessionCoordinator` logs the raw model name beside
 * the class it resolved to, so a misclassified receiver is a one-line addition here.
 */
internal enum class CastReceiverClass {
    /** Everything unrecognised: H.264 High L4.2 at 1080p, the measured-safe floor. */
    LEGACY_1080P,

    /** Chromecast HD: adds HEVC Main/Main 10 up to 1080p (level 4.1). */
    HEVC_1080P,

    /** Ultra / Google TV / SHIELD class: adds HEVC Main/Main 10 up to 4K (level 5.1). */
    ULTRA_4K,

    ;

    companion object {
        /**
         * `CastDevice.getModelName()` values whose published spec is HEVC Main 10 at 4K. SHIELD
         * appears under more than one name across firmware generations.
         */
        private val ULTRA_4K_MODELS =
            setOf(
                "chromecast ultra",
                "chromecast with google tv",
                "chromecast google tv",
                "google tv streamer",
                "shield android tv",
                "shield tv",
                "nvidia shield",
            )

        /** The 1080p Google TV dongle: HEVC Main 10, but only up to 1080p60. */
        private val HEVC_1080P_MODELS = setOf("chromecast hd")

        fun fromModelName(modelName: String?): CastReceiverClass =
            when (modelName?.trim()?.lowercase()) {
                in ULTRA_4K_MODELS -> ULTRA_4K
                in HEVC_1080P_MODELS -> HEVC_1080P
                else -> LEGACY_1080P
            }
    }
}

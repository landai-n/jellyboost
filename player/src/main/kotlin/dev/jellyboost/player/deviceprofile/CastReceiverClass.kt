package dev.jellyboost.player.deviceprofile

/**
 * What a Cast receiver can decode, as far as a sender is able to know it.
 *
 * There is no API for the real answer. The sender SDK's `CastDevice` capability flags cover
 * audio/video in/out and nothing about codecs or resolution, and CAF's `canDisplayType()` runs on
 * the receiver — which, with the Default Media Receiver, is Google's code we cannot ask. So this is
 * a **model-name allowlist**, the same trade every model-adaptive sender makes (jellyfin-web's
 * chromecast plugin included), and every name it does not recognise deliberately lands on
 * [LEGACY_1080P] — the profile every Cast receiver since the first dongle satisfies
 * (DECISIONS.md, 2026-08-15, M12 phase-2a).
 *
 * The ceilings each class stands for are published decoder specs, not guesses; what they buy is
 * decided in `CastDeviceProfile`. `CastSessionCoordinator` logs the raw model name next to the
 * class it resolved to, so a 4K receiver that lands in [LEGACY_1080P] is a one-line addition to
 * the allowlist rather than an investigation.
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
         * Names seen from `CastDevice.getModelName()` for receivers whose published spec is
         * HEVC Main 10 at 4K. SHIELD appears under more than one name across firmware generations.
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

        /** The class for [modelName], with `null` and every stranger falling to the safe floor. */
        fun fromModelName(modelName: String?): CastReceiverClass =
            when (modelName?.trim()?.lowercase()) {
                in ULTRA_4K_MODELS -> ULTRA_4K
                in HEVC_1080P_MODELS -> HEVC_1080P
                else -> LEGACY_1080P
            }
    }
}

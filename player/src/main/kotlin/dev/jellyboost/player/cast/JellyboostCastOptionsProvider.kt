package dev.jellyboost.player.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * How the Cast framework is configured for this app (docs/notes/chromecast-m12-plan.md, Phase 1).
 *
 * The framework instantiates this itself, reflectively, from the `OPTIONS_PROVIDER_CLASS_NAME`
 * meta-data in the manifest — so it is never referenced from Kotlin, and its fully-qualified name is
 * part of the manifest's contract. Renaming or moving the class means editing the manifest too.
 *
 * The receiver is Google's **default** media receiver, not the Jellyfin web receiver: M12 is
 * phone-orchestrated, and the phone negotiates the stream with the server itself and hands
 * the receiver a plain URL (plan, "Key design decisions" 1). Pointing at a styled receiver later is
 * a one-line change here.
 */
class JellyboostCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions =
            NotificationOptions
                .Builder()
                .apply {
                    // The Cast notification has to lead back into the app, but `:player` must not
                    // depend on `:app` to name the activity — the launcher intent answers it at
                    // runtime, exactly as `PlaybackService.launchIntent()` does for the media
                    // session. Left unset if the package somehow has no launcher entry, which only
                    // costs the notification its tap target.
                    launchActivityClassName(context)?.let(::setTargetActivityClassName)
                }.build()

        val mediaOptions =
            CastMediaOptions
                .Builder()
                .setNotificationOptions(notificationOptions)
                // No `setExpandedControllerActivityClassName`: the app's own PlayerScreen is the
                // remote control while casting (plan, decision 10), so there is no
                // ExpandedControllerActivity to open and the framework must not synthesise one.
                .build()

        return CastOptions
            .Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(mediaOptions)
            // Coming back to a session this app started (app switched away and back) reattaches to
            // it instead of leaving the receiver orphaned.
            .setResumeSavedSession(true)
            .build()
    }

    /** No custom session providers — the built-in Cast session is the only one this app speaks. */
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> = emptyList()

    private fun launchActivityClassName(context: Context): String? =
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.component
            ?.className
}

package dev.jellyboost.player.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Instantiated reflectively from the manifest's `OPTIONS_PROVIDER_CLASS_NAME` meta-data — never
 * referenced from Kotlin, so renaming or moving it means editing the manifest too.
 */
internal class JellyboostCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions =
            NotificationOptions
                .Builder()
                // The trampoline, never the launcher activity itself: the framework fires this with
                // `CLEAR_TASK`, which on `MainActivity` wiped the back stack (see the activity's KDoc).
                .setTargetActivityClassName(CastNotificationActivity::class.java.name)
                .build()

        val mediaOptions =
            CastMediaOptions
                .Builder()
                .setNotificationOptions(notificationOptions)
                // No `setExpandedControllerActivityClassName`: PlayerScreen is the remote control,
                // and the framework must not synthesise an ExpandedControllerActivity.
                .build()

        return CastOptions
            .Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(mediaOptions)
            .setResumeSavedSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> = emptyList()
}

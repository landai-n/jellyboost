package dev.jellyboost.player.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import timber.log.Timber

/**
 * The Cast notification's tap target, and nothing else: it starts the app's launcher activity with
 * [CastNotificationIntents.ACTION_OPEN_CASTING_PLAYER] and finishes, drawing nothing.
 *
 * It exists because of how the framework fires that notification's content intent: with
 * `NEW_TASK | CLEAR_TASK | TASK_ON_HOME`, read off a real device. Aimed at `MainActivity` directly,
 * `CLEAR_TASK` wiped the app's whole back stack — the player screen the user was on included — and
 * landed on Home with the television still playing. This activity has its **own task affinity** in
 * the manifest, so the only task that flag can clear is this activity's own; `noHistory` and
 * `excludeFromRecents` keep that task out of sight.
 *
 * Lives in `:player`, which cannot name `:app`'s classes: the launcher activity is resolved at runtime
 * ([CastNotificationIntents.forLauncher]), and [JellyboostCastOptionsProvider] names this class.
 */
class CastNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = CastNotificationIntents.forLauncher(this)
        if (launch == null) {
            Timber.w("No launcher activity to open from the Cast notification")
        } else {
            startActivity(launch)
        }
        // Before `onResume`, always: the manifest gives this a `NoDisplay` theme, which requires it.
        finish()
    }
}

/**
 * The contract between [CastNotificationActivity] and the activity it opens. Public because `:app`'s
 * `MainActivity` is the reader; kept here so the two halves cannot drift.
 */
object CastNotificationIntents {
    /** "Open the player for whatever this app is casting" — or Home, if that turns out to be nothing. */
    const val ACTION_OPEN_CASTING_PLAYER = "dev.jellyboost.player.cast.action.OPEN_CASTING_PLAYER"

    /**
     * `true` only for a fresh delivery: an activity recreated from Recents is handed the intent its
     * task was started with, and a notification tap from an earlier session must not be replayed then.
     */
    fun opensCastingPlayer(
        action: String?,
        flags: Int,
    ): Boolean = action == ACTION_OPEN_CASTING_PLAYER && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

    /**
     * Ordinary flags, never `CLEAR_TASK`: `NEW_TASK` finds the app's existing task and brings it
     * forward as it was, and `SINGLE_TOP` hands the request to the `MainActivity` already on top of it
     * (`onNewIntent`) rather than stacking a second one. With no task left, it starts one.
     */
    internal fun forLauncher(context: Context): Intent? {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        return component?.let {
            Intent(ACTION_OPEN_CASTING_PLAYER)
                .setComponent(it)
                .addFlags(LAUNCH_FLAGS)
        }
    }

    /** `internal` so a test can pin that `CLEAR_TASK` is not among them. */
    internal const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
}

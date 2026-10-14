package app.wakelink.android.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * A user-facing message as a string resource plus format args, so pure
 * logic (and its JVM unit tests) can pick *which* message without needing
 * a Context or a locale; the UI resolves it in the current language.
 */
data class UiText(@StringRes val res: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(res, *args.toTypedArray())
}

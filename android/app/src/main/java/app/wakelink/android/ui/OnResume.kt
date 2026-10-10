package app.wakelink.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Runs [block] every time the screen resumes -- used to re-check system
 * permissions the user may have just changed in the Settings app.
 */
@Composable
fun OnResume(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentBlock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

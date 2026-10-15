package app.wakelink.android.fcm

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.wakelink.android.data.TokenStore
import app.wakelink.android.data.isUserUnlocked
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Bridges FirebaseMessaging's Task-based token API to a suspend function. */
suspend fun currentFcmToken(): String = suspendCancellableCoroutine { cont ->
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { token -> cont.resume(token) }
        .addOnFailureListener { e -> cont.resumeWithException(e) }
}

/**
 * Re-sends the current FCM token: registration may have happened without
 * one (e.g. Play services were briefly unavailable), and onNewToken alone
 * doesn't cover that. Run on every unlocked process start and after boot.
 * Needs credential-protected storage (API key, WorkManager), so it's a
 * no-op before the first unlock.
 */
suspend fun uploadCurrentFcmToken(context: Context) {
    if (!isUserUnlocked(context) || !TokenStore(context).isRegistered) return
    val token = runCatching { currentFcmToken() }.getOrNull() ?: return
    WorkManager.getInstance(context).enqueue(
        OneTimeWorkRequestBuilder<TokenUploadWorker>()
            .setInputData(TokenUploadWorker.inputData(token))
            .build()
    )
}

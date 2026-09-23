package app.wakelink.android.fcm

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

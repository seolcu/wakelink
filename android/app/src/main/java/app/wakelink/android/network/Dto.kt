package app.wakelink.android.network

import com.squareup.moshi.Json

// Plain data classes parsed via Moshi's reflection-based KotlinJsonAdapterFactory
// (see ApiClient) -- no codegen annotation processor in the build, so no
// @JsonClass(generateAdapter = true) here; it would be inert without it.

data class RegisterRequest(
    @Json(name = "display_name") val displayName: String,
    @Json(name = "fcm_token") val fcmToken: String? = null,
)

data class RegisterResponse(
    @Json(name = "user_id") val userId: Long,
    @Json(name = "api_key") val apiKey: String,
)

data class UpdateTokenRequest(
    @Json(name = "fcm_token") val fcmToken: String,
)

data class CreateInviteResponse(
    @Json(name = "pairing_code") val pairingCode: String,
    @Json(name = "expires_at") val expiresAt: Long,
    @Json(name = "deep_link") val deepLink: String,
)

data class AcceptInviteRequest(
    @Json(name = "pairing_code") val pairingCode: String,
)

data class AcceptInviteResponse(
    @Json(name = "pairing_id") val pairingId: Long,
    @Json(name = "paired_with_name") val pairedWithName: String,
)

data class PairingView(
    val id: Long,
    @Json(name = "other_user_name") val otherUserName: String,
    @Json(name = "i_can_wake_them") val iCanWakeThem: Boolean,
    @Json(name = "they_can_wake_me") val theyCanWakeMe: Boolean,
    @Json(name = "created_at") val createdAt: Long,
    /** The contact's device key for sealing wakes end to end; null if their app predates it. */
    @Json(name = "other_public_key") val otherPublicKey: String? = null,
    /** False while the contact has switched off receiving wake-ups in their app. */
    @Json(name = "they_accept_wakes") val theyAcceptWakes: Boolean = true,
    /** My own per-contact switch: whether I accept wakes from them. */
    @Json(name = "i_accept_from_them") val iAcceptFromThem: Boolean = true,
)

/** Each field is optional; null leaves that switch as it is. */
data class PatchPairingRequest(
    /** My permission to wake them (my outbound direction). */
    @Json(name = "i_can_wake_them") val iCanWakeThem: Boolean? = null,
    /** Whether I accept wakes from them (my inbound consent). */
    @Json(name = "i_accept_from_them") val iAcceptFromThem: Boolean? = null,
)

data class TriggerResponse(
    @Json(name = "trigger_id") val triggerId: Long,
    val status: String,
)

data class TriggerView(
    val id: Long,
    @Json(name = "pairing_id") val pairingId: Long,
    @Json(name = "from_user_id") val fromUserId: Long,
    @Json(name = "to_user_id") val toUserId: Long,
    @Json(name = "created_at") val createdAt: Long,
    @Json(name = "send_status") val sendStatus: String,
    @Json(name = "ack_received_at") val ackReceivedAt: Long?,
    /** When the person woken stopped the alarm themselves ("they're up"). */
    @Json(name = "dismissed_at") val dismissedAt: Long? = null,
)

data class MeResponse(
    @Json(name = "user_id") val userId: Long,
    @Json(name = "display_name") val displayName: String,
)

data class PatchMeRequest(
    @Json(name = "display_name") val displayName: String,
)

data class RotateKeyResponse(
    @Json(name = "api_key") val apiKey: String,
)

/**
 * [sound] is a SoundRef key ("preset:chime", "custom:12"); null lets the
 * receiver pick. [image] is "custom:<id>" of one of the user's images, or
 * null for none.
 */
data class TriggerRequest(val sound: String? = null, val image: String? = null, val sealed: String? = null)

data class AcceptingRequest(val accepting: Boolean)

data class PublicKeyRequest(@Json(name = "public_key") val publicKey: String)

/** A recording, audio file or image the user uploaded to send with wake-ups. */
data class MediaView(
    val id: Long,
    val kind: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    @Json(name = "created_at") val createdAt: Long,
)

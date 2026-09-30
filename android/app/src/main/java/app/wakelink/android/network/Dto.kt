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
)

data class PatchPairingRequest(
    @Json(name = "i_can_wake_them") val iCanWakeThem: Boolean,
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

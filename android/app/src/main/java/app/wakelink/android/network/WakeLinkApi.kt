package app.wakelink.android.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface WakeLinkApi {
    @POST("v1/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @GET("v1/me")
    suspend fun getMe(): MeResponse

    @PATCH("v1/me")
    suspend fun patchMe(@Body body: PatchMeRequest): MeResponse

    @DELETE("v1/me")
    suspend fun deleteMe(): Response<Unit>

    @POST("v1/me/rotate-key")
    suspend fun rotateApiKey(): RotateKeyResponse

    @PUT("v1/me/accepting")
    suspend fun putAccepting(@Body body: AcceptingRequest): Response<Unit>

    @PUT("v1/me/public-key")
    suspend fun putPublicKey(@Body body: PublicKeyRequest): Response<Unit>

    @POST("v1/devices/token")
    suspend fun updateToken(@Body body: UpdateTokenRequest): Response<Unit>

    @POST("v1/invites")
    suspend fun createInvite(): CreateInviteResponse

    @POST("v1/invites/accept")
    suspend fun acceptInvite(@Body body: AcceptInviteRequest): AcceptInviteResponse

    @GET("v1/pairings")
    suspend fun listPairings(): List<PairingView>

    @PATCH("v1/pairings/{id}")
    suspend fun patchPairing(@Path("id") pairingId: Long, @Body body: PatchPairingRequest): Response<Unit>

    @DELETE("v1/pairings/{id}")
    suspend fun deletePairing(@Path("id") pairingId: Long): Response<Unit>

    @POST("v1/pairings/{id}/trigger")
    suspend fun trigger(@Path("id") pairingId: Long, @Body body: TriggerRequest = TriggerRequest()): TriggerResponse

    @POST("v1/triggers/{id}/ack")
    suspend fun ackTrigger(@Path("id") triggerId: Long): Response<Unit>

    @GET("v1/triggers")
    suspend fun listTriggers(@Query("pairing_id") pairingId: Long, @Query("limit") limit: Int = 20): List<TriggerView>

    @GET("v1/sounds")
    suspend fun listSounds(): List<MediaView>

    /** [audio] is the raw file; its Content-Type is the audio MIME type. */
    @POST("v1/sounds")
    suspend fun uploadSound(@Query("kind") kind: String, @Query("name") name: String, @Body audio: RequestBody): MediaView

    @DELETE("v1/sounds/{id}")
    suspend fun deleteSound(@Path("id") soundId: Long): Response<Unit>

    @Streaming
    @GET("v1/sounds/{id}/audio")
    suspend fun soundAudio(@Path("id") soundId: Long): ResponseBody

    @GET("v1/images")
    suspend fun listImages(): List<MediaView>

    /** [image] is the encoded file (JPEG/PNG/GIF/WebP); its Content-Type is the image MIME type. */
    @POST("v1/images")
    suspend fun uploadImage(@Query("name") name: String, @Body image: RequestBody): MediaView

    @DELETE("v1/images/{id}")
    suspend fun deleteImage(@Path("id") imageId: Long): Response<Unit>

    @Streaming
    @GET("v1/images/{id}/data")
    suspend fun imageData(@Path("id") imageId: Long): ResponseBody
}

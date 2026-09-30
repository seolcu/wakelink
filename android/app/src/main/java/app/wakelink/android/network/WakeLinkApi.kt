package app.wakelink.android.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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
    suspend fun trigger(@Path("id") pairingId: Long): TriggerResponse

    @POST("v1/triggers/{id}/ack")
    suspend fun ackTrigger(@Path("id") triggerId: Long): Response<Unit>

    @GET("v1/triggers")
    suspend fun listTriggers(@Query("pairing_id") pairingId: Long, @Query("limit") limit: Int = 20): List<TriggerView>
}

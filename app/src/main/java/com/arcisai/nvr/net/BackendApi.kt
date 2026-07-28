package com.arcisai.nvr.net

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Client for the Arcis cloud backend (Arcis_Main_Backend / Express + Mongo).
 *
 * Auth: the backend issues an HTTP-only `token` cookie on POST /auth/login.
 * OkHttp's [PersistentCookieStore] echoes it back on every subsequent request,
 * and survives app restarts. Logout calls /auth/logout (which the backend uses
 * to invalidate the Session row) and the local cookie store is cleared.
 *
 * Endpoint list mirrors the production ArcisAI-Android client where it covers
 * the same surface, plus the ABD-specific endpoints the NVR app needs.
 */
interface BackendApi {

    // ---- Auth -------------------------------------------------------------
    /** Body: `{email, password, rememberMe?}`. Response: cookie + AuthResponse. */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @GET("auth/logout")
    suspend fun logout(): GenericResponse

    /** Create a new Arcis account. Password must be AES-encrypted like login.
     *  On success the backend emails a registration OTP; the account stays
     *  unusable until POST auth/verify confirms it. */
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    /** Confirm the emailed registration OTP. Same endpoint the production
     *  ArcisAI-Android client uses (`auth/verify`, body `{email, otp}`). */
    @POST("auth/verify")
    suspend fun verifyRegistration(@Body body: VerifyOtpRequest): AuthResponse

    /** Re-send the registration OTP email. Body `{email}`. */
    @POST("auth/resendOtp")
    suspend fun resendOtp(@Body body: EmailRequest): AuthResponse

    /** Kick off password reset. Backend emails a link
     *  `view.arcisai.io/resetPassword/<token>` (token valid 15 min,
     *  rate-limited to 1 request/min/email). Body `{email}`. */
    @POST("auth/forgotPassword")
    suspend fun forgotPassword(@Body body: EmailRequest): AuthResponse

    /** Complete password reset with the emailed token. `password` and
     *  `confirmPassword` are BOTH AES-encrypted (backend decrypts the same
     *  way it does for login/register). Body `{token, password, confirmPassword}`. */
    @POST("auth/resetPassword")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): AuthResponse

    // ---- ABD (NVR) management --------------------------------------------
    /** Add a new NVR (ABD) to the current account. Backend validates the
     *  deviceId against EMS first; will 404 if EMS doesn't know it, 403 if
     *  it's already linked to another user, 400 if the name collides. */
    @POST("abd/addAbd")
    suspend fun addAbd(@Body body: AddAbdRequest): GenericResponse

    /** Returns the user's ABDs, each with its assigned sub-cameras (and
     *  credentials merged in). Body lets you filter by name substring. */
    @POST("abd/getAbd")
    suspend fun getAbd(@Body body: GetAbdRequest = GetAbdRequest()): AbdListResponse

    companion object {
        // dev backend — same as the existing ArcisAI-Android app uses.
        const val BASE_URL = "https://dev.arcisai.io/backend/api/"
        const val HOST     = "dev.arcisai.io"

        /** Build the singleton Retrofit instance for this app. */
        fun create(context: Context): BackendApi {
            val cookieJar = PersistentCookieStore(context.applicationContext)
            val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor(logger)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            cookieJarInstance = cookieJar
            okClientInstance  = client
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(BackendApi::class.java)
        }

        // Exposed so ViewModel can clear cookies on logout.
        @Volatile var cookieJarInstance: PersistentCookieStore? = null
            private set
        @Volatile var okClientInstance: OkHttpClient? = null
            private set
    }
}

// ---- Request bodies -------------------------------------------------------

data class LoginRequest(
    val email: String,
    val password: String,
    val rememberMe: Boolean = true,
)

/** Mirrors the production client's register body — password pre-encrypted
 *  with [AESEncryption.encrypt], acceptedTerms always true (UI enforces). */
data class RegisterRequest(
    val name: String,
    val mobile: String,
    val email: String,
    val password: String,
    val acceptedTerms: Boolean = true,
)

data class VerifyOtpRequest(
    val email: String,
    val otp: String,
)

data class EmailRequest(
    val email: String,
)

data class ResetPasswordRequest(
    val token: String,
    val password: String,
    val confirmPassword: String,
)

data class AddAbdRequest(
    val name: String,
    val deviceId: String,
)

data class GetAbdRequest(
    val search: String? = null,
)

// ---- Response shapes ------------------------------------------------------

/** /auth/login response. Backend returns mixed shapes ({success, data, name,
 *  email, role, ...}); we accept what we know and ignore the rest. */
data class AuthResponse(
    val success: Boolean = false,
    val data: String? = null,
    val name: String? = null,
    val email: String? = null,
    val message: String? = null,
)

data class GenericResponse(
    val success: Boolean = false,
    val message: String? = null,
    val data: Any? = null,
)

/** /abd/getAbd response. Each ABD has assignDevice[] of cameras + creds. */
data class AbdListResponse(
    val success: Boolean = false,
    val data: List<AbdDto> = emptyList(),
    val message: String? = null,
)

data class AbdDto(
    val _id: String? = null,
    val deviceId: String = "",
    val name: String = "",
    val status: String? = null,
    val channel: Int? = null,
    val productType: String? = null,
    val created_date: String? = null,
    val assignDevice: List<AbdAssignedDto> = emptyList(),
)

data class AbdAssignedDto(
    val id: String? = null,
    val ip: String? = null,
    val macAddress: String? = null,
    val onvif_port: Int? = null,
    val rtspUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null,
)

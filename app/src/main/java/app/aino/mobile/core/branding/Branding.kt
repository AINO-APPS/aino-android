package app.aino.mobile.core.branding

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.NetworkConfig
import app.aino.mobile.core.network.resolveApiUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `GET /branding` / `GET /public/branding` body. The authenticated route
 * answers `accent_color: "#6366f1"` when the org has no branding row; the
 * public route answers nulls on the master domain or an unbranded tenant.
 */
@Serializable
data class Branding(
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("accent_color") val accentColor: String? = null,
    @SerialName("org_name") val orgName: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** The read side of `client/src/api/meetings.ts` branding calls (P10.4). */
class BrandingRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** The caller's org branding (any org member; `ThemeContext` needs it). */
    fun current(): Branding {
        // @api GET branding
        return decode(api.execute(ApiRequest(path = "branding")).bodyAsString())
    }

    /**
     * The resolved tenant's branding for the signed-out pages. Android has no
     * `?org=` query, so tenant resolution relies on the API host, exactly
     * like a web client served from a tenant's custom domain.
     */
    fun publicBranding(): Branding {
        // @api GET public/branding
        return decode(api.execute(ApiRequest(path = "public/branding")).bodyAsString())
    }

    /** Web `Login.tsx` `logoSrc`: the public logo stream (no auth). */
    fun publicLogoUrl(apiUrl: String = NetworkConfig.apiUrl): String {
        // @api GET public/branding/logo
        return resolveApiUrl(apiUrl, "public/branding/logo")
    }

    private fun decode(body: String): Branding = json.decodeFromString(body.ifBlank { "{}" })
}

/**
 * Web `BrandingContext.tsx`: one process-wide branding value. Signed-in
 * tenant users get their org's branding (refetched on `branding_changed`);
 * signed-out screens get the public branding, fetched once per sign-out.
 */
class BrandingStore(
    private val repository: BrandingRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(Branding())
    val state: StateFlow<Branding> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var publicFetched = false

    /** Signed-in refresh; a failure keeps the current value (web: silent fallback). */
    fun refresh() {
        publicFetched = false
        load(fallback = null) { repository.current() }
    }

    /** Signed-out branding, once until the next sign-in; a failure falls back to the defaults. */
    fun refreshPublic() {
        if (publicFetched) return
        publicFetched = true
        load(fallback = Branding()) { repository.publicBranding() }
    }

    fun publicLogoUrl(): String = repository.publicLogoUrl()

    private fun load(fallback: Branding?, fetch: () -> Branding) {
        job?.cancel()
        job = scope.launch {
            val result = runCatching(fetch)
            if (!isActive) return@launch
            result.fold(
                onSuccess = { _state.value = it },
                onFailure = { fallback?.let { _state.value = it } },
            )
        }
    }
}

package app.aino.mobile.core.call.webrtc

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.json.Json

class IceConfigRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(): IceConfigDto {
        val response = api.execute(ApiRequest(path = "chat/ice-config"))
        return json.decodeFromString<IceConfigDto>(response.bodyAsString()).let { config ->
            config.copy(iceServers = applyPublicTurnPolicy(config.iceServers, config.allowPublicFallback))
        }
    }
}
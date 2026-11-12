package app.aino.mobile.core.network

import app.aino.mobile.core.update.ClientUpgradeState
import app.aino.mobile.core.update.ClientUpgrades

/** Observe below cache/auth handling, including responses from alternative transports. */
class UpgradeAwareApiClient(
    private val delegate: ApiClient,
    private val upgrades: ClientUpgradeState = ClientUpgrades.state,
) : ApiClient {
    override fun execute(request: ApiRequest): ApiResponse = try {
        delegate.execute(request).also { response ->
            if (response.statusCode == 426) {
                throw ApiError.Http(426, response.bodyAsString(), request.method, request.path)
            }
        }
    } catch (error: ApiError.Http) {
        upgrades.observe(error.statusCode, error.responseBody)
        throw error
    }
}

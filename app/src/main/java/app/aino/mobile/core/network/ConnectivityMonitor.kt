package app.aino.mobile.core.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the OS reports about the default network. Starts [Unknown] and is treated as online until told otherwise. */
sealed interface NetworkStatus {
    val usable: Boolean

    data object Unknown : NetworkStatus {
        override val usable = true
    }

    /** A default network exists; [validated] is false until the OS confirms it reaches the internet. */
    data class Available(val validated: Boolean) : NetworkStatus {
        override val usable = true
    }

    data object Unavailable : NetworkStatus {
        override val usable = false
    }
}

/**
 * Folds default-network callbacks into a [NetworkStatus] and decides when the
 * connections built on the previous network must be dropped: the default
 * network changed (Wi-Fi ↔ cellular), came back after a loss, or regained
 * internet validation. Sockets bound to the old path look alive but are dead.
 */
class NetworkTracker {
    @Volatile var status: NetworkStatus = NetworkStatus.Unknown
        private set
    private var current: String? = null
    private var validated = false
    /** The current network had internet, then lost it (stalled / captive): its return is a reset. */
    private var validationLost = false

    /** Returns true when existing connections should be reset. */
    @Synchronized
    fun onAvailable(network: String): Boolean {
        val previous = current
        val wasUnknown = status == NetworkStatus.Unknown
        if (network != previous) {
            validated = false
            validationLost = false
        }
        current = network
        status = NetworkStatus.Available(validated)
        return !wasUnknown && network != previous
    }

    @Synchronized
    fun onCapabilities(network: String, internet: Boolean, isValidated: Boolean): Boolean {
        if (network != current) return onAvailable(network).also { applyValidation(internet, isValidated) }
        val now = internet && isValidated
        val regained = !validated && now && validationLost
        if (validated && !now) validationLost = true
        if (regained) validationLost = false
        applyValidation(internet, isValidated)
        return regained
    }

    @Synchronized
    fun onLost(network: String) {
        if (network != current) return
        current = null
        validated = false
        validationLost = false
        status = NetworkStatus.Unavailable
    }

    private fun applyValidation(internet: Boolean, isValidated: Boolean) {
        validated = internet && isValidated
        status = NetworkStatus.Available(validated)
    }
}

/**
 * Process-wide view of the default network. [resets] fires when connections
 * opened on the previous network must be replaced (see [NetworkTracker]).
 */
class ConnectivityMonitor(private val connectivity: ConnectivityManager?) {
    private val tracker = NetworkTracker()
    private val _status = MutableStateFlow<NetworkStatus>(NetworkStatus.Unknown)
    private val _resets = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var started = false

    val status: StateFlow<NetworkStatus> = _status.asStateFlow()
    val resets: SharedFlow<Unit> = _resets.asSharedFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(tracker.onAvailable(network.toString()))

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish(
            tracker.onCapabilities(
                network.toString(),
                internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            ),
        )

        override fun onLost(network: Network) {
            tracker.onLost(network.toString())
            publish(false)
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = runCatching { connectivity?.registerDefaultNetworkCallback(callback) }.isSuccess && connectivity != null
    }

    private fun publish(reset: Boolean) {
        _status.value = tracker.status
        if (reset) _resets.tryEmit(Unit)
    }
}

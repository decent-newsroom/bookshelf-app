package eu.decentnewsroom.bookshelf.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/** Application-scoped validated-internet state. Local network access alone is not online. */
class ValidatedInternetConnectivity(context: Context) : Closeable {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val stateLock = Any()
    private var currentDefaultNetwork: Network? = connectivityManager.activeNetwork
    private val mutableOnline = MutableStateFlow(
        currentDefaultNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            .hasValidatedInternet(),
    )

    val online: StateFlow<Boolean> = mutableOnline.asStateFlow()
    val isOnline: Boolean get() = mutableOnline.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateForDefaultNetwork(network)

        override fun onLost(network: Network) = synchronized(stateLock) {
            if (currentDefaultNetwork == network) {
                currentDefaultNetwork = null
                mutableOnline.value = false
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(stateLock) {
            // A late callback for the former default must not overwrite a newer default network.
            if (currentDefaultNetwork == network) {
                mutableOnline.value = capabilities.hasValidatedInternet()
            }
        }
    }

    init { connectivityManager.registerDefaultNetworkCallback(callback) }

    override fun close() { connectivityManager.unregisterNetworkCallback(callback) }

    private fun updateForDefaultNetwork(network: Network) = synchronized(stateLock) {
        currentDefaultNetwork = network
        mutableOnline.value = connectivityManager.getNetworkCapabilities(network).hasValidatedInternet()
    }
}

private fun NetworkCapabilities?.hasValidatedInternet(): Boolean =
    this?.let { capabilities ->
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } ?: false

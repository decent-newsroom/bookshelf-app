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
    private val mutableOnline = MutableStateFlow(connectivityManager.hasValidatedInternet())

    val online: StateFlow<Boolean> = mutableOnline.asStateFlow()
    val isOnline: Boolean get() = mutableOnline.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onLost(network: Network) = update()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
    }

    init { connectivityManager.registerDefaultNetworkCallback(callback) }

    override fun close() { connectivityManager.unregisterNetworkCallback(callback) }

    private fun update() { mutableOnline.value = connectivityManager.hasValidatedInternet() }
}

private fun ConnectivityManager.hasValidatedInternet(): Boolean =
    activeNetwork
        ?.let(::getNetworkCapabilities)
        ?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        ?: false

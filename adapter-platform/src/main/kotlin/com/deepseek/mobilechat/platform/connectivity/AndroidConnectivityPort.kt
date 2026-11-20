package com.deepseek.mobilechat.platform.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.deepseek.mobilechat.application.ConnectivityPort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [ConnectivityPort] backed by `ConnectivityManager.NetworkCallback`.
 *
 * The flow emits the current state immediately, then updates whenever a
 * network becomes available/lost or its capabilities change. A network counts
 * as online only when it reports both INTERNET and VALIDATED: INTERNET alone is
 * true on a connected-but-captive Wi-Fi network where requests would still fail.
 */
class AndroidConnectivityPort(context: Context) : ConnectivityPort {

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            awaitClose()
            return@callbackFlow
        }

        trySend(manager.isOnline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(manager.isOnline())
            }

            override fun onLost(network: Network) {
                trySend(manager.isOnline())
            }

            override fun onUnavailable() {
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                // Any network callback may fire; the port reports the *active*
                // network's state, not the state of the network that changed.
                trySend(manager.isOnline())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
        } catch (_: SecurityException) {
            trySend(false)
            awaitClose()
            return@callbackFlow
        }
        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged().conflate()

    private fun ConnectivityManager.isOnline(): Boolean {
        val network = activeNetwork ?: return false
        return getNetworkCapabilities(network)?.isOnline() ?: false
    }

    private fun NetworkCapabilities.isOnline(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

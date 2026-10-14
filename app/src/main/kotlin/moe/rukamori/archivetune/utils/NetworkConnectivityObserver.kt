/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * Simple NetworkConnectivityObserver based on OuterTune's implementation
 * Provides network connectivity monitoring for auto-play functionality
 */
class NetworkConnectivityObserver(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val isRegistered = AtomicBoolean(false)

    private val _networkStatus = Channel<Boolean>(Channel.CONFLATED)
    val networkStatus = _networkStatus.receiveAsFlow()

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _networkStatus.trySend(true)
            }

            override fun onLost(network: Network) {
                _networkStatus.trySend(isCurrentlyConnected())
            }
        }

    init {
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered.set(true)
        } catch (e: Exception) {
            // Fallback: assume connected if registration fails
            _networkStatus.trySend(true)
        }

        // Send initial state
        val isInitiallyConnected = isCurrentlyConnected()
        _networkStatus.trySend(isInitiallyConnected)
    }

    fun unregister() {
        if (isRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: IllegalArgumentException) {
                Timber.tag("NetworkConnectivityObserver").w(e, "Callback was not registered")
            }
        }
        _networkStatus.close()
    }

    /**
     * Check current connectivity state synchronously
     */
    fun isCurrentlyConnected(): Boolean =
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            // Check if we have internet capability
            val hasInternet = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            // For API 23+, also check if connection is validated
            val isValidated =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                } else {
                    true // For older versions, assume validated if we have internet capability
                }

            hasInternet && isValidated
        } catch (e: Exception) {
            false
        }
}

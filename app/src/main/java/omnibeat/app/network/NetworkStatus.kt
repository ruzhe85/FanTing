package omnibeat.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkStatus {
    /**
     * 只看“有没有可用网络”，不再要求 NET_CAPABILITY_VALIDATED：
     * 部分 ROM、VPN 与内网 DNS 下 VALIDATED 会长期为 false，但实际请求是通的，
     * 之前会因此静默跳过所有封面加载。
     */
    fun isOnline(context: Context): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

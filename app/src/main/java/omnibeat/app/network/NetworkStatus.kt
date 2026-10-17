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

    /**
     * 当前是否是 Wi-Fi 网络（播客「仅 Wi-Fi 下载」用）。
     * 拿不到网络 / capabilities 时返回 false——保守按移动数据处理，
     * 由调用方弹窗让用户决定，不偷流量。
     */
    fun isWifi(context: Context): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

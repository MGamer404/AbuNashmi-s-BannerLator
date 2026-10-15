package com.winlator.star.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.winlator.star.xenvironment.EnvironmentComponent
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * The device's network link, published for the Linux runtime's processes.
 *
 * Android denies an app the rtnetlink dump behind getifaddrs() and if_nameindex(), the hardware
 * address ioctl and every table under /proc/net. Wine builds its adapter, address, route and
 * neighbour tables from those, so a game under the runtime saw no adapter, no address, no gateway
 * and no MAC - and one that checks its adapters before going online waited for good. The link the
 * app can see through ConnectivityManager is written here, kept current while the session runs,
 * and the session shim answers whatever the kernel refuses from it (tools/linuxfs/preload/netif.c).
 *
 * Shape follows WinNative's LinuxNetworkLinkComponent (maxjivi05, f76a5c4a), GPL-3.0.
 */
class LinuxNetworkLinkComponent(
    context: Context,
    private val rootDir: File,
) : EnvironmentComponent() {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Called before the session starts, so its first process already sees the link. */
    fun publish() = write(connectivity.activeNetwork?.let(connectivity::getLinkProperties))

    override fun start() {
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = write(properties)
            override fun onLost(network: Network) = write(null)
        }
        synchronized(lock) { callback = registered }
        connectivity.registerDefaultNetworkCallback(registered)
    }

    override fun stop() {
        val registered = synchronized(lock) { callback.also { callback = null } } ?: return
        try {
            connectivity.unregisterNetworkCallback(registered)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was already gone", e)
        }
    }

    private fun write(properties: LinkProperties?) {
        val file = File(rootDir, LINK_FILE)
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                val staged = File(file.path + ".staged")
                staged.writeText(describe(properties))
                if (!staged.renameTo(file)) throw IOException("Could not replace $file")
            } catch (e: IOException) {
                Log.w(TAG, "Could not publish the network link", e)
            }
        }
    }

    private fun describe(properties: LinkProperties?): String {
        val addresses = properties?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address || it.address is Inet6Address }
        val name = properties?.interfaceName?.takeIf { addresses.isNotEmpty() } ?: OFFLINE_NAME
        val mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) properties?.mtu ?: 0 else 0
        return buildString {
            append("if $name $LINK_INDEX ${if (mtu > 0) mtu else DEFAULT_MTU}\n")
            append("mac ${seededMac()}\n")
            if (addresses.isEmpty()) {
                append("addr $OFFLINE_ADDRESS\n")
                return@buildString
            }
            for (address in addresses) {
                append("addr ${address.address.hostAddress?.substringBefore('%')} ${address.prefixLength}\n")
            }
            for (family in listOf(Inet4Address::class.java, Inet6Address::class.java)) {
                val gateway = properties?.routes.orEmpty()
                    .firstOrNull { it.isDefaultRoute && family.isInstance(it.gateway) && !it.gateway!!.isAnyLocalAddress }
                    ?.gateway ?: continue
                append("gw ${gateway.hostAddress?.substringBefore('%')}\n")
            }
        }
    }

    /**
     * A stable, locally administered address for this device. Android hides the real one, and a
     * title should see the same hardware identity every session.
     */
    private fun seededMac(): String {
        val id = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "bannerlator"
        val bytes = java.security.MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        val b0 = (bytes[0].toInt() and 0xfc) or 0x02
        return listOf(b0, bytes[1].toInt(), bytes[2].toInt(), bytes[3].toInt(), bytes[4].toInt(), bytes[5].toInt())
            .joinToString(":") { "%02x".format(it and 0xff) }
    }

    companion object {
        private const val TAG = "LinuxNetworkLink"
        private const val LINK_FILE = "etc/bannerlator-net"
        private const val LINK_INDEX = 2
        private const val DEFAULT_MTU = 1500
        private const val OFFLINE_NAME = "eth0"
        private const val OFFLINE_ADDRESS = "10.0.0.2 24"
    }
}

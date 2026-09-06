package be.mygod.vpnhotspot.control

import android.app.Service
import android.content.Intent
import android.net.TetheringManager
import android.net.wifi.SoftApConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.RoutingManager
import be.mygod.vpnhotspot.ServiceNotification
import be.mygod.vpnhotspot.TetheringService
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.net.TetherStates
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat.Companion.toCompat
import be.mygod.vpnhotspot.net.wifi.WifiApManager
import be.mygod.vpnhotspot.net.wifi.WifiSsidCompat
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.WifiApCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shell-facing control plane for headless deployments: adb/root drives this service via
 * `am start-foreground-service` without touching the GUI. The service is deliberately not
 * exported; shell (uid 2000) and root can always start non-exported components. Results are
 * written to the file log (logcat level filters would otherwise hide root-side failures, which
 * makes headless debugging impractical).
 *
 * Actions:
 *   be.mygod.vpnhotspot.control.STATUS      — tethering/system AP state into the file log
 *   be.mygod.vpnhotspot.control.SETUP       — set AP config, start Wi-Fi tethering, manage wlan0
 *   be.mygod.vpnhotspot.control.TEAR_DOWN   — stop managing wlan0 and stop Wi-Fi tethering
 *   be.mygod.vpnhotspot.control.CLEAN       — global routing cleanup (see [RoutingManager.clean])
 *
 * SETUP extras (all optional; omitted fields keep the system AP configuration):
 *   ssid (String), password (String), security (String: open/wpa2/wpa3), hidden (Boolean)
 */
class ControlService : Service(), CoroutineScope {
    companion object {
        const val ACTION_STATUS = "be.mygod.vpnhotspot.control.STATUS"
        const val ACTION_SETUP = "be.mygod.vpnhotspot.control.SETUP"
        const val ACTION_TEAR_DOWN = "be.mygod.vpnhotspot.control.TEAR_DOWN"
        const val ACTION_CLEAN = "be.mygod.vpnhotspot.control.CLEAN"

        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SECURITY = "security"
        const val EXTRA_HIDDEN = "hidden"

        private const val LOG_MAX_BYTES = 2L shl 20

        val logFile: File by lazy { File(app.filesDir, "control.log") }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    override val coroutineContext = Dispatchers.IO + Job()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceNotification.startForeground(this)
        val job = launch {
            try {
                when (intent?.action) {
                    ACTION_STATUS -> handleStatus()
                    ACTION_SETUP -> handleSetup(intent)
                    ACTION_TEAR_DOWN -> handleTearDown(intent)
                    ACTION_CLEAN -> RoutingManager.clean().join()
                    else -> Timber.w("ControlService: unknown action %s", intent?.action)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
        // TetheringService exits when its last downstream stops, so give the shell a settled view
        // (and any follow-up am call a live process) before dropping the foreground notification.
        mainHandler.postDelayed({ if (!job.isActive) stopSelf(startId) }, 4000)
        return START_NOT_STICKY
    }

    private suspend fun handleStatus() {
        val sb = StringBuilder("== control status ")
        sb.append(timeFormat.format(Date())).appendLine(" ==")
        sb.appendLine("sdk: ${android.os.Build.VERSION.SDK_INT}")
        val states = withContext(Dispatchers.IO) {
            withTimeoutOrNull(3000) { TetherStates.flow.first() }
        }
        if (states == null) {
            sb.appendLine("tether states: timeout (tethering daemon busy?)")
        } else {
            sb.appendLine("tethered: [${states.tethered.joinToString()}]")
            sb.appendLine("available: [${states.available.joinToString()}]")
            sb.appendLine("localOnly: [${states.localOnly.joinToString()}]")
            if (states.errored.isNotEmpty()) sb.appendLine("errored: ${states.errored}")
        }
        try {
            val compat = WifiApManager.configuration.toCompat()
            sb.appendLine("ap ssid: ${compat.ssid}")
            sb.appendLine("ap securityType: ${compat.securityType}")
            sb.appendLine("ap hidden: ${compat.isHiddenSsid}")
            sb.appendLine("ap autoShutdown: ${compat.isAutoShutdownEnabled}")
            sb.appendLine("ap shutdownTimeoutMillis: ${compat.shutdownTimeoutMillis}")
        } catch (e: Exception) {
            sb.appendLine("ap config: error $e")
        }
        try {
            val wlanAddr = java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.name == "wlan0" }.flatMap { it.interfaceAddresses.asSequence() }
                .filter { it.address is java.net.Inet4Address }
                .joinToString { it.address.hostAddress ?: "" }
            sb.appendLine("wlan0: $wlanAddr")
        } catch (e: Exception) {
            sb.appendLine("wlan0: error $e")
        }
        writeStatus(sb.toString())
    }

    private suspend fun handleSetup(intent: Intent) {
        val ssid = intent.getStringExtra(EXTRA_SSID)
        if (ssid != null) {
            val password = intent.getStringExtra(EXTRA_PASSWORD)
            val security = intent.getStringExtra(EXTRA_SECURITY)
            val securityType = when (security) {
                "open" -> SoftApConfiguration.SECURITY_TYPE_OPEN
                "wpa3" -> SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                else -> SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
            }
            try {
                val compat = SoftApConfigurationCompat(
                    ssid = WifiSsidCompat.fromUtf8Text(ssid),
                    passphrase = password?.takeIf { securityType != SoftApConfiguration.SECURITY_TYPE_OPEN },
                    securityType = securityType,
                    isHiddenSsid = intent.getBooleanExtra(EXTRA_HIDDEN, false),
                    isAutoShutdownEnabled = false,
                )
                val platform = compat.toPlatform()
                try {
                    check(WifiApManager.setConfiguration(platform)) { "framework rejected AP config" }
                } catch (e: InvocationTargetException) {
                    if (RootManager.use { it.execute(WifiApCommands.SetConfiguration(platform)) }.value) {
                        Timber.i("ControlService: AP config applied via root")
                    } else throw IllegalStateException("root rejected AP config")
                } catch (eCancel: CancellationException) {
                    throw eCancel
                } catch (e: Exception) {
                    if (RootManager.use { it.execute(WifiApCommands.SetConfiguration(platform)) }.value) {
                        Timber.i("ControlService: AP config applied via root fallback")
                    } else throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "ControlService: AP configuration failed (ssid=%s)", ssid)
            }
        }
        try {
            TetheringManagerCompat.startTethering(TetheringManager.TETHERING_WIFI, false)
            Timber.i("ControlService: startTethering(WIFI) ok")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "ControlService: startTethering failed")
        }
        startService(Intent(this, TetheringService::class.java).apply {
            putExtra(TetheringService.EXTRA_ADD_INTERFACES, arrayOf("wlan0"))
            putExtra(TetheringService.EXTRA_ADD_INTERFACES_MONITOR, arrayListOf("wlan0"))
        })
        Timber.i("ControlService: TetheringService started for wlan0")
    }

    private fun handleTearDown(intent: Intent) {
        startService(Intent(this, TetheringService::class.java).apply {
            putExtra(TetheringService.EXTRA_REMOVE_INTERFACE, "wlan0")
        })
        launch {
            try {
                TetheringManagerCompat.stopTethering(TetheringManager.TETHERING_WIFI, this@ControlService)
                Timber.i("ControlService: stopTethering(WIFI) ok")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "ControlService: stopTethering failed")
            }
        }
    }

    private suspend fun writeStatus(text: String) = withContext(Dispatchers.IO) {
        Timber.i("ControlService status written")
        logFile.parentFile?.mkdirs()
        if (logFile.isFile && logFile.length() > LOG_MAX_BYTES) {
            val keep = java.io.FileInputStream(logFile).use { it.readBytes() }
            // drop the (likely partial) first line, then start over
            val start = keep.indexOf('\n'.code.toByte()) + 1
            logFile.writeBytes(if (start in 1..keep.size) keep.copyOfRange(start, keep.size) else keep)
        }
        logFile.appendText(text + "\n")
    }
}

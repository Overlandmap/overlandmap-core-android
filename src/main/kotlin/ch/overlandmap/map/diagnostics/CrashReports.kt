package ch.overlandmap.map.diagnostics

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import ch.overlandmap.map.R
import java.io.File
import java.io.PrintWriter
import java.util.ArrayDeque
import java.util.Date

/** Local-only diagnostics for release devices without a debugger or Google services. */
object CrashReports {
    private val events = ArrayDeque<String>()
    private var offered = false
    private var installed = false

    @Synchronized fun note(event: String) {
        if (events.size >= 30) events.removeFirst()
        events.addLast("${Date()} $event")
    }

    @Synchronized private fun history(): String = events.joinToString("\n")

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val report = File(context.filesDir, "diagnostics/last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                report.parentFile?.mkdirs()
                report.printWriter().use { out -> write(context, out, thread, error) }
            } catch (_: Throwable) {
                // Reporting must never replace the original crash handling.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun write(context: Context, out: PrintWriter, thread: Thread, error: Throwable) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        out.println("OverlandMap crash: ${Date()}")
        out.println("App: ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}) ${context.packageName}")
        out.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}")
        out.println("OS: ${Build.DISPLAY}; ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        out.println("Thread: ${thread.name}; memory max=${Runtime.getRuntime().maxMemory()}")
        out.println("Recent operations (search text and locations are not recorded):\n${history()}\n")
        error.printStackTrace(out)
    }

    /** Offer a user-initiated share on the next launch, retaining the report until discarded. */
    fun offerPreviousCrash(activity: Activity) {
        val report = File(activity.filesDir, "diagnostics/last-crash.txt")
        if (offered || !report.isFile || activity.isFinishing || activity.isDestroyed) return
        offered = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.crash_report_title)
            .setMessage(R.string.crash_report_message)
            .setPositiveButton(R.string.crash_report_share) { _, _ ->
                try {
                    val copy = File(activity.cacheDir, "shared/overlandmap-crash.txt")
                    copy.parentFile?.mkdirs()
                    report.copyTo(copy, overwrite = true)
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", copy)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.crash_report_share)))
                } catch (e: Exception) {
                    android.util.Log.e("CrashReports", "Cannot share crash report", e)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.crash_report_discard) { _, _ -> report.delete() }
            .show()
    }
}

package ch.overlandmap.map.ui

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.local.AppDatabase

/**
 * A composable gate that checks the on-disk database version before allowing
 * the app to proceed. If the existing database is too old to migrate cleanly,
 * a modal dialog asks the user for permission to wipe and recreate it.
 *
 * - Version 0 (no database file): fresh install, proceed directly.
 * - Version >= [AppDatabase.MIN_COMPATIBLE_VERSION]: compatible, proceed.
 * - Version < [AppDatabase.MIN_COMPATIBLE_VERSION] and > 0: needs reset.
 */
@Composable
fun DatabaseUpgradeGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val diskVersion = remember { AppDatabase.onDiskVersion(context) }

    // No file or compatible version: proceed immediately.
    val needsReset = diskVersion in 1 until AppDatabase.MIN_COMPATIBLE_VERSION
    var resetDone by remember { mutableStateOf(!needsReset) }

    if (resetDone) {
        content()
    } else {
        AlertDialog(
            onDismissRequest = { /* non-dismissable */ },
            title = { Text(stringResource(R.string.db_upgrade_title)) },
            text = { Text(stringResource(R.string.db_upgrade_message)) },
            confirmButton = {
                Button(onClick = {
                    AppDatabase.deleteDatabase(context)
                    // Clear the saved route — the object it points to no longer
                    // exists after the wipe, and restoring it would blank-screen.
                    val app = context.applicationContext as OverlandApp
                    app.userPreferences.clearLastRoute()
                    resetDone = true
                }) {
                    Text(stringResource(R.string.db_upgrade_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // User declined: quit the app.
                    (context as? Activity)?.finishAffinity()
                }) {
                    Text(stringResource(R.string.db_upgrade_exit))
                }
            },
        )
    }
}

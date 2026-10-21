package ch.overlandmap.map.ui.social

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import ch.overlandmap.map.R
import com.google.firebase.auth.FirebaseAuth

/** Opens the app's sign-in screen; provided by the navigation host (default: nothing). */
val LocalOpenSignIn = staticCompositionLocalOf<() -> Unit> { {} }

/** Whether a real account (not the automatic anonymous session) is signed in. */
fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser?.isAnonymous == false

/** Runs an action only for a signed-in account; otherwise explains why and offers to sign in. */
class AuthGate internal constructor(private val show: (Int) -> Unit) {
    fun require(@StringRes message: Int, action: () -> Unit) {
        if (isSignedIn()) action() else show(message)
    }
}

/**
 * The sign-in prompt of the social features (iOS "Sign in first" alerts). Call [AuthGate.require]
 * with the message for the action; the dialog is emitted by this composable.
 */
@Composable
fun rememberAuthGate(): AuthGate {
    val openSignIn = LocalOpenSignIn.current
    var message by remember { mutableStateOf<Int?>(null) }
    message?.let { res ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.sign_in_first)) },
            text = { Text(stringResource(res)) },
            confirmButton = {
                TextButton(onClick = { message = null; openSignIn() }) { Text(stringResource(R.string.sign_in)) }
            },
            dismissButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return remember { AuthGate { message = it } }
}

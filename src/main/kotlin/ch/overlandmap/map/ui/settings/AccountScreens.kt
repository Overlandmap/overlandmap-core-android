package ch.overlandmap.map.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.data.AccountRules
import ch.overlandmap.map.ui.overlandApp
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

/**
 * Sign-in / sign-up screen (iOS `SettingsAuthSheet`): email + password, with a name when
 * registering, a "forgot password" action, and Google Sign-In. Registering upgrades the anonymous
 * session in place and tells the user to verify their email. Pops back once a real account is
 * signed in and any notice has been read.
 */
@Composable
fun SignInScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val user by viewModel.user.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val showVerifyNotice by viewModel.showVerifyNotice.collectAsState()
    val context = LocalContext.current
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var showForgot by remember { mutableStateOf(false) }

    LaunchedEffect(user, showVerifyNotice) {
        if (user != null && !showVerifyNotice) onBack()
    }
    LaunchedEffect(Unit) { viewModel.clearMessage() }

    SettingsSubScreen(
        title = stringResource(if (signUp) R.string.sign_up_title else R.string.sign_in),
        onBack = onBack,
    ) {
        Text(
            stringResource(if (signUp) R.string.sign_up_text else R.string.sign_in_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (signUp) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                isError = attempted && name.isBlank(),
                supportingText = if (attempted && name.isBlank()) {
                    { Text(stringResource(R.string.name_required)) }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        val emailInvalid = attempted && !AccountRules.isValidEmail(email)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.email)) },
            singleLine = true,
            isError = emailInvalid,
            supportingText = if (emailInvalid) {
                { Text(stringResource(R.string.email_is_invalid)) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            isError = attempted && !AccountRules.isValidPassword(password),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = {
                attempted = true
                if (AccountRules.canSubmit(email, password, name, signUp)) {
                    if (signUp) viewModel.signUp(name, email, password) else viewModel.signIn(email, password)
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (signUp) R.string.sign_up else R.string.sign_in))
            }
        }
        TextButton(
            onClick = { signUp = !signUp; attempted = false; viewModel.clearMessage() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (signUp) R.string.sign_in else R.string.sign_up))
        }
        if (!signUp) {
            TextButton(onClick = { showForgot = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.forgot_password_que))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.or_divider),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = { viewModel.signInWithGoogle(context) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sign_in_google))
        }

        MessageText(message, viewModel.resetSentTo.collectAsState().value)
    }

    if (showForgot) {
        ForgotPasswordDialog(
            initialEmail = email,
            onDismiss = { showForgot = false },
            onSend = { address ->
                showForgot = false
                viewModel.forgotPassword(address)
            },
        )
    }

    if (showVerifyNotice) {
        AlertDialog(
            onDismissRequest = { viewModel.showVerifyNotice.value = false },
            title = { Text(stringResource(R.string.verify_email)) },
            text = { Text(stringResource(R.string.verify_email_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.showVerifyNotice.value = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun ForgotPasswordDialog(initialEmail: String, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var address by remember { mutableStateOf(initialEmail) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forgot_password)) },
        text = {
            Column {
                Text(stringResource(R.string.forgot_password_text))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(address) }, enabled = AccountRules.isValidEmail(address)) {
                Text(stringResource(R.string.forgot_password))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** An info or error line under a form. [resetSentTo] completes the "reset email sent to" note. */
@Composable
internal fun MessageText(message: SettingsViewModel.Message?, resetSentTo: String? = null) {
    message ?: return
    val text = when {
        message.res == R.string.reset_password_sent_to && resetSentTo != null ->
            stringResource(message.res) + " " + resetSentTo
        message.res != null -> stringResource(message.res)
        else -> message.text.orEmpty()
    }
    Text(
        text,
        color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * Profile (iOS `ProfileDetailsSheet`): editable name and avatar, the email with its verification
 * state, "restore purchases", "delete account", and the validated purchases with their pack names.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val context = LocalContext.current
    val authUser by viewModel.authUser.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val purchases by viewModel.purchases.collectAsState()
    val packNames by viewModel.packNames.collectAsState()
    val emailVerified by viewModel.emailVerified.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val signedIn = authUser?.isAnonymous == false

    var name by remember(profile?.displayName, authUser?.displayName) {
        mutableStateOf(profile?.displayName ?: authUser?.displayName.orEmpty())
    }
    var avatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var avatarPreview by remember { mutableStateOf<Bitmap?>(null) }
    var removeAvatar by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            decodeAvatar(context, uri)?.let { (bytes, preview) ->
                avatarBytes = bytes
                avatarPreview = preview
                removeAvatar = false
            }
        }
    }

    // Reading the profile screen is a good moment to pick up a verified email.
    LaunchedEffect(Unit) {
        viewModel.clearMessage()
        viewModel.refreshAccount()
    }

    SettingsSubScreen(title = stringResource(R.string.profile), onBack = onBack) {
        if (!signedIn) {
            Text(stringResource(R.string.not_signed_in))
            Button(onClick = onOpenSignIn, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.sign_in))
            }
            return@SettingsSubScreen
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AvatarImage(
                preview = avatarPreview,
                url = if (removeAvatar) null else (profile?.photoUrl ?: authUser?.photoUrl?.toString()),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.edit_profile)) }
                val hasAvatar = avatarPreview != null || profile?.photoUrl != null || authUser?.photoUrl != null
                if (hasAvatar && !removeAvatar) {
                    TextButton(onClick = {
                        removeAvatar = true
                        avatarBytes = null
                        avatarPreview = null
                    }) { Text(stringResource(R.string.remove)) }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.restore_purchases)) },
                        onClick = { menuOpen = false; confirmRestore = true },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        val email = profile?.email ?: authUser?.email
        email?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(if (emailVerified) R.string.email_verified else R.string.email_not_verified),
                style = MaterialTheme.typography.bodySmall,
                color = if (emailVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!emailVerified) {
                TextButton(onClick = viewModel::resendVerification) {
                    Text(stringResource(R.string.verify_email_again))
                }
            }
        }

        val changed = name.trim() != (profile?.displayName ?: authUser?.displayName.orEmpty()) ||
            avatarBytes != null || removeAvatar
        Button(
            onClick = { viewModel.saveProfile(name, avatarBytes, removeAvatar) },
            enabled = !busy && name.isNotBlank() && changed,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.save_profile))
        }
        MessageText(message)

        // Purchases: packs counted and named; pro / admin / beta labelled, pro last.
        val packPurchases = purchases.filter { !it.isPro && !it.isAdmin && !it.isBeta }
        Text(
            stringResource(R.string.purchases) + " (${packPurchases.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        if (purchases.isEmpty()) {
            Text(stringResource(R.string.no_purchases), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        purchases.sortedWith(compareBy({ it.isPro }, { packNames[it.documentId] ?: it.documentId })).forEach { purchase ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                Icon(
                    if (purchase.isPro) Icons.Filled.WorkspacePremium else Icons.Filled.Map,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(
                        when {
                            purchase.isPro -> stringResource(R.string.pro_entitlement)
                            purchase.isAdmin -> stringResource(R.string.admin)
                            purchase.isBeta -> stringResource(R.string.beta)
                            else -> packNames[purchase.documentId] ?: purchase.documentId
                        },
                    )
                    val detail = when {
                        purchase.isPro && purchase.validityEnd != null ->
                            if (purchase.isActive) {
                                stringResource(R.string.valid_until, dateFormat.format(Date(purchase.validityEnd)))
                            } else {
                                stringResource(R.string.expired)
                            }
                        else -> purchase.purchasedAt?.let { dateFormat.format(Date(it)) }
                    }
                    detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (purchase.isPro && !purchase.isActive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (purchase.isActive) Text("✓", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.restore_purchases)) },
            text = { Text(stringResource(R.string.restore_purchases_confirmation)) },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; viewModel.restorePurchases() }) {
                    Text(stringResource(R.string.restore_purchases))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_account)) },
            text = { Text(stringResource(R.string.delete_account_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAccount(onDone = onBack)
                }) { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AvatarImage(preview: Bitmap?, url: String?) {
    val modifier = Modifier.size(72.dp).clip(CircleShape)
    when {
        preview != null -> Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        url != null -> AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        else -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/** The picked image as a 512 px (long edge) JPEG at 85 % quality, plus a bitmap for the preview. */
private fun decodeAvatar(context: Context, uri: Uri): Pair<ByteArray, Bitmap>? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val scale = AVATAR_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else bitmap
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    out.toByteArray() to scaled
}.getOrNull()

private const val AVATAR_PX = 512

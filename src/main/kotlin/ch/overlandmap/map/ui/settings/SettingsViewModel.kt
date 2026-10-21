package ch.overlandmap.map.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.FontSize
import ch.overlandmap.map.units.GpsFormat
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.AppUser
import ch.overlandmap.map.model.UserPurchase
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: OverlandApp) : ViewModel() {

    private val auth = app.authRepository
    private val preferences = app.userPreferences

    /** The signed-in account, or null — the automatic anonymous session doesn't count. */
    val user: StateFlow<FirebaseUser?> = auth.userFlow
        .map { it?.takeUnless(FirebaseUser::isAnonymous) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            auth.currentUser?.takeUnless(FirebaseUser::isAnonymous),
        )

    /** The user's Firestore profile document, live; null when anonymous. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: StateFlow<AppUser?> = auth.userFlow
        .flatMapLatest { app.shopRepository.userDocFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The user's validated purchases, live from `users/{uid}/purchases`. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val purchases: StateFlow<List<UserPurchase>> = auth.userFlow
        .flatMapLatest { app.shopRepository.purchasesFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val useMiles = preferences.useMiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val useFeet = preferences.useFeet
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val gpsFormat = preferences.gpsFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GpsFormat.DD)
    val useFahrenheit = preferences.useFahrenheit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val fontSize = preferences.fontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontSize.MEDIUM)
    val mapLanguage = preferences.mapLanguage
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserPreferences.MAP_LANGUAGE_NATIVE,
        )

    /** Entitlements of the signed-in account (admin / beta / pro), live; null while loading. */
    val entitlements = app.entitlements

    /** Whether the signed-in account's email address is verified; re-read after [AuthRepository.reloadUser]. */
    val emailVerified: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(auth.userFlow, auth.reloads) { u, _ ->
        u?.isEmailVerified == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), auth.currentUser?.isEmailVerified == true)

    /** The account's Auth profile as it is now (name, photo), refreshed with [AuthRepository.reloads]. */
    val authUser: StateFlow<FirebaseUser?> = kotlinx.coroutines.flow.combine(auth.userFlow, auth.reloads) { u, _ -> u }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), auth.currentUser)

    /** Display names of the purchased packs, by pack id (local copy first, then the online pack). */
    val packNames: StateFlow<Map<String, String>> = purchases.map { list ->
        val lang = java.util.Locale.getDefault().language
        list.filter { !it.isPro && !it.isAdmin && !it.isBeta }.mapNotNull { p ->
            val pack = runCatching { app.libraryRepository.trackPack(p.documentId) }.getOrNull()
                ?: runCatching { app.shopRepository.trackPack(p.documentId) }.getOrNull()
            pack?.let { p.documentId to it.name(lang) }
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** A one-shot note for the screen: a string resource (info) or an error text. */
    data class Message(@StringRes val res: Int? = null, val text: String? = null, val isError: Boolean = false)

    val message = MutableStateFlow<Message?>(null)
    val busy = MutableStateFlow(false)

    /** Set after a sign-up, to show the "verify your email" notice. */
    val showVerifyNotice = MutableStateFlow(false)

    fun clearMessage() {
        message.value = null
    }

    private fun fail(e: Throwable) {
        message.value = Message(text = e.localizedMessage ?: e.javaClass.simpleName, isError = true)
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy.value = false
            }
        }
    }

    fun signInWithGoogle(activityContext: Context) = launchBusy {
        try {
            auth.signInWithGoogle(activityContext)
            message.value = null
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            // The user closed the account picker: nothing to report.
        }
    }

    fun signIn(email: String, password: String) = launchBusy {
        auth.signInWithEmail(email.trim(), password)
        message.value = null
    }

    /** Registers an account (linking the anonymous session) and completes its user document. */
    fun signUp(name: String, email: String, password: String) = launchBusy {
        val created = auth.register(name, email.trim(), password)
        val uid = auth.currentUser?.uid
        if (created && uid != null) {
            runCatching { app.accountRepository.initializeNewUserAccount(uid, name.trim()) }
            showVerifyNotice.value = true
        }
        message.value = null
    }

    fun forgotPassword(email: String) = launchBusy {
        auth.sendPasswordReset(email)
        message.value = Message(text = null, res = R.string.reset_password_sent_to)
        resetSentTo.value = email.trim()
    }

    /** The address a reset email was just sent to. */
    val resetSentTo = MutableStateFlow<String?>(null)

    fun resendVerification() = launchBusy {
        auth.resendVerificationEmail()
        message.value = Message(res = R.string.email_link_sent)
    }

    fun refreshAccount() {
        viewModelScope.launch { runCatching { auth.reloadUser() } }
    }

    fun saveProfile(name: String, avatarJpeg: ByteArray?, removeAvatar: Boolean) = launchBusy {
        app.accountRepository.updateProfile(name, avatarJpeg, removeAvatar)
        message.value = null
    }

    fun restorePurchases() = launchBusy {
        val count = app.billingManager.restorePurchases()
        message.value = if (count == 0) Message(res = R.string.no_purchases)
        else Message(res = R.string.purchase_success)
    }

    /**
     * Permanently deletes the account and continues anonymously. Firebase asks for a recent
     * sign-in first; the user is told to sign in again.
     */
    fun deleteAccount(onDone: () -> Unit) = launchBusy {
        try {
            auth.deleteAccount()
            onDone()
        } catch (e: com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
            message.value = Message(res = R.string.sign_in_again_for_delete, isError = true)
        }
    }

    /**
     * Drops the purchased packs from the device (they can be re-downloaded after signing back in;
     * free samples stay), stops running downloads, then signs out. The screen warns the user first.
     */
    fun signOut(onDone: () -> Unit = {}) = launchBusy {
        app.packDownloadManager.cancelAll()
        app.libraryRepository.deletePurchasedPacks()
        auth.signOut()
        onDone()
    }

    fun setLanguage(code: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
    }

    fun setUseMiles(value: Boolean) {
        viewModelScope.launch { preferences.setUseMiles(value) }
    }

    fun setUseFeet(value: Boolean) {
        viewModelScope.launch { preferences.setUseFeet(value) }
    }

    fun setGpsFormat(format: GpsFormat) {
        viewModelScope.launch { preferences.setGpsFormat(format) }
    }

    fun setUseFahrenheit(value: Boolean) {
        viewModelScope.launch { preferences.setUseFahrenheit(value) }
    }

    fun setFontSize(value: FontSize) {
        viewModelScope.launch { preferences.setFontSize(value) }
    }

    fun setMapLanguage(code: String) {
        viewModelScope.launch { preferences.setMapLanguage(code) }
    }
}

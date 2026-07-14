package ch.overlandmap.map.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
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
    val mapLanguage = preferences.mapLanguage
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserPreferences.MAP_LANGUAGE_NATIVE,
        )

    val error = MutableStateFlow<String?>(null)

    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            try {
                auth.signInWithGoogle(activityContext)
                error.value = null
            } catch (e: Exception) {
                error.value = e.localizedMessage
            }
        }
    }

    fun signInWithEmail(email: String, password: String, createAccount: Boolean) {
        viewModelScope.launch {
            try {
                if (createAccount) auth.createAccount(email, password)
                else auth.signInWithEmail(email, password)
                error.value = null
            } catch (e: Exception) {
                error.value = e.localizedMessage
            }
        }
    }

    /**
     * Drops the purchased packs from the device (they can be re-downloaded
     * after signing back in; free samples stay), then signs out. The screen
     * warns the user before calling this.
     */
    fun signOut() {
        viewModelScope.launch {
            app.libraryRepository.deletePurchasedPacks()
            auth.signOut()
        }
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

    fun setMapLanguage(code: String) {
        viewModelScope.launch { preferences.setMapLanguage(code) }
    }
}

package ch.overlandmap.map.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Firebase Authentication. Firestore requires an authenticated user for every
 * request, so the app always has one: either an account the user signed into
 * (cached by Firebase across runs) or an anonymous session created at startup
 * by [ensureSignedIn]. Signing in with a real account is optional everywhere
 * except for purchasing a track pack.
 */
class AuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {

    val currentUser: FirebaseUser? get() = auth.currentUser

    /**
     * Guarantees a Firebase user exists. If no user was cached from a previous
     * run, signs in anonymously; retries until it succeeds (first launch may
     * be offline). Called once from the Application.
     */
    suspend fun ensureSignedIn() {
        while (auth.currentUser == null) {
            try {
                auth.signInAnonymously().await()
            } catch (e: Exception) {
                Log.w("Auth", "Anonymous sign-in failed, retrying", e)
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /** Emits the signed-in user (or null) on every auth state change. */
    val userFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /**
     * Suspends until a user exists. Firestore requests must wait on this, so
     * none goes out before the startup (anonymous) sign-in has completed.
     */
    suspend fun awaitUser(): FirebaseUser =
        auth.currentUser ?: userFlow.filterNotNull().first()

    suspend fun signInWithEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    /**
     * Creates the account (iOS `FirebaseAuthService.register`): an anonymous session is upgraded
     * in place by linking the email credential, so what it already owns carries over. When the
     * email is already registered the link fails and the user is signed in instead. A new account
     * gets its display name and a verification email. Returns true for a new account.
     */
    suspend fun register(name: String, email: String, password: String): Boolean {
        val current = auth.currentUser
        try {
            if (current != null && current.isAnonymous) {
                current.linkWithCredential(EmailAuthProvider.getCredential(email, password)).await()
            } else {
                auth.createUserWithEmailAndPassword(email, password).await()
            }
        } catch (e: FirebaseAuthUserCollisionException) {
            auth.signInWithEmailAndPassword(email, password).await()
            return false
        }
        val user = auth.currentUser ?: return true
        user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
        runCatching { user.sendEmailVerification().await() }
        return true
    }

    /** Kept for the current settings screen; replaced by [register] once the sign-up form has a name field. */
    suspend fun createAccount(email: String, password: String) {
        register(email.substringBefore('@'), email, password)
    }

    suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    /** Sends the verification email again ("Send new verification email"). */
    suspend fun resendVerificationEmail() {
        auth.currentUser?.sendEmailVerification()?.await()
    }

    /**
     * Reloads the account from Firebase (picks up a verified email) and announces it on
     * [reloads], since the auth-state listener doesn't fire for it.
     */
    suspend fun reloadUser() {
        val user = auth.currentUser ?: return
        if (user.isAnonymous) return
        user.reload().await()
        reloads.value += 1
    }

    /** Bumped each time the account was reloaded, so observers re-read `isEmailVerified`. */
    val reloads = kotlinx.coroutines.flow.MutableStateFlow(0)

    suspend fun updateDisplayName(name: String) {
        val user = auth.currentUser ?: return
        user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
        reloads.value += 1
    }

    /**
     * Permanently deletes the account, then falls back to a new anonymous session. Firebase
     * refuses with `FirebaseAuthRecentLoginRequiredException` when the sign-in is too old; the
     * caller tells the user to sign in again.
     */
    suspend fun deleteAccount() {
        auth.currentUser?.delete()?.await()
        ensureSignedIn()
    }

    /**
     * Google Sign-In through the Credential Manager. [context] must be an
     * Activity context so the account picker can be shown.
     */
    suspend fun signInWithGoogle(context: Context) {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId(context))
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(context).getCredential(context, request)
        val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        auth.signInWithCredential(firebaseCredential).await()
    }

    /** Signs out of the account and falls back to a new anonymous session. */
    suspend fun signOut() {
        auth.signOut()
        ensureSignedIn()
    }

    /** The OAuth web client ID that google-services.json generated as a resource. */
    private fun webClientId(context: Context): String {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return context.getString(id)
    }

    private companion object {
        const val RETRY_DELAY_MS = 5_000L
    }
}

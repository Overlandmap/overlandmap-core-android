package ch.overlandmap.map.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
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

    suspend fun createAccount(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).await()
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

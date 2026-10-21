package ch.overlandmap.map.ui.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.tracking.VoiceCommand
import java.util.Locale

/** Where the voice waypoint dialogue is (iOS `VoiceWaypointCoordinator`). */
internal enum class VoicePhase { IDLE, LISTENING_LABEL, SPEAKING, LISTENING_CONFIRM }

/**
 * Drives the dialogue: listen for a label (the recording stops after [SILENCE_MS] without new
 * words), read it back, then listen for "OK" (save), "cancel" (dismiss) or "change" (start over).
 * Must be used on the main thread, as `SpeechRecognizer` requires.
 */
internal class VoiceWaypointController(
    private val context: Context,
    private val locale: Locale,
    private val onSave: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val fallbackLabel: String,
) {
    var phase by mutableStateOf(VoicePhase.IDLE); private set
    var label by mutableStateOf("")
    var unavailable by mutableStateOf(false); private set

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var retries = 0
    private var destroyed = false
    private val silenceTimeout = Runnable { if (phase == VoicePhase.LISTENING_LABEL) finishLabel() }

    fun start() {
        if (destroyed) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            unavailable = true
            return
        }
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = locale
            }.also {
                it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { if (phase == VoicePhase.SPEAKING) listenForConfirmation() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { handler.post { if (phase == VoicePhase.SPEAKING) listenForConfirmation() } }
                })
            }
        }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        retries = 0
        listenForLabel()
    }

    /** "Speak again": discards the label and starts over. */
    fun restart() {
        tts?.stop()
        recognizer?.cancel()
        handler.removeCallbacks(silenceTimeout)
        label = ""
        retries = 0
        listenForLabel()
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        phase = VoicePhase.IDLE
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.startListening(intent)
    }

    private fun listenForLabel() {
        phase = VoicePhase.LISTENING_LABEL
        listen()
    }

    private fun listenForConfirmation() {
        if (destroyed) return
        phase = VoicePhase.LISTENING_CONFIRM
        listen()
    }

    /** The label is complete: read it back, then wait for the confirmation. */
    private fun finishLabel() {
        handler.removeCallbacks(silenceTimeout)
        recognizer?.cancel()
        val spoken = label.trim().ifEmpty { fallbackLabel }
        phase = VoicePhase.SPEAKING
        if (ttsReady) {
            tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "voice-waypoint")
        } else {
            listenForConfirmation()
        }
    }

    private fun handleResult(texts: List<String>, final: Boolean) {
        val best = texts.firstOrNull().orEmpty()
        when (phase) {
            VoicePhase.LISTENING_LABEL -> {
                if (best.isNotEmpty()) {
                    label = best.replaceFirstChar { it.uppercase(locale) }
                    retries = 0
                    handler.removeCallbacks(silenceTimeout)
                    if (final) finishLabel() else handler.postDelayed(silenceTimeout, SILENCE_MS)
                } else if (final) {
                    retryOrStop()
                }
            }
            VoicePhase.LISTENING_CONFIRM -> {
                if (!final) {
                    when (VoiceCommand.classify(best)) {
                        VoiceCommand.NONE -> {}
                        else -> resolveConfirmation(best)
                    }
                } else {
                    resolveConfirmation(texts.joinToString(" "))
                }
            }
            else -> {}
        }
    }

    private fun resolveConfirmation(text: String) {
        when (VoiceCommand.classify(text)) {
            VoiceCommand.OK -> { recognizer?.cancel(); onSave(label.trim()) }
            VoiceCommand.CANCEL -> { recognizer?.cancel(); onCancel() }
            VoiceCommand.CHANGE -> restart()
            VoiceCommand.NONE -> retryOrStop()
        }
    }

    /** Listens again after silence or an unrecognised reply, up to a few times. */
    private fun retryOrStop() {
        if (destroyed) return
        if (++retries > MAX_RETRIES) {
            phase = VoicePhase.IDLE
            return
        }
        handler.postDelayed({ if (!destroyed && phase != VoicePhase.IDLE) listen() }, 250)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            handleResult(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(), final = false)
        }
        override fun onResults(results: Bundle?) {
            handleResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(), final = true)
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> { phase = VoicePhase.IDLE; unavailable = true }
                // A label already heard before the silence timer fired: carry on with it.
                else -> if (phase == VoicePhase.LISTENING_LABEL && label.isNotBlank()) finishLabel() else retryOrStop()
            }
        }
    }

    private companion object {
        const val SILENCE_MS = 1400L
        const val MAX_RETRIES = 4
    }
}

/** The "add a waypoint by voice" sheet (iOS `VoiceWaypointSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceWaypointSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val fallback = stringResource(R.string.waypoint)
    val controller = remember {
        VoiceWaypointController(
            context = context,
            locale = Locale.getDefault(),
            onSave = { text -> app.trackRecorder.addWaypoint(text); onDismiss() },
            onCancel = onDismiss,
            fallbackLabel = fallback,
        )
    }
    var micDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start() else micDenied = true
    }
    LaunchedEffect(Unit) {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) controller.start() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { controller.destroy() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.add_waypoint), style = MaterialTheme.typography.titleLarge)
            val status = when {
                micDenied -> stringResource(R.string.voice_microphone_needed)
                controller.unavailable -> stringResource(R.string.voice_unavailable)
                controller.phase == VoicePhase.LISTENING_LABEL -> stringResource(R.string.listening)
                controller.phase == VoicePhase.SPEAKING -> stringResource(R.string.speaking)
                controller.phase == VoicePhase.LISTENING_CONFIRM -> stringResource(R.string.say_ok_to_confirm)
                else -> ""
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (micDenied || controller.unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = controller.label,
                onValueChange = { controller.label = it },
                label = { Text(stringResource(R.string.waypoint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        if (micDenied) launcher.launch(Manifest.permission.RECORD_AUDIO).also { micDenied = false }
                        else controller.restart()
                    },
                    enabled = !controller.unavailable,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.speak_again)) }
                Button(
                    onClick = {
                        app.trackRecorder.addWaypoint(controller.label.trim())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.save_waypoint)) }
            }
            Row(Modifier.padding(bottom = 12.dp)) {}
        }
    }
}

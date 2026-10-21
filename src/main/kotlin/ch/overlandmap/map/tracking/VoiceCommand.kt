package ch.overlandmap.map.tracking

/** What a spoken reply means while a voice waypoint waits for confirmation (iOS `VoiceWaypointCoordinator`). */
enum class VoiceCommand {
    NONE, OK, CANCEL, CHANGE;

    companion object {
        private val CANCEL_WORDS = listOf("cancel", "annuler", "abbrechen", "cancelar", "annulla", "annullare", "annuleren", "отмена", "отменить")
        private val CHANGE_WORDS = listOf(
            "change", "changer", "ändern", "aendern", "cambiar", "cambia", "cambiare", "modifica",
            "wijzigen", "veranderen", "mudar", "alterar", "изменить", "поменять",
        )
        private val OK_WORDS = listOf("ok", "okay", "yes", "oui", "ja", "si", "sim", "да")

        /**
         * Cancel wins over change, change over OK (as on iOS). Cancel, change and OK in English also
         * match inside a longer word ("okay" contains "ok"); the other languages must be whole words.
         */
        fun classify(recognized: String): VoiceCommand {
            val text = recognized.lowercase()
            val tokens = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            fun has(words: List<String>) = words.any { it in tokens }
            return when {
                "cancel" in text || "annuler" in text || "abbrechen" in text || "cancelar" in text || has(CANCEL_WORDS) -> CANCEL
                "change" in text || "changer" in text || has(CHANGE_WORDS) -> CHANGE
                "ok" in text || "okay" in text || has(OK_WORDS) -> OK
                else -> NONE
            }
        }
    }
}

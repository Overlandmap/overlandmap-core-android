package ch.overlandmap.map.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import ch.overlandmap.map.OverlandApp
import java.util.Locale

/** The app container, for ViewModel initializers: `viewModel { XViewModel(overlandApp()) }`. */
fun CreationExtras.overlandApp(): OverlandApp = this[APPLICATION_KEY] as OverlandApp

/** Language code used to resolve translated Firestore texts ("en", "fr", …). */
fun currentLanguage(): String = Locale.getDefault().language.ifEmpty { "en" }

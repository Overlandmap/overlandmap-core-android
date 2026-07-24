package ch.overlandmap.map.ui.world

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.model.BorderOpenState
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.overlandApp
import ch.overlandmap.map.ui.theme.contentTextStyle

/**
 * Overland map tab: world map on top; the bottom half shows the info of the
 * tapped country, border or border post (or a country search when nothing is
 * selected). Free feature, works offline and signed out.
 */
@Composable
fun WorldMapScreen(
    viewModel: WorldViewModel = viewModel { WorldViewModel(overlandApp()) },
) {
    val borders by viewModel.borders.collectAsState()
    val borderPosts by viewModel.borderPosts.collectAsState()
    val countries by viewModel.countries.collectAsState()
    val selection by viewModel.selection.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        WorldMap(
            borders = borders,
            borderPosts = borderPosts,
            onBorderTapped = viewModel::selectBorderId,
            onBorderPostTapped = viewModel::selectBorderPostId,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = selection) {
                null -> CountrySearch(countries) { viewModel.select(WorldSelection.OfCountry(it)) }
                else -> SelectionInfo(
                    selection = current,
                    onClose = { viewModel.select(null) },
                    onOpenCountry = viewModel::selectCountryCode,
                )
            }
        }
    }
}

@Composable
private fun CountrySearch(countries: List<Country>, onPick: (Country) -> Unit) {
    var query by remember { mutableStateOf("") }
    val lang = currentLanguage()
    val matches = if (query.length < 2) emptyList()
    else countries.filter { it.name(lang).contains(query, ignoreCase = true) }

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_country)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        LazyColumn {
            items(matches.size) { index ->
                val country = matches[index]
                TextButton(onClick = { onPick(country) }) {
                    Text(
                        country.name(lang),
                        style = contentTextStyle(MaterialTheme.typography.labelLarge),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionInfo(
    selection: WorldSelection,
    onClose: () -> Unit,
    onOpenCountry: (String) -> Unit,
) {
    val lang = currentLanguage()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (selection) {
                    is WorldSelection.OfCountry -> selection.country.name(lang)
                    is WorldSelection.OfBorder -> selection.border.name
                    is WorldSelection.OfBorderPost -> selection.post.name
                },
                style = contentTextStyle(MaterialTheme.typography.titleMedium),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
        when (selection) {
            is WorldSelection.OfCountry -> CountryInfo(selection.country, lang)
            is WorldSelection.OfBorder -> {
                OpenStateLabel(selection.border.openState)
                selection.border.comment(lang)?.let {
                    Text(it, style = contentTextStyle(MaterialTheme.typography.bodyLarge))
                }
                Row {
                    selection.border.country1?.let { code ->
                        TextButton(onClick = { onOpenCountry(code) }) { Text(code) }
                    }
                    selection.border.country2?.let { code ->
                        TextButton(onClick = { onOpenCountry(code) }) { Text(code) }
                    }
                }
            }
            is WorldSelection.OfBorderPost -> {
                OpenStateLabel(selection.post.openState)
                selection.post.countries?.let {
                    Text(it, style = contentTextStyle(MaterialTheme.typography.bodySmall))
                }
                selection.post.comment(lang)?.let {
                    Text(it, style = contentTextStyle(MaterialTheme.typography.bodyLarge))
                }
            }
        }
    }
}

@Composable
private fun CountryInfo(country: Country, lang: String) {
    InfoRow(stringResource(R.string.country_capital), country.capital(lang))
    InfoRow(stringResource(R.string.country_continent), country.continent)
    InfoRow(stringResource(R.string.country_currency), country.currency)
    InfoRow(stringResource(R.string.country_driving), country.driving)
    InfoRow(stringResource(R.string.country_overlanding), country.overlandingStatus.name)
    InfoRow(stringResource(R.string.country_visa), country.visaStatus.name)
    InfoRow(stringResource(R.string.country_carnet), country.carnetStatus.name)
    InfoRow(stringResource(R.string.country_insurance), country.insuranceStatus.name)
    country.comment(lang)?.let {
        Text(
            it,
            style = contentTextStyle(MaterialTheme.typography.bodyLarge),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value == null) return
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = contentTextStyle(MaterialTheme.typography.bodyMedium))
    }
}

@Composable
private fun OpenStateLabel(state: BorderOpenState) {
    val label = when (state) {
        BorderOpenState.OPEN -> stringResource(R.string.border_open)
        BorderOpenState.CLOSED -> stringResource(R.string.border_closed)
        BorderOpenState.BILATERAL -> stringResource(R.string.border_bilateral)
        BorderOpenState.RESTRICTIONS -> stringResource(R.string.border_restrictions)
        BorderOpenState.UNKNOWN -> stringResource(R.string.border_unknown)
    }
    Text(label, style = MaterialTheme.typography.labelLarge)
}

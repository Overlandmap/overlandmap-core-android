package ch.overlandmap.map.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.billing.PurchaseError

/**
 * The note riding above a purchase button: the last purchase error, or that Play is still waiting
 * for the payment. Renders nothing when there is neither.
 */
@Composable
fun PurchaseStatus(error: PurchaseError?, pending: Boolean, modifier: Modifier = Modifier) {
    val text = when {
        error is PurchaseError.Message -> error.text
        error is PurchaseError.VerifyEmail -> stringResource(R.string.verify_email_to_purchase)
        error is PurchaseError.ProductUnavailable -> stringResource(R.string.purchase_unavailable)
        error is PurchaseError.NotSettled -> stringResource(R.string.purchase_failed)
        pending -> stringResource(R.string.processing_purchase)
        else -> return
    }
    val isError = error != null
    Surface(shape = MaterialTheme.shapes.small, tonalElevation = 3.dp, modifier = modifier) {
        Text(
            text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

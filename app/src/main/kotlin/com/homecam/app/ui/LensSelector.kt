package com.homecam.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.homecam.app.ui.kit.Segmented
import com.homecam.app.ui.kit.SwitchRow
import com.homecam.core.lens.LensSelections
import com.homecam.proto.LensInfo

/** The partner lens a proven pair can run beside [LensSelector]'s primary. */
data class SecondLens(val partnerId: String, val on: Boolean, val onChange: (Boolean) -> Unit)

/** Offers only the lenses the camera advertises; with one there is nothing to choose. */
@Composable
fun LensSelector(
    lenses: List<LensInfo>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    secondLens: SecondLens? = null,
) {
    if (lenses.size < 2 && secondLens == null) return
    Column(modifier) {
        if (lenses.size >= 2) {
            Segmented(lenses.map { it.id to LensSelections.labelOf(it.id) }, selected, onSelect)
        }
        secondLens?.let { partner ->
            SwitchRow("Second lens", partner.on, partner.onChange, meta = LensSelections.labelOf(partner.partnerId))
        }
    }
}

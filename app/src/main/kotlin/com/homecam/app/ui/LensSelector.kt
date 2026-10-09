package com.homecam.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.homecam.app.ui.kit.Segmented
import com.homecam.core.lens.LensSelections
import com.homecam.proto.LensInfo

/** Offers only the lenses the camera advertises; with one there is nothing to choose. */
@Composable
fun LensSelector(
    lenses: List<LensInfo>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size < 2) return
    Segmented(lenses.map { it.id to LensSelections.labelOf(it.id) }, selected, onSelect, modifier)
}

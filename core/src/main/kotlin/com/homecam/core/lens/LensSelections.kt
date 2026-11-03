package com.homecam.core.lens

import com.homecam.proto.Lens
import com.homecam.proto.LensPair
import com.homecam.proto.LensSelection
import com.homecam.proto.SettingsSnapshot

object LensSelections {

    fun primaryOf(settings: SettingsSnapshot): String = settings.lens.primary.ifEmpty { LensCatalog.DEFAULT }

    /** The lens the older, facing-only control means. */
    fun idFor(facing: Lens.Facing): String = if (facing == Lens.Facing.FRONT) LensCatalog.FRONT else LensCatalog.BACK_WIDE

    fun labelOf(id: String): String = when (id) {
        LensCatalog.BACK_WIDE -> "Wide"
        LensCatalog.BACK_ULTRAWIDE -> "Ultra-wide"
        LensCatalog.FRONT -> "Front"
        else -> id
    }

    /** Why [selection] cannot be taken, or null if it can. An empty [pairs] refuses every secondary. */
    fun rejection(selection: LensSelection, advertised: Collection<String>, pairs: Collection<LensPair>): String? {
        if (selection.primary !in advertised) return "this camera has no lens named '${selection.primary}'"
        if (selection.secondaryCount > 1) return "this camera can record at most one other lens"
        if (selection.secondaryCount == 0) return null
        val secondary = selection.getSecondary(0)
        return when {
            secondary == selection.primary -> "this camera cannot record a lens alongside itself"
            secondary !in advertised -> "this camera has no lens named '$secondary'"
            pairs.none { samePair(it, selection.primary, secondary) } ->
                "this camera has not shown it can record '${selection.primary}' and '$secondary' together"
            else -> null
        }
    }

    private fun samePair(pair: LensPair, x: String, y: String): Boolean =
        (pair.a == x && pair.b == y) || (pair.a == y && pair.b == x)
}

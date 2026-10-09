package com.homecam.core.lens

import com.homecam.proto.Lens
import com.homecam.proto.LensSelection
import com.homecam.proto.SettingsSnapshot

object LensSelections {

    fun primaryOf(settings: SettingsSnapshot): String = settings.lens.primary.ifEmpty { LensCatalog.DEFAULT }

    /** The lens the older, facing-only control means. */
    fun idFor(facing: Lens.Facing): String = if (facing == Lens.Facing.FRONT) LensCatalog.FRONT else LensCatalog.BACK_WIDE

    /** Why [selection] cannot be taken on a camera that opens [advertised], or null if it can. */
    fun rejection(selection: LensSelection, advertised: Collection<String>): String? = when {
        selection.primary !in advertised -> "this camera has no lens named '${selection.primary}'"
        selection.secondaryCount > 0 -> "this camera does not capture from more than one lens"
        else -> null
    }
}

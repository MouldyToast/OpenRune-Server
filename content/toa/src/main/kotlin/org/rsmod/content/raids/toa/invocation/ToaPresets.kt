package org.rsmod.content.raids.toa.invocation

import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.game.entity.Player

/**
 * Per-player invocation preset storage: 5 slots x 3 persistent varps
 * (`toa_invocations_preset_1a` .. `5c`, varps 3680-3694), plus the preset-slot selection
 * varbit `toa_preset_selected` (14541; 0 = none, 1..5 = slot + 1).
 *
 * Port of the preset methods of NR/Zenyte `TOAManager.java`
 * (`com.zenyte.game.content.tombsofamascut`): `saveInvocationPreset`,
 * `clearInvocationPreset`, `getInvocationPreset`, `isPresetEmpty`, `getPresetBaseVarId`.
 *
 * As in NR, presets are per-player (they load into the *party's* shared settings via the
 * lobby UI) and an all-zero triple means "empty slot". Only the bitmaps are stored —
 * `kcRequirement` is not part of a preset.
 */
internal object ToaPresets {

    const val SLOT_COUNT: Int = 5

    /** Selected preset slot on the party-management screen: 0 = none, 1..5 = slot + 1. */
    const val SELECTED_SLOT_VARBIT: String = "varbit.toa_preset_selected"

    /** Slot `s` uses `PRESET_VARPS[s * 3]` .. `[s * 3 + 2]` (NR `3680 + index * 3` + 0..2). */
    private val PRESET_VARPS: Array<String> =
        arrayOf(
            "varp.toa_invocations_preset_1a",
            "varp.toa_invocations_preset_1b",
            "varp.toa_invocations_preset_1c",
            "varp.toa_invocations_preset_2a",
            "varp.toa_invocations_preset_2b",
            "varp.toa_invocations_preset_2c",
            "varp.toa_invocations_preset_3a",
            "varp.toa_invocations_preset_3b",
            "varp.toa_invocations_preset_3c",
            "varp.toa_invocations_preset_4a",
            "varp.toa_invocations_preset_4b",
            "varp.toa_invocations_preset_4c",
            "varp.toa_invocations_preset_5a",
            "varp.toa_invocations_preset_5b",
            "varp.toa_invocations_preset_5c",
        )

    /** Writes [settings]' bitmaps into preset [slot] (0-based, 0..4). */
    fun save(player: Player, slot: Int, settings: ToaPartySettings) {
        writeSlot(player, slot, settings.encode())
    }

    /**
     * Reads preset [slot] (0-based), or `null` when the slot is empty (all three varps zero —
     * NR `isPresetEmpty`).
     */
    fun load(player: Player, slot: Int): ToaPartySettings? {
        val bitmaps = readSlot(player, slot)
        if (bitmaps.all { it == 0 }) {
            return null
        }
        return ToaPartySettings.decode(bitmaps)
    }

    /** Zeroes preset [slot] (NR `clearInvocationPreset`). */
    fun clear(player: Player, slot: Int) {
        writeSlot(player, slot, IntArray(ToaPartySettings.BITMAP_SIZE))
    }

    fun isEmpty(player: Player, slot: Int): Boolean = readSlot(player, slot).all { it == 0 }

    /** Currently selected preset slot (0-based), or `-1` when none is selected. */
    fun selectedSlot(player: Player): Int = player.vars[SELECTED_SLOT_VARBIT] - 1

    /** Selects preset [slot] (0-based), or clears the selection when [slot] is `-1`. */
    fun setSelectedSlot(player: Player, slot: Int) {
        if (slot != -1) {
            requireValidSlot(slot)
        }
        VarPlayerIntMapSetter.set(player, SELECTED_SLOT_VARBIT, slot + 1)
    }

    private fun readSlot(player: Player, slot: Int): IntArray {
        requireValidSlot(slot)
        val base = slot * ToaPartySettings.BITMAP_SIZE
        // NR-BUG-FIX: NR's `getInvocationPreset` read `{base, base+1, base+1}` — the third
        // varp (`*c`) was never loaded, corrupting presets containing bit indices > 61.
        return IntArray(ToaPartySettings.BITMAP_SIZE) { i -> player.vars[PRESET_VARPS[base + i]] }
    }

    private fun writeSlot(player: Player, slot: Int, bitmaps: IntArray) {
        requireValidSlot(slot)
        val base = slot * ToaPartySettings.BITMAP_SIZE
        for (i in 0 until ToaPartySettings.BITMAP_SIZE) {
            VarPlayerIntMapSetter.set(player, PRESET_VARPS[base + i], bitmaps[i])
        }
    }

    private fun requireValidSlot(slot: Int) {
        require(slot in 0 until SLOT_COUNT) { "Preset slot must be within 0..4, got $slot." }
    }
}

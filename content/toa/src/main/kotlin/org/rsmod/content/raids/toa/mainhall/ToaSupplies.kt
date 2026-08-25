package org.rsmod.content.raids.toa.mainhall

import kotlin.math.max
import org.rsmod.content.raids.toa.invocation.ToaInvocation
import org.rsmod.content.raids.toa.invocation.ToaPartySettings

/**
 * The helpful spirit's three claimable supply bundles (Life / Chaos / Power) and the invocation
 * scaling that shrinks them.
 *
 * Port of NR/Zenyte `MainHallEncounter.setSupplies()` + `addSupplyItem()`
 * (`com.zenyte.game.content.tombsofamascut.encounter`): NR filled three shared `Container`s
 * (TOA_SUPPLY_LIFE / CHAOS / POWER); the claim UI itself was not in the provided NR sources, so
 * this port models each container as a claimable bundle handed to the claiming player — the
 * live-game behavior (each member claims ONE of the three named bundles from the spirit).
 *
 * All quantity math is NR's verbatim, including the float-truncation quirks:
 * - `max(1, ...)` floors only where NR applied it — silk dressing, tears (chaos) and salts
 *   (chaos) have NO floor and legitimately drop to zero at low help factors.
 * - The chaos bundle only receives its liquid adrenaline when fewer than two item stacks made
 *   it in (NR `container.getSize() < 2`).
 * - ON_A_DIET swaps the life bundle's silk dressings for extra ambrosia/scarabs.
 */
internal object ToaSupplies {

    /** One item stack inside a bundle. */
    data class Item(val obj: String, val count: Int)

    /** NR help-factor: NEED_SOME_HELP .67, NEED_LESS_HELP .34, NO_HELP_NEEDED .1, else 1. */
    private fun helpFactor(settings: ToaPartySettings): Float =
        when {
            settings.isActive(ToaInvocation.NEED_SOME_HELP) -> 0.67f
            settings.isActive(ToaInvocation.NEED_LESS_HELP) -> 0.34f
            settings.isActive(ToaInvocation.NO_HELP_NEEDED) -> 0.1f
            else -> 1f
        }

    /** Builds the three bundles for [settings] (NR `setSupplies`, container order preserved). */
    fun bundles(settings: ToaPartySettings): List<ToaSupplyBundle> {
        val factor = helpFactor(settings)
        val onDiet = settings.isActive(ToaInvocation.ON_A_DIET)

        val life = buildList {
            addItem(OBJ_NECTAR_4, max(1, (5 * factor).toInt()))
            addItem(OBJ_TEARS_4, max(1, (5 * factor).toInt()))
            addItem(OBJ_AMBROSIA_2, max(1, ((if (onDiet) 3 else 2) * factor).toInt()))
            addItem(OBJ_SCARAB_2, max(1, ((if (onDiet) 5 else 3) * factor).toInt()))
            if (!onDiet) {
                // NR-PARITY: no max(1, ...) floor — 0 at the NO_HELP_NEEDED factor.
                addItem(OBJ_SILK_DRESSING_2, (3 * factor).toInt())
            }
        }

        val chaos = buildList {
            addItem(OBJ_NECTAR_4, max(1, (8 * factor).toInt()))
            // NR-PARITY: tears and salts have no floor here.
            addItem(OBJ_TEARS_4, (6 * factor).toInt())
            addItem(OBJ_SALTS_2, (2 * factor).toInt())
            if (size < 2) {
                addItem(OBJ_ADRENALINE_2, 1)
            }
        }

        val power = buildList {
            addItem(OBJ_SALTS_2, max(1, (2 * factor).toInt()))
            addItem(OBJ_ADRENALINE_2, max(1, (2 * factor).toInt()))
        }

        return listOf(
            ToaSupplyBundle("Life", life),
            ToaSupplyBundle("Chaos", chaos),
            ToaSupplyBundle("Power", power),
        )
    }

    /** NR `addSupplyItem`: stacks with a non-positive amount are dropped. */
    private fun MutableList<Item>.addItem(obj: String, count: Int) {
        if (count > 0) {
            this += Item(obj, count)
        }
    }

    // Obj gamevals (osrs-dumps obj.sym ids in comments; NR used its own ItemId constants).

    /** 27315 — nectar (4). */
    private const val OBJ_NECTAR_4: String = "obj.toa_supply_heal_4"

    /** 27327 — tears of elidinis (4). */
    private const val OBJ_TEARS_4: String = "obj.toa_supply_prayer_4"

    /** 27347 — ambrosia (2). */
    private const val OBJ_AMBROSIA_2: String = "obj.toa_supply_panicheal_2"

    /** 27335 — blessed crystal scarab (2). */
    private const val OBJ_SCARAB_2: String = "obj.toa_supply_prayer_overtime_2"

    /** 27323 — silk dressing (2). */
    private const val OBJ_SILK_DRESSING_2: String = "obj.toa_supply_heal_overtime_2"

    /** 27343 — smelling salts (2). */
    private const val OBJ_SALTS_2: String = "obj.toa_supply_stats_2"

    /** 27339 — liquid adrenaline (2). */
    private const val OBJ_ADRENALINE_2: String = "obj.toa_supply_energy_2"
}

/**
 * A claimable supply bundle (one NR supply `Container`): the display [name] shown in the claim
 * menu and the item stacks granted on claim.
 */
internal data class ToaSupplyBundle(val name: String, val items: List<ToaSupplies.Item>)

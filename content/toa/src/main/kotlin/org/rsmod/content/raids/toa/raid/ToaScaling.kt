package org.rsmod.content.raids.toa.raid

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure scaling formulas for Tombs of Amascut, verbatim from NR/Zenyte.
 *
 * Port of the formulas in `TOANPC.java` (constructor + `setMaxHealth()` + `getMaxHit()`),
 * `TOARaidParty.getDamageMultiplier()`, `TOARaidArea.hit()`/`sendDeath()` and
 * `TOAManager.refreshHudStates()` (`com.zenyte.game.content.tombsofamascut[.npc|.raid]`).
 *
 * No callers in Session 1 — later sessions (room/NPC wiring, HUD, death) consume these.
 * All float math intentionally mirrors NR's Java `float` arithmetic; `floor` on
 * non-negative inputs matches NR's `(int) Math.floor(...)`.
 */
internal object ToaScaling {

    /** Damage multipliers are capped at 2.5x (NR `TOANPC` / `TOARaidParty`). */
    const val DAMAGE_FACTOR_CAP: Float = 2.5f

    /** `raidFactor = 1 + (raidLevel / 5) * 0.02` — +2% per 5 raid levels (NR `TOANPC` ctor). */
    fun raidFactor(raidLevel: Int): Float = 1f + ((raidLevel / 5f) * 0.02f)

    /**
     * Path/boss-level factor: `bossLevel > 0 ? 0.08 + (bossLevel - 1) * 0.05 : 0` — +8% at
     * level 1, +5% per level after (NR `TOANPC` ctor).
     */
    fun levelFactor(bossLevel: Int): Float =
        if (bossLevel > 0) 0.08f + ((bossLevel - 1) * 0.05f) else 0f

    /** `min(2.5, raidFactor + levelFactor)` (NR `TOANPC` ctor). */
    fun damageFactor(raidLevel: Int, bossLevel: Int): Float =
        min(DAMAGE_FACTOR_CAP, raidFactor(raidLevel) + levelFactor(bossLevel))

    /** NPC accuracy multiplier = raid factor, uncapped (NR `TOANPC` ctor). */
    fun accuracyFactor(raidLevel: Int): Float = raidFactor(raidLevel)

    /** Scaled NPC defence: `floor(baseDefence * raidFactor)` (NR `TOANPC` ctor). */
    fun scaledDefence(baseDefence: Int, raidLevel: Int): Int =
        floor(baseDefence * raidFactor(raidLevel)).toInt()

    /**
     * `1 + (startTeamSize - 1) * 0.9` — +90% HP per extra member (NR `TOANPC.setMaxHealth`).
     * `startTeamSize` is the roster size snapshotted at room start.
     */
    fun teamSizeFactor(startTeamSize: Int): Float = 1f + (startTeamSize - 1) * 0.9f

    /** `raidFactor * teamSizeFactor * (1 + levelFactor)` (NR `TOANPC.setMaxHealth`). */
    fun hpFactor(raidLevel: Int, bossLevel: Int, startTeamSize: Int): Float =
        raidFactor(raidLevel) * teamSizeFactor(startTeamSize) * (1f + levelFactor(bossLevel))

    /** Scaled NPC max hitpoints: `floor(baseHp * hpFactor)` (NR `TOANPC.setMaxHealth`). */
    fun scaledMaxHp(baseHp: Int, raidLevel: Int, bossLevel: Int, startTeamSize: Int): Int =
        floor(baseHp * hpFactor(raidLevel, bossLevel, startTeamSize)).toInt()

    /**
     * Displayed NPC combat level: `(int) (combatLevel * (1 + max(0, hpFactor - 1) / 4))`
     * (NR `TOANPC.setMaxHealth`).
     */
    fun scaledCombatLevel(baseCombatLevel: Int, hpFactor: Float): Int =
        (baseCombatLevel * (1f + max(0f, hpFactor - 1f) / 4f)).toInt()

    /** Scaled NPC max hit: `floor(base * damageFactor)` (NR `TOANPC.getMaxHit`). */
    fun scaledMaxHit(baseMaxHit: Int, damageFactor: Float): Int =
        floor(baseMaxHit * damageFactor).toInt()

    /**
     * Party-wide environmental-damage multiplier: `min(1 + raidLevel * 0.004, 2.5)`
     * (NR `TOARaidParty.getDamageMultiplier`).
     */
    fun partyDamageMultiplier(raidLevel: Int): Float = min(1f + raidLevel * 0.004f, DAMAGE_FACTOR_CAP)

    /**
     * Points awarded for a hit: `floor(min(damage, targetRemainingHp) * pointMultiplier)`;
     * a multiplier <= 0 yields no points (NR `TOARaidArea.hit`).
     */
    fun pointsForDamage(damage: Int, targetRemainingHp: Int, pointMultiplier: Float): Int =
        floor(min(damage, targetRemainingHp) * pointMultiplier).toInt()

    /**
     * Personal points remaining after a death:
     * `max(0, points - max(1000, floor(points * 0.2)))` (NR `TOARaidArea.sendDeath`).
     */
    fun pointsAfterDeath(points: Int): Int =
        max(0, points - max(1000, floor(points * 0.2f).toInt()))

    /**
     * HUD member HP bucket: `1 + min(28, floor(hp / maxHp * 28))` — 1..29; callers use 0 for
     * an empty slot, 30 for a ghost and 31 for a member in another room
     * (NR `TOAManager.refreshHudStates`).
     */
    fun hudHpBucket(hp: Int, maxHp: Int): Int =
        1 + min(28, floor(hp.toFloat() / maxHp * 28f).toInt())
}

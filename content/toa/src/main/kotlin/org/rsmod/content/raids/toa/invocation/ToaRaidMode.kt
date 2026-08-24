package org.rsmod.content.raids.toa.invocation

/**
 * Raid mode thresholds and the raid parameters derived from invocation choices.
 *
 * Port of NR/Zenyte `TOAPartySettings.getMode()` and the `timeLimitMinutes` /
 * `permittedTeamDeaths` derivation + over-time raid-level penalty from
 * `TOARaidParty.java` (both constructors and `setCompletion()`;
 * `com.zenyte.game.content.tombsofamascut[.raid]`).
 */
internal enum class ToaRaidMode(val displayName: String) {
    ENTRY("Entry"),
    NORMAL("Normal"),
    EXPERT("Expert");

    /** Killcount tracking key, e.g. `"tombs of amascut: entry mode"` (NR `TOARaidArea`). */
    val kcKey: String
        get() = "tombs of amascut: ${displayName.lowercase()} mode"

    companion object {
        /** Entry: 0-149, Normal: 150-299, Expert: 300+ (NR `TOAPartySettings.getMode`). */
        fun forRaidLevel(raidLevel: Int): ToaRaidMode =
            when {
                raidLevel >= 300 -> EXPERT
                raidLevel >= 150 -> NORMAL
                else -> ENTRY
            }
    }
}

internal fun ToaPartySettings.mode(): ToaRaidMode = ToaRaidMode.forRaidLevel(raidLevel())

/**
 * Overall raid time limit in minutes from the active TIME_LIMIT invocation, or `-1` for no
 * limit (NR `TOARaidParty` constructor).
 *
 * NR-PARITY: 40/35/30/25 minutes, matching NR (and OSRS).
 */
internal fun ToaPartySettings.timeLimitMinutes(): Int =
    when {
        isActive(ToaInvocation.WALK_FOR_IT) -> 40
        isActive(ToaInvocation.JOG_FOR_IT) -> 35
        isActive(ToaInvocation.RUN_FOR_IT) -> 30
        isActive(ToaInvocation.SPRINT_FOR_IT) -> 25
        else -> -1
    }

/**
 * Permitted team wipes from the active ATTEMPTS invocation, or `-1` for unlimited
 * (NR `TOARaidParty` constructor).
 */
internal fun ToaPartySettings.permittedTeamDeaths(): Int =
    when {
        isActive(ToaInvocation.TRY_AGAIN) -> 10
        isActive(ToaInvocation.PERSISTENCE) -> 5
        isActive(ToaInvocation.SOFTCORE_RUN) -> 3
        isActive(ToaInvocation.HARDCORE_RUN) -> 1
        else -> -1
    }

/**
 * Raid-level reduction applied to the completed raid level when the raid exceeds
 * [timeLimitMinutes] (`0` when no TIME_LIMIT invocation is active).
 *
 * NR-PARITY: NR subtracts 10/15/20/25 (`TOARaidParty.setCompletion`); OSRS uses
 * 20/30/40/50. Kept at NR's values per the Session 1 design brief.
 */
internal fun ToaPartySettings.overtimeRaidLevelPenalty(): Int =
    when {
        isActive(ToaInvocation.WALK_FOR_IT) -> 10
        isActive(ToaInvocation.JOG_FOR_IT) -> 15
        isActive(ToaInvocation.RUN_FOR_IT) -> 20
        isActive(ToaInvocation.SPRINT_FOR_IT) -> 25
        else -> 0
    }

package org.rsmod.content.raids.toa.raid

import org.rsmod.api.instances.InstanceId
import org.rsmod.api.instances.InstanceSession
import org.rsmod.content.raids.toa.invocation.ToaPartySettings
import org.rsmod.content.raids.toa.invocation.ToaRaidMode
import org.rsmod.content.raids.toa.invocation.permittedTeamDeaths
import org.rsmod.content.raids.toa.invocation.timeLimitMinutes
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid

/**
 * In-memory state for one active Tombs of Amascut raid (one per [InstanceSession] / party).
 *
 * Port of NR/Zenyte `TOARaidParty.java` (party-side raid state), the per-player raid fields of
 * `AbstractTOAManager.java`/`TOAManager.java` (points, damage, individual deaths, current
 * encounter) and `TOAPlayerLogoutState.java`
 * (`com.zenyte.game.content.tombsofamascut[.raid]`), restructured onto the OpenRune
 * architecture: raid state lives in this object (registered in `ToaRaidRegistry` keyed by
 * [instanceId]), never on the session or the player.
 *
 * Purely data + bookkeeping helpers — all orchestration (room transitions, timers, death and
 * wipe flows) is owned by the raid controller.
 *
 * @property instanceId The backing instance session's id.
 * @property session The backing instance session (one large region holding every room).
 * @property region The session's engine region, resolved once at raid creation via
 *   `InstanceManager.regionForId(session.id)` — used by `ToaLayout.roomCoord`.
 * @property settings Frozen invocation snapshot. The constructor deep-copies whatever is
 *   passed, so later lobby-side toggles can never mutate a running raid (NR-BUG-FIX: NR's
 *   "copy" aliased the leader's live bitmap array).
 */
internal class ToaRaid(
    val instanceId: InstanceId,
    val session: InstanceSession,
    val region: Region,
    settings: ToaPartySettings,
    members: List<ToaRaidMember>,
    leaderUuid: Long = members.first().uuid,
) {
    val settings: ToaPartySettings = settings.copy()

    /**
     * The raid's effective raid level: frozen at raid start (NR
     * `TOARaidParty.partySettings.getRaidLevel()`), then reduced by the over-time penalty at
     * completion — NR `setCompletion` wrote the cut back via `partySettings.setRaidLevel`, so
     * completion messaging and kc/reward wiring read the reduced value.
     */
    var raidLevel: Int = this.settings.raidLevel()

    /**
     * Entry / Normal / Expert, derived from the effective [raidLevel] — recomputed after the
     * over-time cut, as NR's `getMode()` read the written-back settings level.
     */
    val mode: ToaRaidMode
        get() = ToaRaidMode.forRaidLevel(raidLevel)

    /** Overall time limit in minutes, or -1 for none (NR `timeLimitMinutes`). */
    val timeLimitMinutes: Int = this.settings.timeLimitMinutes()

    /** Permitted team wipes, or -1 for unlimited (NR `permittedTeamDeaths`). */
    val permittedTeamDeaths: Int = this.settings.permittedTeamDeaths()

    /**
     * Roster of rejoin-entitled members (NR `originalPlayers`). Re-snapshotted from
     * [activeMembers] via [snapshotRoster] whenever a room resets/tears down (NR re-ran
     * `updateOriginalPlayers` in `TOARaidArea.destroyRegion`); a voluntary leaver is removed
     * (forfeits rejoin) while a logout keeps its entry (rejoin ticket).
     */
    val originalMembers: MutableList<ToaRaidMember> = members.toMutableList()

    /** Live members, in join order (HUD slot order; NR `players`). */
    val activeMembers: MutableList<ToaRaidMember> = members.toMutableList()

    /** Current leader; promoted to the first remaining member when the leader leaves. */
    var leaderUuid: Long = leaderUuid

    /** The room the PARTY is currently assigned to (NR `currentEncounterType`). */
    var currentRoom: ToaRoom = ToaRoom.MAIN_HALL

    /** Challenge stage of [currentRoom] (NR `TOARaidArea.stage`). */
    var stage: ToaRoomStage = ToaRoomStage.NOT_STARTED

    /** Path currently in progress, or null in the main hall pre-selection (NR `pathType`). */
    var currentPath: ToaPath? = null

    /** Paths already completed, in completion order (NR `pathsCompleted`). */
    val pathsCompleted: MutableList<ToaPath> = mutableListOf()

    /**
     * Path-level invocation levels, indexed by [ToaPath] ordinal (NR `bossLevels`; raised by
     * the main-hall obelisks, fed to NPC scaling and HUD path-level varbits — note the HUD
     * varbit DECLARATION order differs, see `ToaConstants.VARBIT_PATH_LEVELS`).
     */
    val pathLevels: IntArray = IntArray(ToaPath.entries.size)

    /** Team wipe count (NR `teamDeaths`). */
    var teamDeaths: Int = 0

    /** Individual death events, including mid-challenge logouts (NR `totalDeaths`). */
    var totalDeaths: Int = 0

    /**
     * Game tick the raid timer started at (first non-main-hall room entry), or 0 while the
     * timer has not started (NR `startTime` in world cycles).
     */
    var startTick: Int = 0

    /** Total raid ticks, set on completion; -1 while the raid is unfinished (NR `totalTime`). */
    var totalTimeTicks: Int = -1

    /** Game tick the current room's challenge started at (NR `TOARaidArea.challengeTime`). */
    var challengeStartTick: Int = 0

    /**
     * Roster size snapshotted when the current room's challenge starts — the NPC HP-scaling
     * team size (NR `TOARaidArea.teamSize`, set in `startRoom`). 0 before the first start.
     */
    var roomTeamSize: Int = 0

    /** Completed challenge results, in completion order (NR `challengeResults`). */
    val challengeResults: MutableList<ToaChallengeResult> = mutableListOf()

    /**
     * Raid level credited on completion; -1 until set. Reduced below [raidLevel] when the
     * time-limit challenge was failed (NR `completedRaidLevel` / `setCompletion`).
     */
    var completedRaidLevel: Int = -1

    /** `true` when the raid finished over its invocation time limit (NR `failedTimeChallenge`). */
    var failedTimeChallenge: Boolean = false

    /** `true` once the raid has failed (out of wipe attempts; NR `tombsFailure`). */
    var tombsFailure: Boolean = false

    /**
     * Per-player raid state keyed by player uuid. Entries are created for every starting
     * member and kept for the raid's lifetime (leavers included — points/deaths history).
     */
    val playerStates: MutableMap<Long, ToaPlayerState> =
        members.associateTo(HashMap()) { it.uuid to ToaPlayerState(it) }

    /** Sum of every member's current personal [ToaPlayerState.points]. */
    val totalPoints: Int
        get() = playerStates.values.sumOf { it.points }

    /** Personal-points contribution per player uuid (defensive snapshot). */
    val pointContributions: Map<Long, Int>
        get() = playerStates.mapValues { (_, state) -> state.points }

    /** `true` once the raid timer is running (NR `startTime > 0`). */
    val isTimerStarted: Boolean
        get() = startTick > 0

    /** `true` once the raid has completed (NR `getTotalTime() != -1`). */
    val isFinished: Boolean
        get() = totalTimeTicks != -1

    /** Elapsed raid ticks at [currentTick], or 0 while the timer has not started. */
    fun elapsedTicks(currentTick: Int): Int = if (isTimerStarted) currentTick - startTick else 0

    /** Sum of completed challenge durations (NR `computeTotalChallengeTime`). */
    fun totalChallengeTicks(): Int = challengeResults.sumOf { it.timeTicks }

    fun isLeader(uuid: Long): Boolean = uuid == leaderUuid

    fun playerState(uuid: Long): ToaPlayerState? = playerStates[uuid]

    fun memberByUuid(uuid: Long): ToaRaidMember? = activeMembers.firstOrNull { it.uuid == uuid }

    /** Rejoin authorization: is [uuid] still on the roster? (NR checked usernames.) */
    fun isOriginalMember(uuid: Long): Boolean = originalMembers.any { it.uuid == uuid }

    /**
     * Adds [member] back to the live list (idempotent) and ensures a player-state entry
     * exists (NR `TOARaidParty.add`).
     */
    fun addMember(member: ToaRaidMember) {
        if (activeMembers.none { it.uuid == member.uuid }) {
            activeMembers += member
        }
        playerStates.getOrPut(member.uuid) { ToaPlayerState(member) }
    }

    /**
     * Removes [uuid] from the live list; when [keepRosterEntry] is false (voluntary leave or
     * kick) the roster entry is dropped too, forfeiting rejoin — a logout passes true and
     * keeps its rejoin ticket (NR `TOARaidParty.leave` semantics). Promotes the first
     * remaining member when the leader leaves. Returns the removed member, or null.
     */
    fun removeMember(uuid: Long, keepRosterEntry: Boolean): ToaRaidMember? {
        val removed = activeMembers.firstOrNull { it.uuid == uuid } ?: return null
        activeMembers.remove(removed)
        if (!keepRosterEntry) {
            originalMembers.removeAll { it.uuid == uuid }
        }
        if (leaderUuid == uuid) {
            activeMembers.firstOrNull()?.let { leaderUuid = it.uuid }
        }
        return removed
    }

    /** Re-snapshots the roster from the live members (NR `updateOriginalPlayers`). */
    fun snapshotRoster() {
        originalMembers.clear()
        originalMembers += activeMembers
    }

    /** Adds [amount] personal points to [uuid]'s state (NR `TOAManager.setCurrentPoints`). */
    fun addPoints(uuid: Long, amount: Int) {
        val state = playerStates[uuid] ?: return
        state.points += amount
    }

    /** Raises a path's invocation level (NR `TOARaidParty.increaseBossLevel`). */
    fun increasePathLevel(path: ToaPath, levels: Int) {
        pathLevels[path.ordinal] += levels
    }

    /**
     * HUD name slots: 8 entries, live member names in join order padded with "" (NR
     * `generateHudPlayerList`).
     */
    fun hudNameSlots(): List<String> =
        List(ToaConstants.MAX_PARTY_MEMBERS) { activeMembers.getOrNull(it)?.name ?: "" }
}

/**
 * A raid member's stable identity — uuid for lookups, display name for HUD/broadcasts. Stored
 * instead of `Player` references so state survives logout/relog (NR stored usernames).
 */
internal data class ToaRaidMember(val uuid: Long, val name: String)

/**
 * Per-player raid state. Port of the raid-scoped fields of NR/Zenyte
 * `AbstractTOAManager.java`/`TOAManager.java` (points, damageDone/damageTaken,
 * individualDeaths, currentEncounter) — kept raid-side here rather than player-side.
 */
internal class ToaPlayerState(val member: ToaRaidMember) {
    /** Personal points this raid (NR `points`; death loss via `ToaScaling.pointsAfterDeath`). */
    var points: Int = 0

    /** Cumulative damage dealt to point-carrying NPCs (NR `damageDone`). */
    var damageDone: Int = 0

    /** Cumulative damage taken (NR `damageTaken`). */
    var damageTaken: Int = 0

    /** Individual deaths this raid (NR `individualDeaths`). */
    var deaths: Int = 0

    /** `true` while transformed into `npc.toa_player_ghost` (NR `isTransformedIntoNpc`). */
    var isGhost: Boolean = false

    /**
     * The room THIS player has entered — may lag behind `ToaRaid.currentRoom` ("straggler"
     * detection; NR `TOAManager.currentEncounter`). Null before first entry.
     */
    var currentRoom: ToaRoom? = null

    /** Snapshot taken on logout inside the raid; null once consumed or when not applicable. */
    var logoutState: ToaLogoutState? = null
}

/**
 * Logout-during-raid snapshot. Port of NR/Zenyte `TOAPlayerLogoutState.java` (which stored the
 * room via the `BaseEncounterType` mirror enum — dropped here, see [ToaRoom] NR-BUG-FIX note).
 *
 * @property room The room the player logged out in.
 * @property duringChallenge `true` when the room's challenge was STARTED and the player was
 *   inside the challenge area (a logout-death).
 * @property coords Instance coordinates at logout: the room's spawn tile for a logout-death,
 *   otherwise the player's logout position. NR used `logoutLocation` only for the rejoin AREA
 *   lookup — restore placement is ALWAYS the logout room's randomized spawn tile
 *   (`getRandomizedSpawnTile()` in `TOAManager.onLogin`), never these exact coords.
 * @property safeDeath `true` when team wipes were unlimited at logout (`permittedTeamDeaths ==
 *   -1`) — failing to rejoin then does NOT fail the raid for the player.
 */
internal data class ToaLogoutState(
    val room: ToaRoom,
    val duringChallenge: Boolean,
    val coords: CoordGrid,
    val safeDeath: Boolean,
)

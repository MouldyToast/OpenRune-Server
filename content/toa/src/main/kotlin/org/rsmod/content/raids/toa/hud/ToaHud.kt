package org.rsmod.content.raids.toa.hud

import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.content.raids.toa.raid.ToaPath
import org.rsmod.content.raids.toa.raid.ToaRaid
import org.rsmod.content.raids.toa.raid.ToaRaidMember
import org.rsmod.content.raids.toa.raid.ToaScaling
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

/**
 * The in-raid HUD overlay (interface 481 `toa_hud`): open/close, the member-name list, the
 * per-slot member status varbits, raid/path levels, personal points and the speedrun timer.
 *
 * Port of NR/Zenyte `TOAManager.java` — `sendHud()`, `refreshHudPlayers()` (clientscript 6585
 * `toa_hud_statusnames`), `refreshHudStates()` (varbits 14346-14354/14362-14369),
 * `sendRaidLevel()` (varbit 14380), `refreshPathLevel()` (varbits 14376-14379),
 * `refreshTimer()` (clientscript 6580 `toa_speedrun_time_update`) and `setCurrentPoints()` —
 * plus `TOARaidParty.getHudPlayerList()` (`com.zenyte.game.content.tombsofamascut[.raid]`).
 *
 * NR's custom points varbit 3586 is NOT ported: personal points transmit through the stock
 * varp `toa_personal_contribution` (3606) per the Session 1 binding decisions (rscm-map
 * mismatch #1). Clientscripts are sent fire-and-forget by raw id — a client missing them
 * ignores the calls, so everything here degrades gracefully.
 *
 * Kept as a plain object per the Session 1 shared contracts; the [EventBus]/[PlayerList]
 * dependencies (needed to open/close overlays and resolve member uuids) are provided once at
 * boot by [ToaHudScript]. Every function no-ops safely if called before initialization.
 */
internal object ToaHud {

    /** HUD member-slot value: member is in a different room than the party (NR value 31). */
    const val STATE_ABSENT: Int = 31

    /** HUD member-slot value: member is a dead ghost (NR value 30). */
    const val STATE_GHOST: Int = 30

    /** HUD member-slot value: slot empty / hidden (NR value 0). */
    const val STATE_EMPTY: Int = 0

    private var eventBus: EventBus? = null
    private var playerList: PlayerList? = null

    /** Boot-time wiring — called once from [ToaHudScript.startup]. */
    fun init(eventBus: EventBus, playerList: PlayerList) {
        this.eventBus = eventBus
        this.playerList = playerList
    }

    /** Opens the HUD overlay for [player] (NR `sendHud`: interface 481 at OVERLAY). */
    fun open(player: Player) {
        val bus = eventBus ?: return
        player.ifOpenOverlay(ToaConstants.IF_HUD, bus)
    }

    /** Closes the HUD overlay for [player] (NR area-`leave` overlay close). */
    fun close(player: Player) {
        val bus = eventBus ?: return
        player.ifCloseOverlay(ToaConstants.IF_HUD, bus)
    }

    /**
     * Full party refresh for every online member: name list (cs2 6585), member-state varbits,
     * own-slot varbit, sight markers, raid level, path levels and personal points.
     *
     * The controller calls this on every membership/death/room change; the instance join/leave
     * events (see [ToaHudScript]) call it as a safety net so rejoins and evictions stay in
     * sync (NR `refreshHudPlayers` + all-party `refreshHudStates`).
     */
    fun refreshParty(raid: ToaRaid) {
        val players = playerList ?: return
        val names = raid.hudNameSlots()
        for (member in raid.activeMembers.toList()) {
            val viewer = players.firstOrNull { it.uuid == member.uuid } ?: continue
            refreshFor(raid, viewer, member.uuid, names)
        }
    }

    /** [refreshParty] for a single online member (login-state restore on rejoin). */
    fun refreshMember(raid: ToaRaid, viewer: Player) {
        val uuid = viewer.uuid ?: return
        refreshFor(raid, viewer, uuid, raid.hudNameSlots())
    }

    /**
     * Sends [points] as [player]'s personal contribution (stock varp 3606
     * `toa_personal_contribution`; replaces NR `setCurrentPoints`'s custom varbit 3586).
     */
    fun setPoints(player: Player, points: Int) {
        VarPlayerIntMapSetter.set(player, ToaConstants.VARP_PERSONAL_POINTS, points)
    }

    /** Sends the frozen raid level to the HUD (varbit 14380; NR `sendRaidLevel`). */
    fun setRaidLevel(player: Player, raidLevel: Int) {
        VarPlayerIntMapSetter.set(player, ToaConstants.VARBIT_RAID_LEVEL, raidLevel)
    }

    /**
     * Sets the HUD current-path indicator (varbit 14381; NR `HUD_PATH_VARBIT`). `0` = main
     * hall; non-zero path values are sent by room content (later sessions).
     */
    fun setCurrentPath(player: Player, value: Int) {
        VarPlayerIntMapSetter.set(player, ToaConstants.VARBIT_CURRENT_PATH, value)
    }

    /**
     * Sends one path's invocation level (varbits 14376-14379; NR `refreshPathLevel`). Mapped
     * by name via [ToaConstants.pathLevelVarbit] — NOT by ordinal — to resolve the
     * declaration-order mismatch between [ToaPath] and the varbits (rscm-map mismatch #7).
     */
    fun setPathLevel(player: Player, path: ToaPath, level: Int) {
        VarPlayerIntMapSetter.set(player, ToaConstants.pathLevelVarbit(path), level)
    }

    /**
     * Speedrun timer update (NR `refreshTimer`): [elapsedTicks] with `finished = false` while
     * running, or the frozen total with `finished = true` once the raid completes.
     */
    fun timer(player: Player, elapsedTicks: Int, finished: Boolean) {
        // cs2: toa_speedrun_time_update — (elapsed ticks, finished flag 0|1).
        player.runClientScript(ToaConstants.CS2_TIMER_UPDATE, elapsedTicks, if (finished) 1 else 0)
    }

    private fun refreshFor(raid: ToaRaid, viewer: Player, viewerUuid: Long, names: List<String>) {
        // cs2: toa_hud_statusnames — 8 member-name strings, "" for empty slots.
        viewer.runClientScript(ToaConstants.CS2_HUD_STATUS_NAMES, names)
        for (slot in 0 until ToaConstants.MAX_PARTY_MEMBERS) {
            val member = raid.activeMembers.getOrNull(slot)
            val value =
                if (member == null || raid.isFinished) {
                    STATE_EMPTY
                } else {
                    memberStateValue(raid, member)
                }
            VarPlayerIntMapSetter.set(viewer, ToaConstants.VARBIT_HUD_PLAYER_SLOTS[slot], value)
            // NR: 1 while the member holds the "apmeken_sight_player" attribute — Apmeken room
            // content (later sessions); always cleared in Session 1.
            VarPlayerIntMapSetter.set(viewer, ToaConstants.VARBIT_HUD_SIGHT_SLOTS[slot], 0)
            if (member != null && member.uuid == viewerUuid) {
                VarPlayerIntMapSetter.set(viewer, ToaConstants.VARBIT_HUD_OWN_SLOT, slot + 1)
            }
        }
        setRaidLevel(viewer, raid.raidLevel)
        for (path in ToaPath.entries) {
            setPathLevel(viewer, path, raid.pathLevels[path.ordinal])
        }
        setPoints(viewer, raid.playerState(viewerUuid)?.points ?: 0)
    }

    /**
     * NR `refreshHudStates` slot value: 31 when the member is not in the party's current room,
     * 30 for a ghost, else the 1-29 HP bucket ([ToaScaling.hudHpBucket]); an unresolvable
     * (offline) member shows as absent.
     */
    private fun memberStateValue(raid: ToaRaid, member: ToaRaidMember): Int {
        val state = raid.playerState(member.uuid) ?: return STATE_EMPTY
        if (state.currentRoom != raid.currentRoom) {
            return STATE_ABSENT
        }
        if (state.isGhost) {
            return STATE_GHOST
        }
        val target = playerList?.firstOrNull { it.uuid == member.uuid } ?: return STATE_ABSENT
        return ToaScaling.hudHpBucket(target.hitpoints, target.baseHitpointsLvl)
    }
}

package org.rsmod.content.raids.toa.mainhall

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.content.raids.toa.hud.ToaHud
import org.rsmod.content.raids.toa.invocation.ToaInvocation
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.content.raids.toa.raid.ToaLayout
import org.rsmod.content.raids.toa.raid.ToaPath
import org.rsmod.content.raids.toa.raid.ToaRaid
import org.rsmod.content.raids.toa.raid.ToaRoom
import org.rsmod.content.raids.toa.raid.ToaRoomStage
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.loc.LocInfo
import org.rsmod.map.CoordGrid

/**
 * Main-hall (nexus) visit state: per-visit path-level increases, the helpful-spirit supply
 * drop, the entrance-door lock/complete states and the started-path bookkeeping.
 *
 * Port of NR/Zenyte `MainHallEncounter.java` — `constructed()`, `enter()`,
 * `levelRandomPath()`, `setSupplies()`, `setStartedPath()`, `replaceEntrance()` and the
 * straggler check of `TOAManager.needsAbandonRequest()`
 * (`com.zenyte.game.content.tombsofamascut[.encounter]`), restructured for the persistent
 * single-region architecture: NR built a fresh `MainHallEncounter` room instance per visit, so
 * its constructor WAS the visit reset — here [onVisitStart] plays that role, invoked by the
 * raid controller whenever the party's room becomes [ToaRoom.MAIN_HALL] (raid start included).
 *
 * The click handlers themselves (path doors, Wardens entry, spirit claim) live in
 * [ToaMainHallScript]; this class owns state + world mutations only, so the raid controller
 * can depend on it without a dependency cycle.
 */
@Singleton
internal class ToaMainHall
@Inject
constructor(
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val manager: InstanceManager,
    private val playerList: PlayerList,
) {

    /**
     * Starts a main-hall visit for [raid] (NR `MainHallEncounter.constructed()`): resets the
     * per-visit state, rolls the visit's path-level increases (Path-level invocations on the
     * first visit, Walk the Path afterwards), spawns the supply spirit after the 2nd and 4th
     * completed paths, applies the increases to [ToaRaid.pathLevels] and sets every entrance
     * door to its correct variant (completed doors closed; Wardens entry open after all four).
     */
    fun onVisitStart(raid: ToaRaid) {
        raid.startedPath = null
        raid.visitPathIncreases.fill(0)
        raid.supplyNpc?.let { npcRepo.del(it, Int.MAX_VALUE) }
        raid.supplyNpc = null
        raid.supplyBundles = null
        raid.supplyEligible.clear()

        val increases = raid.visitPathIncreases
        if (raid.pathLevels.all { it == 0 }) {
            val fill =
                when {
                    raid.settings.isActive(ToaInvocation.PATHMASTER) -> 3
                    raid.settings.isActive(ToaInvocation.PATHFINDER) -> 2
                    raid.settings.isActive(ToaInvocation.PATHSEEKER) -> 1
                    else -> 0
                }
            if (fill > 0) {
                increases.fill(fill)
            }
        }
        val walkThePath = raid.settings.isActive(ToaInvocation.WALK_THE_PATH)
        val completed = raid.pathsCompleted.size
        if (completed == 1 && walkThePath) {
            levelRandomPath(raid, 2)
        }
        if (completed == 2 && walkThePath) {
            levelRandomPath(raid, 1)
        }
        if (completed == 2 || completed == 4) {
            setSupplies(raid)
        }
        if (completed == 3 && walkThePath) {
            levelRandomPath(raid, 1)
        }
        for (path in ToaPath.entries) {
            raid.increasePathLevel(path, increases[path.ordinal])
        }
        applyDoorStates(raid)
        // Members ALREADY standing in the main hall when the party returns (they stayed
        // behind for the whole path) never run an arrival transition, so the visit's effects
        // reach them here. NR's per-visit hall instance re-fired `enter()` for everyone; the
        // persistent hall cannot rely on that.
        for (member in raid.activeMembers.toList()) {
            if (raid.playerState(member.uuid)?.currentRoom != ToaRoom.MAIN_HALL) {
                continue
            }
            val player = playerList.firstOrNull { it.uuid == member.uuid } ?: continue
            arriveEffects(player, raid, member.uuid)
        }
    }

    /**
     * Per-player main-hall arrival (NR `MainHallEncounter.enter()`): the "mysterious rumbling"
     * message + HUD path-level refresh for each path raised this visit, and the supply-arrival
     * grant for members still on the eligibility list. Called by the raid controller from the
     * queued-entry and room-transition flows when the destination is the main hall, and by
     * [onVisitStart] for members already present.
     */
    fun onPlayerArrive(access: ProtectedAccess, raid: ToaRaid) {
        val uuid = access.player.uuid ?: return
        arriveEffects(access.player, raid, uuid)
    }

    /** The arrival effects themselves — plain player calls, safe from any context. */
    private fun arriveEffects(player: Player, raid: ToaRaid, uuid: Long) {
        for (path in ToaPath.entries) {
            if (raid.visitPathIncreases[path.ordinal] != 0) {
                player.mes(
                    "You hear a mysterious rumbling coming from the Path of ${path.properName}."
                )
                ToaHud.setPathLevel(player, path, raid.pathLevels[path.ordinal])
            }
        }
        if (raid.supplyNpc != null && raid.supplyEligible.remove(uuid)) {
            player.mes("<col=0000b2>A helpful spirit has arrived with some supplies.")
            raid.playerState(uuid)?.canClaimSupplies = true
        }
    }

    /**
     * Sets the started path for this visit and re-derives every door state (NR
     * `setStartedPath`: lock the other un-completed entrances on select, unlock on clear).
     * Mirrors NR's `party.setPathType(this.startedPath = startedPath)` — [ToaRaid.currentPath]
     * follows, including to null on a cleared selection.
     */
    fun setStartedPath(raid: ToaRaid, path: ToaPath?) {
        raid.startedPath = path
        raid.currentPath = path
        applyDoorStates(raid)
    }

    /**
     * Clears a stale path selection (NR `handlePathEnter`'s
     * `party.getCurrentRaidArea().isDestroyed()` branch): the party's room is a PATH room
     * whose challenge never started and NO live member remains anywhere on that path (every
     * entrant logged out or was evicted) — NR's emptied room instance was destroyed at that
     * point, voiding the selection. The party's room snaps back to the main hall so a fresh
     * selection flows exactly like the first.
     *
     * Deliberately checks the whole PATH, not just the current room: `advanceRaid` moves the
     * party's room ahead of the members' teleports, so "nobody in the current room" is a
     * normal transient — only a fully deserted path is stale. Also recovers a room latched
     * with `startedPath == null` (a selection transition that died mid-fade), which would
     * otherwise dead-end every door.
     */
    fun clearStaleSelection(raid: ToaRaid) {
        if (raid.stage != ToaRoomStage.NOT_STARTED) {
            return
        }
        val roomPath = raid.currentRoom.path ?: return
        val started = raid.startedPath
        if (started != null && roomPath != started) {
            return
        }
        val anyoneOnPath =
            raid.activeMembers.any { raid.playerState(it.uuid)?.currentRoom?.path == roomPath }
        if (anyoneOnPath) {
            return
        }
        raid.currentRoom = ToaRoom.MAIN_HALL
        raid.challengeStartTick = 0
        setStartedPath(raid, null)
    }

    /**
     * `true` when some live member has not arrived in the party's current room (NR
     * `TOAManager.needsAbandonRequest`) — the leader is then warned before proceeding.
     */
    fun needsAbandonRequest(raid: ToaRaid): Boolean =
        raid.activeMembers.any { raid.playerState(it.uuid)?.currentRoom != raid.currentRoom }

    /**
     * Sets all five entrance doors to their derived states: a completed path shows its closed
     * door (NR base+2), a non-started path shows "unselected" while another path is started
     * (NR base+1), otherwise the open door; the Wardens entry opens once all four paths are
     * complete (NR base+1 in `constructed()`).
     */
    fun applyDoorStates(raid: ToaRaid) {
        for (path in ToaPath.entries) {
            val target =
                when {
                    path in raid.pathsCompleted -> ToaConstants.pathDoorClosed(path)
                    raid.startedPath != null && raid.startedPath != path ->
                        ToaConstants.pathDoorUnselected(path)
                    else -> ToaConstants.pathDoorOpen(path)
                }
            setDoor(raid, path.entrance, target)
        }
        val wardensTarget =
            if (raid.pathsCompleted.size == ToaPath.entries.size) {
                ToaConstants.LOC_WARDENS_DOOR_OPEN
            } else {
                ToaConstants.LOC_WARDENS_DOOR
            }
        setDoor(raid, ToaConstants.WARDENS_DOOR_OFFSET, wardensTarget)
    }

    /**
     * NR `levelRandomPath`: [amount] independent uniform picks over the not-yet-completed
     * paths — with replacement, so one path CAN be rolled twice (NR `Utils.random(list)` in a
     * loop; kept verbatim).
     */
    private fun levelRandomPath(raid: ToaRaid, amount: Int) {
        val available = ToaPath.entries.filter { it !in raid.pathsCompleted }
        if (available.isEmpty()) {
            return
        }
        repeat(amount) {
            val path = available.random()
            raid.visitPathIncreases[path.ordinal]++
        }
    }

    /**
     * NR `setSupplies()`: builds the three bundles from the frozen settings, marks every
     * roster member supply-eligible and spawns the helpful spirit (11694) on its NR tile,
     * movement-locked and attached to the instance for teardown cleanup.
     */
    private fun setSupplies(raid: ToaRaid) {
        raid.supplyBundles = ToaSupplies.bundles(raid.settings)
        raid.supplyEligible += raid.originalMembers.map { it.uuid }
        val coords =
            ToaLayout.roomCoord(raid, ToaRoom.MAIN_HALL, ToaConstants.SUPPLY_NPC_OFFSET)
        val npc = Npc(ToaConstants.NPC_HELPFUL_SPIRIT, coords)
        npc.mode = NpcMode.None
        npcRepo.add(npc, Int.MAX_VALUE)
        manager.attachNpc(raid.instanceId, npc)
        raid.supplyNpc = npc
    }

    /**
     * Replaces the door at main-hall [offset] with [target] when it is not already showing it
     * (NR `replaceEntrance`, id-delta replaced by named variants). The current loc is found by
     * centrepiece shape at the door's origin tile; its angle/shape carry over, exactly like
     * NR's `WorldObject(object.getId() + inc, object.getType(), object.getRotation(), ...)`.
     */
    private fun setDoor(raid: ToaRaid, offset: CoordGrid, target: String) {
        val coords = ToaLayout.roomCoord(raid, ToaRoom.MAIN_HALL, offset)
        val current = findDoor(coords) ?: return
        val targetId = target.asRSCM(RSCMType.LOC)
        if (current.id == targetId) {
            return
        }
        locRepo.add(coords, target, Int.MAX_VALUE, current.angle, current.shape)
    }

    /** NR `World.getObjectWithType(location, 10, ...)` — the centrepiece loc at [coords]. */
    private fun findDoor(coords: CoordGrid): LocInfo? =
        locRepo.findAll(coords).firstOrNull { it.shapeId == DOOR_SHAPE_ID }

    private companion object {
        /** Loc shape 10 (`CentrepieceStraight`) — NR object "type" 10. */
        const val DOOR_SHAPE_ID: Int = 10
    }
}

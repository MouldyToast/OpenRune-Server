package org.rsmod.content.raids.toa.mainhall

import jakarta.inject.Inject
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpNpc1
import org.rsmod.content.raids.toa.hud.ToaHud
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.content.raids.toa.raid.ToaPath
import org.rsmod.content.raids.toa.raid.ToaRaid
import org.rsmod.content.raids.toa.raid.ToaRaidController
import org.rsmod.content.raids.toa.raid.ToaRaidRegistry
import org.rsmod.content.raids.toa.raid.ToaRoom
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Main-hall (nexus) interactions: the four path-entrance doors, the Wardens lower-level entry
 * and the helpful spirit's supply claim.
 *
 * Port of NR/Zenyte `MainHallEncounter.handlePathEnter()` / `handleWardensEnter()` and
 * `TOAManager.startAbandonDialogue()` (`com.zenyte.game.content.tombsofamascut[.encounter]`).
 * The NR object action that dispatched entrance clicks into `handlePathEnter` was not among
 * the provided sources; the bindings here follow the stock cache ops — "Enter" (op1, confirm
 * dialogs) and "Quick-Enter" (op2, NR `quickEnter`/`quickUse` = true) on both the open and
 * the "unselected" door variants (the latter answer with NR's different-path message), no ops
 * on completed/sealed doors.
 *
 * The helpful spirit's claim dialogue was also absent from the NR sources (`SackAction` et al
 * cover the supply BAG only): the claim here follows the live game — op "Claim", each
 * eligible member picks ONE of the three named bundles (Life / Chaos / Power), granted
 * straight to the inventory (add-or-drop).
 */
internal class ToaMainHallScript
@Inject
constructor(
    private val controller: ToaRaidController,
    private val mainHall: ToaMainHall,
    private val registry: ToaRaidRegistry,
    private val playerList: PlayerList,
    private val objRepo: ObjRepository,
) : PluginScript() {

    override fun ScriptContext.startup() {
        for (path in ToaPath.entries) {
            // 46155/46158/46161/46164 toa_nexus_<path>_door, op1 "Enter" / op2 "Quick-Enter".
            onOpLoc1(ToaConstants.pathDoorOpen(path)) { pathEnter(path, quick = false) }
            onOpLoc2(ToaConstants.pathDoorOpen(path)) { pathEnter(path, quick = true) }
            // 46156/46159/46162/46165 _unselected — same ops; NR answered via handlePathEnter.
            onOpLoc1(ToaConstants.pathDoorUnselected(path)) { pathEnter(path, quick = false) }
            onOpLoc2(ToaConstants.pathDoorUnselected(path)) { pathEnter(path, quick = true) }
        }
        // 46168 toa_nexus_wardens_door_open (the sealed 46167 has no ops).
        onOpLoc1(ToaConstants.LOC_WARDENS_DOOR_OPEN) { wardensEnter(quick = false) }
        onOpLoc2(ToaConstants.LOC_WARDENS_DOOR_OPEN) { wardensEnter(quick = true) }
        // 11694 toa_midraidloot_trader "Helpful Spirit", op1 "Claim".
        onOpNpc1(ToaConstants.NPC_HELPFUL_SPIRIT) { claimSupplies() }
    }

    /* ------------------------------------------------------------------------------------ */
    /* Path entrances                                                                       */
    /* ------------------------------------------------------------------------------------ */

    /** NR `MainHallEncounter.handlePathEnter(pathType, player, quickEnter)`. */
    private suspend fun ProtectedAccess.pathEnter(path: ToaPath, quick: Boolean) {
        arriveDelay()
        val uuid = player.uuid ?: return
        val raid = registry.forPlayer(uuid) ?: return
        mainHall.clearStaleSelection(raid)
        val started = raid.startedPath
        if (started != null) {
            if (started == path) {
                // A member following the selected path (NR `enter(true, firstEncounter)` with
                // the party already in the room — no leader gate). When the party has already
                // advanced DEEPER into the path, the caller still enters its first room
                // without re-assigning the party's room (see `transitionTo` NR-BUG-FIX note).
                val partyInFirstRoom = raid.currentRoom == path.firstRoom
                val moved =
                    with(controller) {
                        transitionTo(raid, path.firstRoom, movePartyRoom = partyInFirstRoom)
                    }
                if (moved) {
                    ToaHud.setCurrentPath(player, path.ordinal + 1)
                }
            } else {
                mesbox("You can't proceed as a different path has already been selected.")
            }
            return
        }
        if (raid.currentRoom != ToaRoom.MAIN_HALL) {
            return
        }
        if (!raid.isLeader(uuid)) {
            // NR `enter(true, ...)` leader gate: the party's room would have to change.
            mesbox("Your leader, ${leaderName(raid)}, must enter first.")
            return
        }
        if (mainHall.needsAbandonRequest(raid)) {
            if (!abandonDialogue("Do you wish to walk the Path of ${path.properName}")) {
                return
            }
            evictStragglers(raid)
        } else if (!quick) {
            val proceed =
                choice2(
                    "Yes.",
                    true,
                    "No.",
                    false,
                    title = "Do you wish to walk the Path of ${path.properName}?",
                )
            if (!proceed) {
                return
            }
        }
        // NR ownerRunnable: enter the first room, mark the path started, tell the party.
        // The started-path assignment runs BEFORE the (suspending) transition so the raid can
        // never sit with `currentRoom = firstRoom` but no selection — a leader coroutine dying
        // mid-fade then leaves a coherent selected state that `clearStaleSelection` can void
        // (NR ran `setStartedPath` after `enter()`, but NR's `enter()` never suspended).
        val previousPath = raid.currentPath
        mainHall.setStartedPath(raid, path)
        if (!with(controller) { transitionTo(raid, path.firstRoom) }) {
            // Precondition failure (ghost, dropped state) — the party's room never moved;
            // restore the pre-selection state, doors included.
            raid.startedPath = null
            raid.currentPath = previousPath
            mainHall.applyDoorStates(raid)
            return
        }
        ToaHud.setCurrentPath(player, path.ordinal + 1)
        broadcastFollow(raid, player, "has chosen to walk the Path of ${path.properName}.")
    }

    /* ------------------------------------------------------------------------------------ */
    /* Wardens entry                                                                        */
    /* ------------------------------------------------------------------------------------ */

    /** NR `MainHallEncounter.handleWardensEnter(player, quickEnter)`. */
    private suspend fun ProtectedAccess.wardensEnter(quick: Boolean) {
        arriveDelay()
        val uuid = player.uuid ?: return
        val raid = registry.forPlayer(uuid) ?: return
        if (raid.currentRoom != ToaRoom.MAIN_HALL) {
            // A member following the party below (NR `enter(true, ...)`, room unchanged) —
            // the caller-only variant covers the party being past the first Wardens room.
            val partyInFirstRoom = raid.currentRoom == ToaRoom.WARDENS_FIRST_ROOM
            val moved =
                with(controller) {
                    transitionTo(raid, ToaRoom.WARDENS_FIRST_ROOM, movePartyRoom = partyInFirstRoom)
                }
            if (moved) {
                ToaHud.setCurrentPath(player, ToaConstants.HUD_PATH_WARDENS)
            }
            return
        }
        if (!raid.isLeader(uuid)) {
            mesbox("Your leader, ${leaderName(raid)}, must enter first.")
            return
        }
        if (mainHall.needsAbandonRequest(raid)) {
            // NR-PARITY: NR passed the action WITH its question mark here (unlike the path
            // flow), so the live abandon title rendered "...lower level??".
            if (!abandonDialogue("Do you wish to proceed to the lower level?")) {
                return
            }
            evictStragglers(raid)
        } else if (!quick) {
            val proceed =
                choice2(
                    "Yes.",
                    true,
                    "No.",
                    false,
                    title = "Do you wish to proceed to the lower level?",
                )
            if (!proceed) {
                return
            }
        }
        if (!with(controller) { transitionTo(raid, ToaRoom.WARDENS_FIRST_ROOM) }) {
            return
        }
        ToaHud.setCurrentPath(player, ToaConstants.HUD_PATH_WARDENS)
        broadcastFollow(raid, player, "has proceeded to the lower level.")
    }

    /* ------------------------------------------------------------------------------------ */
    /* Helpful spirit                                                                       */
    /* ------------------------------------------------------------------------------------ */

    /**
     * The spirit's "Claim" op. NR's claim flow was not in the provided sources — this follows
     * the live game: one bundle per eligible member, chosen from the three (see the class
     * KDoc). Eligibility re-checks after the menu suspension so a double-click cannot claim
     * twice.
     */
    private suspend fun ProtectedAccess.claimSupplies() {
        arriveDelay()
        val uuid = player.uuid ?: return
        val raid = registry.forPlayer(uuid) ?: return
        val state = raid.playerState(uuid) ?: return
        val bundles = raid.supplyBundles
        if (raid.supplyNpc == null || bundles == null || !state.canClaimSupplies) {
            mesbox("The spirit has no supplies for you.")
            return
        }
        val choice =
            choice3(
                "${bundles[0].name}.",
                0,
                "${bundles[1].name}.",
                1,
                "${bundles[2].name}.",
                2,
                title = "Claim which supplies?",
            )
        val bundle = bundles.getOrNull(choice) ?: return
        if (!state.canClaimSupplies) {
            return
        }
        state.canClaimSupplies = false
        for (item in bundle.items) {
            invAddOrDrop(objRepo, item.obj, count = item.count)
        }
        mes("You claim the ${bundle.name.lowercase()} supplies.")
    }

    /* ------------------------------------------------------------------------------------ */
    /* Helpers                                                                              */
    /* ------------------------------------------------------------------------------------ */

    /**
     * NR `TOAManager.startAbandonDialogue(action, ...)`: warns about stragglers, then asks
     * "<action>?" — option one waits (abort), option two abandons them. Returns `true` when
     * the leader chose to proceed.
     */
    private suspend fun ProtectedAccess.abandonDialogue(action: String): Boolean {
        mesbox(
            "Some of your party don't seem to have arrived yet.<br>If you proceed, they " +
                "will be abandoned."
        )
        return choice2(
            "No, wait for any stragglers.",
            false,
            "Yes, abandon any stragglers.",
            true,
            title = "$action?",
        )
    }

    /**
     * The abandon-confirmation's straggler eviction (NR `startAbandonDialogue` option two):
     * every live member who has not arrived in the party's current room is removed — rejoin
     * forfeited, exactly like NR's `raidParty.leave(p, false)` — and faded out to the outside
     * spawn with NR's message.
     */
    private fun evictStragglers(raid: ToaRaid) {
        for (member in raid.activeMembers.toList()) {
            val state = raid.playerState(member.uuid) ?: continue
            if (state.currentRoom == raid.currentRoom) {
                continue
            }
            val straggler = playerList.firstOrNull { it.uuid == member.uuid } ?: continue
            controller.leaveRaid(
                straggler,
                raid,
                voluntary = true,
                exitMessage = "Your party moved on without you.",
            )
        }
    }

    /**
     * NR's follow broadcast: "<name> <did something>. Join him/her..." to every other member.
     * NR keyed the pronoun on `isMale`; the appearance pronoun is used here.
     */
    private fun broadcastFollow(raid: ToaRaid, actor: Player, action: String) {
        val uuid = actor.uuid ?: return
        val pronoun =
            when (actor.appearance.subjectPronoun()) {
                "He" -> "him"
                "She" -> "her"
                else -> "them"
            }
        for (member in raid.activeMembers.toList()) {
            if (member.uuid == uuid) {
                continue
            }
            playerList
                .firstOrNull { it.uuid == member.uuid }
                ?.mes("${actor.displayName} $action Join $pronoun...")
        }
    }

    private fun leaderName(raid: ToaRaid): String =
        raid.activeMembers.firstOrNull { it.uuid == raid.leaderUuid }?.name ?: "your leader"
}

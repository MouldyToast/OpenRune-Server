package org.rsmod.content.raids.toa.raid

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.aconverted.interf.IfSubType
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlin.random.Random
import org.rsmod.annotations.InternalApi
import org.rsmod.api.instances.BossInstanceRegistry
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceEnterTransition
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.withInstanceEnterTransition
import org.rsmod.api.instances.withInstanceLeaveTransition
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.player.midiJingle
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.content.other.consumables.potion.toa.ToaPotionEffect
import org.rsmod.content.raids.toa.hud.ToaHud
import org.rsmod.content.raids.toa.invocation.ToaInvocation
import org.rsmod.content.raids.toa.invocation.overtimeRaidLevelPenalty
import org.rsmod.content.raids.toa.lobby.ToaPartyInterfaces
import org.rsmod.content.raids.toa.mainhall.ToaMainHall
import org.rsmod.content.raids.toa.party.ToaParty
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.map.CoordGrid

/**
 * Orchestrates the full lifecycle of a Tombs of Amascut raid: creation, member entry, room
 * transitions, challenge start/complete/reset, deaths, wipes, logouts/rejoins, failure and
 * completion.
 *
 * Port of NR/Zenyte `TOAManager.java` (`enterRaid`/`enter`/`advanceRaid`/`leaveTombs`/
 * `triggerTOAFailure`/`onLogin`), `TOARaidParty.java` (`constructEncounter`/`setCompletion`/
 * `leave`) and the lifecycle methods of `TOARaidArea.java` (`startRoom`/`completeRoom`/
 * `resetRoom`/`checkRoomReset`/`onLogout`/`handleBarrier` gating semantics)
 * (`com.zenyte.game.content.tombsofamascut[.raid]`), restructured onto the OpenRune
 * architecture: one `InstanceSession` + one large region per raid, state on [ToaRaid] in
 * [ToaRaidRegistry], room transitions as in-region telejumps.
 *
 * Member entry runs through the strong player queue [QUEUE_RAID_ENTRY] (bound in
 * `ToaInstance.configure`) so that members other than the interacting player — and the
 * interacting player themselves, whose own coroutine is mid-flight — are moved from a fresh
 * protected-access scope on the next cycle.
 */
@Singleton
internal class ToaRaidController
@Inject
constructor(
    private val manager: InstanceManager,
    private val registry: ToaRaidRegistry,
    private val bossRegistry: BossInstanceRegistry,
    private val playerList: PlayerList,
    private val worldClock: MapClock,
    private val protectedAccess: ProtectedAccessLauncher,
    private val objRepo: ObjRepository,
    private val potionEffects: ToaPotionEffect,
    private val partyInterfaces: ToaPartyInterfaces,
    private val mainHall: ToaMainHall,
) {
    /* ------------------------------------------------------------------------------------ */
    /* Raid creation + entry                                                                */
    /* ------------------------------------------------------------------------------------ */

    /**
     * Creates the raid instance for [party] and pulls the leader and every online member in.
     *
     * NR built the `TOARaidParty` when the leader clicked the entry and let each member enter
     * individually (`TOAManager.enterRaid`); per the Session 1 design the whole party enters
     * together on raid start, so the "X has entered the Tombs of Amascut. Step inside..."
     * broadcast only goes to genuine stragglers who could not be pulled in. NR's raid-start
     * lobby bookkeeping (`raidStarted`, obelisk delist, per-member `currentParty` clear) runs
     * eagerly here via [ToaPartyInterfaces.markRaidStarted] — NR did it inline in
     * `enterRaid`/`enter`. Returns false (with a message to [leader]) when the instance could
     * not be created.
     */
    fun startRaid(leader: Player, party: ToaParty): Boolean {
        val leaderUuid = leader.uuid ?: return false
        if (registry.forPlayer(leaderUuid) != null) {
            leader.mes("You are already in a raid.")
            return false
        }
        val spec = bossRegistry.get(ToaConstants.INSTANCE_KEY)
        if (spec == null) {
            leader.mes("The Tombs of Amascut are unavailable right now.")
            return false
        }
        val result =
            manager.create(leader, ToaConstants.INSTANCE_KEY, spec, InstanceAccess.Private, worldClock.cycle)
        val created =
            when (result) {
                is InstanceManager.Result.Created -> result
                is InstanceManager.Result.Failed -> {
                    leader.mes(result.reason)
                    return false
                }
                else -> return false
            }
        val session = created.session
        val region = manager.regionForId(session.id)
        if (region == null) {
            leader.mes("No instance space available, try again shortly.")
            return false
        }
        // Roster snapshot, leader first (NR: lobby party index 0 = leader). The ToaRaid ctor
        // deep-copies the settings (NR-BUG-FIX: NR's snapshot aliased the leader's bitmaps).
        val identities = buildList {
            add(ToaRaidMember(leaderUuid, leader.displayName))
            for (member in party.members) {
                if (member.uuid != leaderUuid && none { it.uuid == member.uuid }) {
                    add(ToaRaidMember(member.uuid, member.name))
                }
            }
        }
        val raid = ToaRaid(session.id, session, region, party.settings, identities, leaderUuid)
        registry.register(raid)
        // NR built the MainHallEncounter on demand for the party's first `enter`; the raid
        // starts with the main hall as its current room, so the visit starts here (initial
        // Path-level invocation increases, door states).
        mainHall.onVisitStart(raid)

        // The leader was bound by create() (not yet an occupant); members must join first.
        val entered = mutableSetOf(leaderUuid)
        leader.strongQueue(QUEUE_RAID_ENTRY, 1, ToaEntryArgs(session.id.value, rejoin = false))
        for (identity in identities.drop(1)) {
            val member = resolve(identity.uuid)
            if (member == null) {
                // Offline member: keep the roster entry — the login rejoin flow pulls them in.
                continue
            }
            if (manager.sessionForPlayer(member) != null) {
                member.mes("You are already inside an instance.")
                dropFailedJoin(raid, identity.uuid)
                continue
            }
            when (val join = manager.join(member, session, worldClock.cycle, forceAccess = true)) {
                is InstanceManager.Result.Joined -> {
                    member.strongQueue(QUEUE_RAID_ENTRY, 1, ToaEntryArgs(session.id.value, rejoin = false))
                    entered += identity.uuid
                }
                is InstanceManager.Result.Failed -> {
                    member.mes(join.reason)
                    dropFailedJoin(raid, identity.uuid)
                }
                else -> dropFailedJoin(raid, identity.uuid)
            }
        }
        // NR `TOAManager.enterRaid`/`enter` raid-start bookkeeping, run eagerly (a lazy
        // detection can never fire once the whole party is inside the instance): flags +
        // delists the lobby party and clears every member's current-party pointer.
        partyInterfaces.markRaidStarted(party, entered)
        return true
    }

    /**
     * Removes a member whose instance join failed at raid start from the roster and live list
     * (no phantom members: they never became an occupant and cannot follow the party), and
     * drops their player index when it points at this raid — [ToaRaidRegistry.register] may
     * have clobbered an index belonging to another raid, which must not be detached here.
     */
    private fun dropFailedJoin(raid: ToaRaid, uuid: Long) {
        raid.removeMember(uuid, keepRosterEntry = false)
        if (registry.forPlayer(uuid) === raid) {
            registry.detachPlayer(uuid)
        }
    }

    /**
     * Runs one member's queued raid entry (fresh entry or rejoin): fade, telejump,
     * `finalizeEntry`, client varbits, HUD and first-entry messages. Bound to
     * [QUEUE_RAID_ENTRY] in `ToaInstance.configure`.
     */
    suspend fun ProtectedAccess.runQueuedEntry(args: ToaEntryArgs) {
        val raid = registry.forInstance(InstanceId(args.instanceId)) ?: return
        val uuid = player.uuid ?: return
        val state = raid.playerState(uuid) ?: return
        // A queued entry can outlive its membership by a cycle (evicted as a straggler, or
        // the raid failed/finished, between the queue write and this launch): playerStates
        // keeps entries for leavers, so membership must be checked against the LIVE list or
        // the pending entry would teleport a non-member back into the instance.
        if (raid.memberByUuid(uuid) == null || raid.tombsFailure || raid.isFinished) {
            // A rejoin binding taken by tryRejoin must not linger without finalizeEntry —
            // release it so a stale binding can never block the player's next instance entry.
            if (manager.sessionForPlayer(player) === raid.session) {
                manager.leave(player, raid.session, worldClock.cycle)
            }
            return
        }
        val logout = if (args.rejoin) state.logoutState else null
        val room = logout?.room ?: ToaRoom.MAIN_HALL
        // NR-PARITY: a rejoiner ALWAYS restores at the logout room's randomized spawn tile
        // (`TOAManager.onLogin` -> `getRandomizedSpawnTile()`), never their exact logout coords.
        val dest = roomSpawn(raid, room)
        state.logoutState = null

        withInstanceEnterTransition(InstanceEnterTransition()) {
            telejump(dest)
            manager.finalizeEntry(player, raid.session, worldClock.cycle)
        }
        // Invocation scaling tie-in: combat formulas read this for `param.amascutnpc` npcs.
        vars[ToaConstants.VARBIT_RAID_LEVEL] = raid.raidLevel
        if (room == ToaRoom.MAIN_HALL) {
            vars[ToaConstants.VARBIT_CURRENT_PATH] = 0
        }
        state.currentRoom = room

        if (args.rejoin) {
            // NR-PARITY: no trailing period in the source message.
            mes("You have rejoined your party")
            if (logout?.duringChallenge == true) {
                mes(
                    "You logged out during the challenge. Total deaths: " +
                        "<col=ff0000>${raid.totalDeaths}</col>."
                )
                if (raid.stage == ToaRoomStage.STARTED && room == raid.currentRoom) {
                    // NR: a mid-challenge rejoiner comes back as a dead ghost.
                    state.isGhost = true
                    transmog(ToaConstants.NPC_PLAYER_GHOST)
                }
            }
        } else {
            mes("You enter the Tombs of Amascut (${raid.mode.displayName} Mode)...")
            if (raid.timeLimitMinutes != -1) {
                mes(
                    "Overall time to beat: <col=ef1020>${raid.timeLimitMinutes}:00</col>. " +
                        "The timer starts upon choosing your first path."
                )
            }
        }
        ToaHud.open(player)
        ToaHud.refreshParty(raid)
        refreshTimer(player, raid)
        if (room == ToaRoom.MAIN_HALL) {
            // NR MainHallEncounter.enter(): rumbling messages + supply-arrival grant.
            mainHall.onPlayerArrive(this, raid)
        }
        if (args.rejoin) {
            checkRoomReset(raid)
        }
    }

    /* ------------------------------------------------------------------------------------ */
    /* Room transitions                                                                     */
    /* ------------------------------------------------------------------------------------ */

    /**
     * Moves the calling player to [room] (fade + telejump), switching the party's current room
     * first when this player is the first one through (NR `TOAManager.enter`). The raid timer
     * starts on the first non-main-hall room entry. Returns `true` when the player was moved
     * (NR `enter`'s boolean — callers gate HUD path-varbit updates on it).
     *
     * [movePartyRoom] = false teleports the CALLER only, never re-assigning the party's
     * current room — the main hall uses it to let a straggler follow into a path's first room
     * after the party has already advanced deeper (NR-BUG-FIX: NR's `enter(true, ...)` there
     * either leader-gated the straggler or dragged the whole party's room state backwards).
     */
    suspend fun ProtectedAccess.transitionTo(
        raid: ToaRaid,
        room: ToaRoom,
        movePartyRoom: Boolean = true,
    ): Boolean {
        val uuid = player.uuid ?: return false
        val state = raid.playerState(uuid) ?: return false
        if (state.isGhost) {
            mes("A mysterious force prevents you from doing that.")
            return false
        }
        if (movePartyRoom && raid.currentRoom != room) {
            moveParty(raid, room)
        }
        startTimerIfNeeded(raid, room)

        val returningMidPath =
            room == ToaRoom.MAIN_HALL && raid.currentPath != null && raid.pathsCompleted.isNotEmpty()
        val dest =
            if (returningMidPath) {
                mainHallPathSpawn(raid, raid.currentPath ?: return false)
            } else {
                roomSpawn(raid, room)
            }
        // Assigned BEFORE the suspending fade — NR parity (`TOAManager.enter` ran
        // `setCurrentEncounter` synchronously at click time, ahead of its scheduled fade
        // tasks): the straggler/abandon checks and `clearStaleSelection`'s path-occupancy
        // test must see the entrant during the fade window, or a member's door click could
        // void a selection whose leader is still mid-transition.
        state.currentRoom = room
        withInstanceEnterTransition(InstanceEnterTransition()) { telejump(dest) }
        if (returningMidPath) {
            raid.currentPath?.let { faceDirection(it.faceDirection) }
        }
        if (room == ToaRoom.MAIN_HALL) {
            vars[ToaConstants.VARBIT_CURRENT_PATH] = 0
        }
        refreshTimer(player, raid)
        ToaHud.refreshParty(raid)
        if (room == ToaRoom.MAIN_HALL) {
            // NR MainHallEncounter.enter(): rumbling messages + supply-arrival grant.
            mainHall.onPlayerArrive(this, raid)
        }
        return true
    }

    /**
     * Advances the PARTY to the next room: puzzle -> boss, Wardens 1 -> 2 -> reward room, path
     * boss -> main hall, main hall -> the selected path's first room (NR `advanceRaid` =
     * `enter(false, VALUES[ordinal + 1])`; explicit links here — see [ToaRoom.next]). Players
     * follow individually via [transitionTo].
     */
    fun advanceRaid(raid: ToaRaid) {
        val current = raid.currentRoom
        val next =
            when {
                current == ToaRoom.MAIN_HALL -> raid.currentPath?.firstRoom ?: return
                current.next != null -> current.next ?: return
                current.isPathBoss -> ToaRoom.MAIN_HALL
                else -> return
            }
        moveParty(raid, next)
        // NR broadcast "<name> has proceeded to the next challenge. Join him/her..." from the
        // advancing player; advanceRaid is player-less here, so the line is neutral.
        broadcast(raid, exclude = null, "Your party has proceeded to the next challenge.")
    }

    private fun moveParty(raid: ToaRaid, room: ToaRoom) {
        raid.currentRoom = room
        raid.stage = ToaRoomStage.NOT_STARTED
        raid.challengeStartTick = 0
        // NR re-snapshotted `originalPlayers` when the previous room instance was torn down
        // (`TOARaidArea.destroyRegion`); the equivalent here is the party moving on — a
        // logged-out member loses their rejoin ticket once the party advances.
        val before = raid.originalMembers.map { it.uuid }
        raid.snapshotRoster()
        for (uuid in before) {
            // Only drop indexes still pointing at THIS raid — a stale roster entry must never
            // clobber the player's registration in a raid they have since joined.
            if (!raid.isOriginalMember(uuid) && registry.forPlayer(uuid) === raid) {
                registry.detachPlayer(uuid)
            }
        }
        if (room == ToaRoom.MAIN_HALL) {
            // NR reconstructed the MainHallEncounter per visit; the visit reset (Walk the
            // Path rolls, supply spirit, door states) runs when the party moves back. After
            // the roster snapshot — supply eligibility reads the fresh roster (NR re-ran
            // `updateOriginalPlayers` in the old room's teardown before `constructed()`).
            mainHall.onVisitStart(raid)
        }
    }

    private fun startTimerIfNeeded(raid: ToaRaid, room: ToaRoom) {
        if (room == ToaRoom.MAIN_HALL || raid.isTimerStarted) {
            return
        }
        raid.startTick = worldClock.cycle
        // NR-PARITY: the broadcast only fires when a time-limit invocation is active.
        if (raid.timeLimitMinutes != -1) {
            broadcast(
                raid,
                exclude = null,
                "Overall time to beat: <col=ef1020>${raid.timeLimitMinutes}:00</col>. " +
                    "The timer has started!",
            )
        }
    }

    /* ------------------------------------------------------------------------------------ */
    /* Challenge lifecycle                                                                  */
    /* ------------------------------------------------------------------------------------ */

    /** Starts the current room's challenge (NR `TOARaidArea.startRoom` + barrier broadcast). */
    fun startRoom(raid: ToaRaid) {
        if (raid.stage != ToaRoomStage.NOT_STARTED) {
            return
        }
        raid.challengeStartTick = worldClock.cycle
        raid.stage = ToaRoomStage.STARTED
        // NR: HP-scaling team size snapshots the ROSTER size at room start.
        raid.roomTeamSize = raid.originalMembers.size
        broadcast(raid, exclude = null, "Challenge started: ${challengeName(raid, raid.currentRoom)}.")
    }

    /**
     * Completes the current room's challenge (NR `TOARaidArea.completeRoom`): records the
     * challenge result, revives ghosts, sends duration messages, and — on
     * [ToaRoom.WARDENS_SECOND_ROOM] — triggers raid completion (incl. the overrun raid-level
     * cut).
     *
     * Per-member side effects use plain (non-protected) player calls: launching a lenient
     * protected scope here would force-cancel a member's live coroutine — e.g. an in-flight
     * [org.rsmod.content.raids.toa.death.ToaDeathSequence] or a queued-entry transition —
     * silently skipping their respawn/bookkeeping. None of these effects need protection.
     */
    fun completeRoom(raid: ToaRaid) {
        if (raid.stage != ToaRoomStage.STARTED) {
            return
        }
        raid.stage = ToaRoomStage.COMPLETED
        val room = raid.currentRoom
        val duration = worldClock.cycle - raid.challengeStartTick
        raid.challengeResults += ToaChallengeResult(duration, mvpName = null, room = room)
        if (room.isPathBoss) {
            room.path?.let { path ->
                if (path !in raid.pathsCompleted) {
                    raid.pathsCompleted += path
                }
            }
        }
        val isEnd = room == ToaRoom.WARDENS_SECOND_ROOM
        for (identity in raid.activeMembers.toList()) {
            val member = resolve(identity.uuid) ?: continue
            val state = raid.playerState(identity.uuid)
            if (state?.isGhost == true) {
                state.isGhost = false
                member.transmog = null
            }
            if (room.isPuzzle) {
                member.midiJingle(JINGLE_PUZZLE_COMPLETE) // NR jingle 295.
            }
            if (!isEnd) {
                member.mes(
                    "Challenge complete: ${challengeName(raid, room)}. " +
                        "Duration: <col=ff0000>${formatTicks(duration)}</col>."
                )
                member.mes(
                    "Total challenge time: <col=ff0000>${formatTicks(raid.totalChallengeTicks())}</col>."
                )
            }
        }
        // NR-PARITY: full player reset, HP-hud/camera cleanup, out-of-area teleports and the
        // Osmumten teleport npc (jingle 296) are room-content behaviors — later sessions.
        if (isEnd) {
            completeRaid(raid)
        }
        ToaHud.refreshParty(raid)
    }

    /**
     * Resets the current room after a failed (but permitted) wipe (NR `TOARaidArea.resetRoom`):
     * back to NOT_STARTED, 4-6 honey locusts per ROOM player unless ON_A_DIET or the raid
     * failed. NR's `players` was the room area's own player set — a member who never entered
     * the room (e.g. a main-hall straggler) gets nothing.
     */
    fun resetRoom(raid: ToaRaid) {
        raid.stage = ToaRoomStage.NOT_STARTED
        raid.challengeStartTick = 0
        val onDiet = raid.settings.isActive(ToaInvocation.ON_A_DIET)
        if (onDiet || raid.tombsFailure) {
            return
        }
        for (identity in raid.activeMembers.toList()) {
            if (raid.playerState(identity.uuid)?.currentRoom != raid.currentRoom) {
                continue
            }
            val member = resolve(identity.uuid) ?: continue
            // NR: `random(4, 6)` honey locusts per player on room reset. Plain call — see
            // [completeRoom] on why these fan-outs must not launch protected scopes.
            member.invAddOrDrop(objRepo, OBJ_HONEY_LOCUST, count = 4 + Random.nextInt(3))
        }
    }

    /* ------------------------------------------------------------------------------------ */
    /* Death / wipe / logout                                                                */
    /* ------------------------------------------------------------------------------------ */

    /**
     * Raid-state bookkeeping for a member's death — called by the death agent's
     * `PlayerDeathSequenceHook` flow in the SAME tick as the respawn/ghost transmog (NR
     * `TOARaidArea.sendDeath` tick-5 stage): death counters, point loss, ghost flag and
     * messages. [duringChallenge] is the sequence's tick-5 decision — the one that applied
     * the transmog — so the ghost flag can never desync from the visual ghost state. The
     * tick-7 wipe check ([checkRoomReset]) is the sequence's separate final step, as in NR.
     */
    fun onPlayerDeath(player: Player, raid: ToaRaid, duringChallenge: Boolean) {
        val uuid = player.uuid ?: return
        val state = raid.playerState(uuid) ?: return
        raid.totalDeaths++
        state.deaths++
        state.points = ToaScaling.pointsAfterDeath(state.points)
        if (duringChallenge) {
            state.isGhost = true
        }
        player.mes("You have died. Total deaths: <col=ff0000>${raid.totalDeaths}</col>.")
        // NR-PARITY: the respawn-wait line only shows while challenge players remain (NR
        // `getChallengePlayers().length > 0`) — the dead player is already outside the
        // challenge area by this point in the sequence, so they never count themselves.
        if (duringChallenge && anyChallengeSurvivor(raid)) {
            if (raid.permittedTeamDeaths == -1 || raid.permittedTeamDeaths > raid.teamDeaths + 1) {
                player.mes("You will respawn when your party completes or fails the challenge.")
            } else {
                player.mes("You will respawn when your party completes the challenge.")
            }
        }
        // NR-BUG-FIX: NR broadcast the RECEIVER's name ("p.getName()"); use the dead player's.
        broadcast(
            raid,
            exclude = uuid,
            "<col=ff0000>${player.displayName}</col> has died. " +
                "Total deaths: <col=ff0000>${raid.totalDeaths}</col>.",
        )
        ToaHud.refreshParty(raid)
    }

    /**
     * Wipe detection (NR `TOARaidArea.checkRoomReset`). NR scanned the current ROOM area's
     * own player set: only members who have entered the party's current room count — a
     * live straggler elsewhere (e.g. the main hall) can neither survive nor block a wipe —
     * and a STARTED room whose players all logged out still counts one. A survivor is a
     * room member who is inside the challenge area or not a ghost (NR's
     * `insideChallengeArea(p) || !isTransformedIntoNpc(p)`). On a wipe, count a team death
     * and either reset the room or fail the raid when the permitted-wipe budget is spent.
     * Called after deaths, leaves, logouts and rejoins.
     */
    fun checkRoomReset(raid: ToaRaid) {
        if (raid.stage != ToaRoomStage.STARTED || raid.tombsFailure) {
            return
        }
        val room = raid.currentRoom
        val inRoom =
            raid.activeMembers.mapNotNull { m ->
                val state = raid.playerState(m.uuid)
                if (state?.currentRoom != room) {
                    null
                } else {
                    resolve(m.uuid)?.let { player -> Triple(m, state, player) }
                }
            }
        val anySurvivor =
            inRoom.any { (_, state, player) ->
                !state.isGhost || insideChallengeArea(raid, room, player.coords)
            }
        if (anySurvivor) {
            return
        }
        raid.teamDeaths++
        val resetAllowed = raid.permittedTeamDeaths == -1 || raid.teamDeaths < raid.permittedTeamDeaths
        if (resetAllowed) {
            // NR checkRoomReset room-reset flow: per player, a fade screen whose callback ran
            // the full reset (untransform, tab reopen, jingle 90, the wipe message), then an
            // unfade with a HUD re-send two ticks later. Strong-queued per member (the same
            // pattern as raid entry): a launchLenient fan-out here would force-cancel the
            // dying member's own in-flight death sequence.
            for ((_, _, player) in inRoom) {
                player.strongQueue(QUEUE_ROOM_WIPE, 1, ToaWipeArgs(raid.instanceId.value))
            }
            resetRoom(raid)
            ToaHud.refreshParty(raid)
        } else {
            // Plain per-player calls — see [completeRoom] on why these fan-outs must not
            // launch protected scopes; failRaid owns the faded failure exit.
            for ((_, state, player) in inRoom) {
                if (state.isGhost) {
                    state.isGhost = false
                    player.transmog = null
                }
                player.midiJingle(JINGLE_RAID_FAIL) // NR jingle 90.
            }
            raid.tombsFailure = true
            failRaid(raid)
            // NR runs resetRoom either way; tombsFailure suppresses the honey locusts.
            resetRoom(raid)
        }
    }

    /**
     * Runs one member's queued room-wipe reset — the per-player half of NR
     * `TOARaidArea.checkRoomReset`'s reset branch: fade to black, then (NR's fade callback)
     * the full player reset (`p.reset()` -> stat restore), the ghost untransform with the
     * inventory/equipment tab reopen, jingle 90 and the wipe message, then the unfade with a
     * HUD re-send (NR `sendHud()` in the unfade callback). Bound to [QUEUE_ROOM_WIPE] in
     * `ToaInstance.configure`.
     */
    suspend fun ProtectedAccess.runQueuedWipe(args: ToaWipeArgs) {
        val raid = registry.forInstance(InstanceId(args.instanceId)) ?: return
        val uuid = player.uuid ?: return
        val state = raid.playerState(uuid) ?: return
        fadeOverlay(
            startColour = 0,
            startTransparency = 0,
            endColour = 0,
            endTransparency = 255,
            clientDuration = WIPE_FADE_CLIENT_DURATION,
        )
        delay(1)
        if (state.isGhost) {
            state.isGhost = false
        }
        resetTransmog()
        statRestoreAll(allStatNames())
        // NR reopened the tabs the ghost transform had closed (EQUIPMENT_TAB + INVENTORY_TAB).
        ifOpenSub(IF_INVENTORY_TAB, COM_SIDE_INVENTORY, IfSubType.Overlay)
        ifOpenSub(IF_WORNITEMS_TAB, COM_SIDE_WORNITEMS, IfSubType.Overlay)
        midiJingle(JINGLE_RAID_FAIL) // NR jingle 90.
        if (raid.permittedTeamDeaths == -1) {
            mes("Your party failed to complete the challenge. You may try again...")
        } else {
            val remaining = raid.permittedTeamDeaths - raid.teamDeaths
            mes(
                "Your party failed to complete the challenge. You have " +
                    "<col=ff0000>$remaining</col> attempts remaining..."
            )
        }
        delay(2)
        closeFadeOverlay()
        // NR unfade callback: sendHud().
        ToaHud.open(player)
        ToaHud.refreshMember(raid, player)
        refreshTimer(player, raid)
    }

    /**
     * Handles a member logging out inside the raid (NR `TOARaidArea.onLogout`): snapshots the
     * rejoin state, counts a mid-challenge logout as a death, and removes them from the live
     * list while KEEPING their roster entry (the rejoin ticket).
     *
     * The instance framework's own logout hook (`InstanceManager.handleLogout`, run by
     * `InstanceLifecycleScript`) independently removes the occupant and persists
     * `InstanceAttributes.LOGIN_EXIT_COORD` = the DB exit coord, so the next login places the
     * player outside the raid entrance — mirroring NR's `forceLocation(outside)`.
     */
    fun onPlayerLogout(player: Player, raid: ToaRaid) {
        val uuid = player.uuid ?: return
        if (raid.memberByUuid(uuid) == null) {
            return
        }
        val state = raid.playerState(uuid) ?: return
        val room = state.currentRoom ?: return
        val previousLeader = raid.leaderUuid
        val duringChallenge =
            raid.stage == ToaRoomStage.STARTED &&
                room == raid.currentRoom &&
                insideChallengeArea(raid, room, player.coords)
        val coords = if (duringChallenge) roomSpawn(raid, room) else player.coords
        state.logoutState =
            ToaLogoutState(
                room = room,
                duringChallenge = duringChallenge,
                coords = coords,
                safeDeath = raid.permittedTeamDeaths == -1,
            )
        state.isGhost = false
        state.currentRoom = null
        if (duringChallenge) {
            // NR: a mid-challenge logout is a death.
            state.deaths++
            raid.totalDeaths++
            broadcast(
                raid,
                exclude = uuid,
                "<col=ff0000>${player.displayName}</col> has logged out. " +
                    "Total deaths: <col=ff0000>${raid.totalDeaths}</col>.",
            )
        }
        raid.removeMember(uuid, keepRosterEntry = true)
        notifyPromotion(raid, previousLeader)
        ToaHud.refreshParty(raid)
        checkRoomReset(raid)
    }

    /**
     * Reconciles raid state when the instance framework evicts a still-active member on a
     * path the module does not own — `InstanceManager.reconcileOccupants` removing an
     * occupant whose coords left the region bounds (e.g. a spell teleport out of the raid).
     *
     * NR's equivalent was the raid AREA's leave hook (`TOARaidArea` leave →
     * `TOARaidParty.leave` → wipe check): the member drops off the live list but KEEPS their
     * roster entry, so stepping back in through the entrance rejoins the raid. Plain player
     * calls only — the evictee may hold a live coroutine (their teleport). Called from
     * `ToaLogoutScript`'s `InstancePlayerLeaveUnboundEvent` handler.
     */
    fun onPlayerEvicted(player: Player, raid: ToaRaid) {
        val uuid = player.uuid ?: return
        if (raid.memberByUuid(uuid) == null) {
            return
        }
        val previousLeader = raid.leaderUuid
        raid.playerState(uuid)?.let { state ->
            if (state.isGhost) {
                state.isGhost = false
                player.transmog = null
            }
            state.currentRoom = null
        }
        raid.removeMember(uuid, keepRosterEntry = true)
        potionEffects.clearSessionEffects(player)
        player.mes("You have left the Tombs of Amascut.")
        notifyPromotion(raid, previousLeader)
        ToaHud.refreshParty(raid)
        checkRoomReset(raid)
    }

    /**
     * Attempts to re-enter the caller's still-alive raid (NR `TOAManager.onLogin` rejoin
     * branch): roster membership is the ticket; the raid must not have failed or finished.
     * Entry itself runs through the queued entry flow with `rejoin = true`.
     */
    fun tryRejoin(player: Player): Boolean {
        val uuid = player.uuid ?: return false
        val raid = registry.forPlayer(uuid) ?: return false
        if (raid.tombsFailure || raid.isFinished) {
            // A failed/finished raid holds no rejoin ticket: drop the player index so the
            // stale entry cannot lock the player out of ToA while the instance winds down.
            registry.detachPlayer(uuid)
            return false
        }
        if (!raid.isOriginalMember(uuid)) {
            return false
        }
        if (manager.sessionForPlayer(player) != null) {
            return false
        }
        when (val join = manager.join(player, raid.session, worldClock.cycle, forceAccess = true)) {
            is InstanceManager.Result.Joined -> Unit
            is InstanceManager.Result.Failed -> {
                player.mes(join.reason)
                return false
            }
            else -> return false
        }
        raid.addMember(ToaRaidMember(uuid, player.displayName))
        registry.attachPlayer(uuid, raid)
        player.strongQueue(QUEUE_RAID_ENTRY, 1, ToaEntryArgs(raid.instanceId.value, rejoin = true))
        return true
    }

    /* ------------------------------------------------------------------------------------ */
    /* Leaving / failure / completion                                                       */
    /* ------------------------------------------------------------------------------------ */

    /**
     * Removes [player] from the raid (NR `leaveTombs` + `TOARaidParty.leave`): a [voluntary]
     * leave forfeits the rejoin ticket; the player is faded out to the lobby-side outside
     * spawn. The session self-destructs when the last occupant leaves (`destroyWhenEmpty`).
     * [exitMessage] overrides the default farewell (the main hall's straggler eviction sends
     * NR's "Your party moved on without you." through here).
     */
    @OptIn(InternalApi::class)
    fun leaveRaid(
        player: Player,
        raid: ToaRaid,
        voluntary: Boolean,
        exitMessage: String? =
            if (voluntary) "You abandon the raid and leave the Tombs of Amascut." else null,
    ) {
        val uuid = player.uuid ?: return
        val previousLeader = raid.leaderUuid
        raid.playerState(uuid)?.let { state ->
            state.isGhost = false
            state.currentRoom = null
        }
        raid.removeMember(uuid, keepRosterEntry = !voluntary)
        if (voluntary && registry.forPlayer(uuid) === raid) {
            registry.detachPlayer(uuid)
        }
        notifyPromotion(raid, previousLeader)
        launchExit(raid = raid, player = player, message = exitMessage, playJingle = false)
        ToaHud.refreshParty(raid)
        checkRoomReset(raid)
    }

    /**
     * Fails the raid for every remaining member (NR `triggerTOAFailure`): all are faded out to
     * the outside spawn. Registry cleanup follows automatically via `InstanceEndedEvent` once
     * the last occupant is removed (`destroyWhenEmpty`).
     */
    fun failRaid(raid: ToaRaid) {
        raid.tombsFailure = true
        for (identity in raid.activeMembers.toList()) {
            raid.playerState(identity.uuid)?.let { state ->
                state.isGhost = false
                state.currentRoom = null
            }
            raid.removeMember(identity.uuid, keepRosterEntry = false)
            val player = resolve(identity.uuid) ?: continue
            launchExit(
                raid = raid,
                player = player,
                message = "You failed to survive the Tombs of Amascut.",
                playJingle = false, // NR `triggerTOAFailure(false)` on wipe; jingle 90 already played.
            )
        }
    }

    /**
     * Raid completion (NR `TOARaidParty.setCompletion` + the end-of-raid messaging in
     * `TOARaidArea.completeRoom`): freezes the total time, applies the over-time raid-level
     * cut, and stops the HUD timer.
     */
    fun completeRaid(raid: ToaRaid) {
        if (raid.isFinished) {
            return
        }
        raid.totalTimeTicks = if (raid.isTimerStarted) worldClock.cycle - raid.startTick else 0
        var completedLevel = raid.raidLevel
        val overTime =
            raid.timeLimitMinutes != -1 &&
                raid.totalTimeTicks > raid.timeLimitMinutes * TICKS_PER_MINUTE
        if (overTime) {
            // NR-PARITY: -10/-15/-20/-25 raid-level cut (NR values; OSRS uses larger cuts).
            completedLevel -= raid.settings.overtimeRaidLevelPenalty()
        }
        raid.completedRaidLevel = completedLevel
        raid.failedTimeChallenge = completedLevel != raid.raidLevel
        if (overTime) {
            // NR `setCompletion` wrote the cut back (`partySettings.setRaidLevel`), so the
            // completion messaging below — and later kc/reward wiring — read the reduced
            // effective raid level and its recomputed mode.
            raid.raidLevel = completedLevel
        }
        for (identity in raid.activeMembers.toList()) {
            val member = resolve(identity.uuid) ?: continue
            member.mes(
                "You have completed the Tombs of Amascut (${raid.mode.displayName} Mode)! " +
                    "Duration: <col=ff0000>${formatTicks(raid.totalTimeTicks)}</col>."
            )
            if (raid.timeLimitMinutes != -1) {
                val line =
                    if (overTime) {
                        "You failed to beat the challenge time. Your raid level has been " +
                            "reduced to <col=ff0000>${raid.completedRaidLevel}</col>."
                    } else {
                        "You beat the challenge time!"
                    }
                member.mes(line)
            }
            // cs2: toa_speedrun_time_update — finished flag freezes the HUD timer.
            member.runClientScript(ToaConstants.CS2_TIMER_UPDATE, raid.totalTimeTicks, 1)
        }
        computeRewards(raid)
    }

    private fun computeRewards(raid: ToaRaid) {
        // NR-PARITY: NR's `TOARaidParty.computeRewards()` is an EMPTY stub ("//TODO figure out
        // what rewards we want // 14373 varbit purple chest"). Rewards (and the boss-kc
        // notification NR sent from completeRoom's end branch) are ported in a later session.
    }

    /* ------------------------------------------------------------------------------------ */
    /* Tick driver                                                                          */
    /* ------------------------------------------------------------------------------------ */

    /**
     * Per-cycle raid upkeep, driven by `ToaRaidTickScript`: keeps the HUD speedrun timer in
     * sync for every online member while the raid timer runs.
     */
    fun tick(raid: ToaRaid) {
        if (!raid.isTimerStarted || raid.isFinished || raid.tombsFailure) {
            return
        }
        val elapsed = raid.elapsedTicks(worldClock.cycle)
        for (identity in raid.activeMembers) {
            // cs2: toa_speedrun_time_update — (elapsed ticks, finished flag).
            resolve(identity.uuid)?.runClientScript(ToaConstants.CS2_TIMER_UPDATE, elapsed, 0)
        }
    }

    /* ------------------------------------------------------------------------------------ */
    /* Helpers                                                                              */
    /* ------------------------------------------------------------------------------------ */

    /**
     * Whether [coords] lies inside [room]'s challenge bounding box (NR
     * `EncounterType.insideChallengeArea`), honoring Zebak's water-lane exclusion rectangles.
     */
    fun insideChallengeArea(raid: ToaRaid, room: ToaRoom, coords: CoordGrid): Boolean {
        val min = room.challengeMin ?: return false
        val max = room.challengeMax ?: return false
        if (room == ToaRoom.ZEBAK_BOSS) {
            for ((exclusionMin, exclusionMax) in ToaRoom.ZEBAK_CHALLENGE_EXCLUSIONS) {
                if (within(raid, room, exclusionMin, exclusionMax, coords)) {
                    return false
                }
            }
        }
        return within(raid, room, min, max, coords)
    }

    /**
     * `true` while any live member of the party's current room is still inside its challenge
     * area (NR `getChallengePlayers().length > 0` at `sendDeath` tick 5 — ghosts and freshly
     * respawned members stand at the room spawn, outside the challenge bounds).
     */
    private fun anyChallengeSurvivor(raid: ToaRaid): Boolean =
        raid.activeMembers.any { member ->
            raid.playerState(member.uuid)?.currentRoom == raid.currentRoom &&
                resolve(member.uuid)?.let { player ->
                    insideChallengeArea(raid, raid.currentRoom, player.coords)
                } == true
        }

    private fun within(
        raid: ToaRaid,
        room: ToaRoom,
        minOffset: CoordGrid,
        maxOffset: CoordGrid,
        coords: CoordGrid,
    ): Boolean {
        val min = ToaLayout.roomCoord(raid, room, minOffset)
        val max = ToaLayout.roomCoord(raid, room, maxOffset)
        return coords.level == min.level && coords.x in min.x..max.x && coords.z in min.z..max.z
    }

    /** Randomized room-entry spawn (NR `EncounterType.getRandomizedSpawnTile`). */
    private fun roomSpawn(raid: ToaRaid, room: ToaRoom): CoordGrid =
        ToaLayout.roomCoord(
            raid,
            room,
            room.playerSpawn.translate(
                Random.nextInt(room.spawnRandomX + 1),
                Random.nextInt(room.spawnRandomZ + 1),
            ),
        )

    /** Randomized mid-path main-hall re-entry spawn (NR `TOAPathType.getRandomizedSpawnTile`). */
    private fun mainHallPathSpawn(raid: ToaRaid, path: ToaPath): CoordGrid =
        ToaLayout.roomCoord(
            raid,
            ToaRoom.MAIN_HALL,
            path.mainHallSpawn.translate(
                Random.nextInt(path.spawnRandomX + 1),
                Random.nextInt(path.spawnRandomZ + 1),
            ),
        )

    /** Randomized lobby-side outside spawn (NR `getRandomizedOutsideLocation`). */
    private fun outsideSpawn(): CoordGrid =
        ToaConstants.OUTSIDE.translate(
            Random.nextInt(ToaConstants.OUTSIDE_RANDOM_X + 1),
            Random.nextInt(ToaConstants.OUTSIDE_RANDOM_Z + 1),
        )

    /** Challenge display name (NR `TOARaidArea.getCurrentChallengeName`). */
    private fun challengeName(raid: ToaRaid, room: ToaRoom): String =
        if (room.isPuzzle) {
            "Path of ${(room.path ?: raid.currentPath)?.properName ?: "Unknown"}"
        } else {
            room.bossDisplayName ?: room.name
        }

    @OptIn(InternalApi::class)
    private fun launchExit(raid: ToaRaid, player: Player, message: String?, playJingle: Boolean) {
        protectedAccess.launchLenient(player) {
            resetTransmog()
            withInstanceLeaveTransition(InstanceEnterTransition()) {
                manager.leave(player, raid.session, worldClock.cycle)
                telejump(outsideSpawn())
            }
            // NR-PARITY: NR's `removeTOAItems` strips supplies here and `triggerTOAFailure`
            // routes items into a retrieval chest; supplies and the retrieval service are out
            // of Session 1 scope (no supply items can exist yet).
            potionEffects.clearSessionEffects(player)
            ToaHud.close(player)
            message?.let { mes(it) }
            if (playJingle) {
                midiJingle(JINGLE_RAID_FAIL)
            }
        }
    }

    private fun notifyPromotion(raid: ToaRaid, previousLeader: Long) {
        if (raid.leaderUuid != previousLeader && raid.activeMembers.isNotEmpty()) {
            resolve(raid.leaderUuid)?.mes("You have been promoted to the raid party leader.")
        }
    }

    private fun refreshTimer(player: Player, raid: ToaRaid) {
        if (!raid.isTimerStarted) {
            return
        }
        // cs2: toa_speedrun_time_update (NR `refreshTimer`).
        if (raid.isFinished) {
            player.runClientScript(ToaConstants.CS2_TIMER_UPDATE, raid.totalTimeTicks, 1)
        } else {
            player.runClientScript(ToaConstants.CS2_TIMER_UPDATE, raid.elapsedTicks(worldClock.cycle), 0)
        }
    }

    private fun broadcast(raid: ToaRaid, exclude: Long?, text: String) {
        for (member in raid.activeMembers.toList()) {
            if (member.uuid == exclude) {
                continue
            }
            resolve(member.uuid)?.mes(text)
        }
    }

    private fun resolve(uuid: Long): Player? = playerList.firstOrNull { it.uuid == uuid }

    /** NR `TOARaidArea.formatTime`, simplified to a consistent `[h:]mm:ss` / `m:ss` render. */
    private fun formatTicks(ticks: Int): String {
        val totalSeconds = (ticks.coerceAtLeast(0) * 6) / 10
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun allStatNames(): List<String> =
        ServerCacheManager.getStats().values.map { RSCM.getReverseMapping(RSCMType.STAT, it.id) }

    companion object {
        /** Strong player queue driving member entries; bound in `ToaInstance.configure`. */
        const val QUEUE_RAID_ENTRY: String = "queue.toa_raid_entry"

        /** Strong player queue driving per-member wipe resets; bound in `ToaInstance.configure`. */
        const val QUEUE_ROOM_WIPE: String = "queue.toa_room_wipe"

        /** cs2 fade args (0, 0, 0, 255, 50) — NR `FadeScreen` equivalent (see `ToaLobbyScript`). */
        private const val WIPE_FADE_CLIENT_DURATION: Int = 50

        /** Inventory side tab (NR `GameInterface.INVENTORY_TAB`). */
        private const val IF_INVENTORY_TAB: String = "interface.inventory"

        /** Worn-equipment side tab (NR `GameInterface.EQUIPMENT_TAB`). */
        private const val IF_WORNITEMS_TAB: String = "interface.wornitems"

        /** Toplevel side3 slot — the inventory tab target (see `Cinematic.openTopLevelTabs`). */
        private const val COM_SIDE_INVENTORY: String = "component.toplevel_osrs_stretch:side3"

        /** Toplevel side4 slot — the worn-equipment tab target. */
        private const val COM_SIDE_WORNITEMS: String = "component.toplevel_osrs_stretch:side4"

        /** 27351 — honey locust handed out on room reset (verified gameval). */
        private const val OBJ_HONEY_LOCUST: String = "obj.toa_honey_locust"

        /**
         * Raid failure/wipe jingle. NR played its cache's raw jingle 90; the stock rev240
         * cache has no dedicated ToA-failure jingle (and GameValProvider rejects aliases of
         * existing osrs ids), so the generic minigame-loss jingle (968) substitutes.
         */
        private const val JINGLE_RAID_FAIL: String = "jingle.game_lose"

        /** Puzzle-room completion — stock gameval (1100); NR played its cache's raw 295. */
        private const val JINGLE_PUZZLE_COMPLETE: String = "jingle.toa_path_complete_jingle"

        private const val TICKS_PER_MINUTE: Int = 100
    }
}

/**
 * Arguments carried by the [ToaRaidController.QUEUE_RAID_ENTRY] strong queue: which raid the
 * entry belongs to, and whether it is a rejoin (restores the logout snapshot) or a fresh
 * raid-start entry (main hall + first-entry messages).
 */
internal data class ToaEntryArgs(val instanceId: Long, val rejoin: Boolean)

/**
 * Arguments carried by the [ToaRaidController.QUEUE_ROOM_WIPE] strong queue: which raid the
 * member's wipe reset belongs to.
 */
internal data class ToaWipeArgs(val instanceId: Long)

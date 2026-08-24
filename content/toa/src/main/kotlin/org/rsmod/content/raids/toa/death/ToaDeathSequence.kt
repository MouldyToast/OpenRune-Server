package org.rsmod.content.raids.toa.death

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.death.PlayerDeathSequenceHook
import org.rsmod.api.instances.inInstance
import org.rsmod.api.mechanics.toxins.Toxin.cureAllToxins
import org.rsmod.api.player.deathResetTimers
import org.rsmod.api.player.disablePrayers
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.queueDeath
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.content.raids.toa.raid.ToaLayout
import org.rsmod.content.raids.toa.raid.ToaRaid
import org.rsmod.content.raids.toa.raid.ToaRaidController
import org.rsmod.content.raids.toa.raid.ToaRaidRegistry
import org.rsmod.content.raids.toa.raid.ToaRoom
import org.rsmod.content.raids.toa.raid.ToaRoomStage
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

/**
 * The Tombs of Amascut death flow: the [PlayerDeathSequenceHook] that takes over from the
 * standard death sequence, and the 7-tick ghost/respawn sequence itself.
 *
 * Port of NR/Zenyte `TOARaidArea.java` — `sendDeath()` (the 7-tick death `WorldTask`),
 * `turnIntoDeadGhost()` and the `DeathPlugin` area override — plus the death-side pieces of
 * `TOAManager.java` (`com.zenyte.game.content.tombsofamascut.raid`).
 *
 * Flow:
 * 1. `queue.death` fires as usual ([org.rsmod.api.death.PlayerDeath.death]); [overrideDeath]
 *    claims the death for raid members and queues [ToaDeathScript.QUEUE_TOA_DEATH]. Returning
 *    `true` skips the ENTIRE standard sequence — no death animation, no item drops, no
 *    Lumbridge respawn — which is ToA's safe-death handling (NR: raid deaths never drop
 *    items; `TOARaidArea.getDeathInformation() = null`).
 * 2. [runDeathSequence] (next cycle, protected) plays the NR tick schedule: tick 0 camera
 *    reset + stop actions, tick 1 death animation, tick 5 full reset + respawn at the room's
 *    randomized spawn (via [ToaRoom] offsets + [ToaLayout.roomCoord]) with the ghost transmog
 *    (`npc.toa_player_ghost`, 11695) for a mid-challenge death, tick 7 the wipe check.
 * 3. [ToaRaidController.onPlayerDeath] owns the tick-5 bookkeeping: death counters, the
 *    `max(1000, 20%)` point penalty, the ghost flag, death messages/broadcast and the HUD
 *    refresh. It receives the SAME `duringChallenge` decision that applied the transmog, in
 *    the same tick, so flag and visual ghost state can never desync (NR ran all of this
 *    atomically in `sendDeath`'s tick-5 stage). Tick 7 runs only
 *    [ToaRaidController.checkRoomReset] — NR's exact schedule.
 *
 * A death OUTSIDE a challenge (stage != STARTED, or a straggler in a different room, or in
 * the main hall) is a simple respawn at the room spawn: no ghost transmog and no team-death
 * consequences (the controller's wipe check early-returns).
 *
 * NR-PARITY deviations (cosmetic, engine gaps): no `blockIncomingHits` grace window exists in
 * OpenRune; the ghost's inventory/equipment tab close + run-off are skipped (NR reopened them
 * on revival — here the completion/eviction revival paths only reset the transmog, while the
 * wipe reset queue reopens the tabs as NR did); the Ba-Ba pit-death variant
 * (`"apmeken_baba_pit_death"` fall animation) and RETRIBUTION-on-death are room/prayer content
 * for later sessions.
 */
@Singleton
internal class ToaDeathSequence
@Inject
constructor(
    private val registry: ToaRaidRegistry,
    private val controller: ToaRaidController,
) : PlayerDeathSequenceHook {

    /**
     * Claims the death of an active raid member inside the raid instance. A ghost's "death"
     * and re-queued deaths while the sequence is mid-flight are swallowed (consumed with no
     * new sequence) — NR ghosts are untargetable and the death task ran once.
     */
    override fun overrideDeath(player: Player): Boolean {
        val uuid = player.uuid ?: return false
        val raid = registry.forPlayer(uuid) ?: return false
        if (!player.inInstance(raid.instanceId)) {
            return false
        }
        if (raid.memberByUuid(uuid) == null) {
            return false
        }
        val state = raid.playerState(uuid) ?: return false
        if (state.isGhost || player.attr[SEQUENCE_ACTIVE] == true) {
            return true
        }
        player.attr[SEQUENCE_ACTIVE] = true
        player.strongQueue(ToaDeathScript.QUEUE_TOA_DEATH, 1)
        return true
    }

    /**
     * The NR `sendDeath` 7-tick task, driven from the [ToaDeathScript.QUEUE_TOA_DEATH] player
     * queue. If the raid dissolved while the queue was pending, the standard death is
     * re-queued instead ([overrideDeath] then declines and the normal sequence runs).
     */
    suspend fun ProtectedAccess.runDeathSequence() {
        try {
            val uuid = player.uuid ?: return
            val raid = registry.forPlayer(uuid)
            if (raid == null || raid.memberByUuid(uuid) == null || !player.inInstance(raid.instanceId)) {
                player.queueDeath()
                return
            }
            val state = raid.playerState(uuid) ?: return
            val room = state.currentRoom ?: raid.currentRoom

            // NR tick 0: reset camera, stop the current animation/actions ("lock" is implicit
            // in this protected coroutine's delays).
            stopAction()
            camReset()
            resetAnim()
            delay(1)

            // NR tick 1: death animation.
            anim(SEQ_DEATH)
            delay(4)

            // NR tick 5: full player reset, respawn, — mid-challenge — the ghost transform,
            // and ALL the death bookkeeping (counters, point loss, ghost flag, messages,
            // HUD) in the same tick, sharing one `duringChallenge` decision so the transmog
            // and the ghost flag cannot desync if the room's stage changes before tick 7.
            combatClearQueue()
            clearQueue(QUEUE_STANDARD_DEATH)
            resetRaidDeathState()
            resetAnim()
            val duringChallenge =
                raid.stage == ToaRoomStage.STARTED &&
                    room == raid.currentRoom &&
                    room != ToaRoom.MAIN_HALL
            telejump(respawnCoord(raid, room))
            if (duringChallenge) {
                // NR `turnIntoDeadGhost`: transmog to npc 11695 (`toa_player_ghost`).
                transmog(ToaConstants.NPC_PLAYER_GHOST)
            }
            controller.onPlayerDeath(player, raid, duringChallenge)
            delay(2)

            // NR tick 7: the wipe check only.
            controller.checkRoomReset(raid)
        } finally {
            player.attr.remove(SEQUENCE_ACTIVE)
        }
    }

    /**
     * Respawn coordinate (NR `sendDeath` tick 5): the room's randomized entry spawn for the
     * main hall or a NOT_STARTED/STARTED room; the challenge-side spawn once the room's
     * challenge is COMPLETED.
     */
    private fun ProtectedAccess.respawnCoord(raid: ToaRaid, room: ToaRoom): CoordGrid {
        val challengeSpawn = room.challengeSpawn
        if (raid.stage == ToaRoomStage.COMPLETED && room == raid.currentRoom && challengeSpawn != null) {
            return ToaLayout.roomCoord(raid, room, challengeSpawn)
        }
        val offset =
            room.playerSpawn.translate(
                random.of(0, room.spawnRandomX),
                random.of(0, room.spawnRandomZ),
            )
        return ToaLayout.roomCoord(raid, room, offset)
    }

    /**
     * NR `player.reset()` + unskull at `sendDeath` tick 5: prayers off, toxins cured, death
     * timers reset, skull removed, all stats restored. Deliberately does NOT run the standard
     * death cleanup hooks — `InstanceDeathCleanupHook` would evict the occupant.
     */
    private fun ProtectedAccess.resetRaidDeathState() {
        player.disablePrayers()
        player.cureAllToxins()
        player.deathResetTimers()
        player.skullIcon = null
        statRestoreAll(allStatNames())
    }

    private fun allStatNames(): List<String> =
        ServerCacheManager.getStats().values.map { RSCM.getReverseMapping(RSCMType.STAT, it.id) }

    private companion object {
        /** The standard death queue re-queued by hits landing mid-sequence (cleared). */
        private const val QUEUE_STANDARD_DEATH: String = "queue.death"

        /** Standard player death animation (NR `Player.DEATH_ANIMATION`). */
        private const val SEQ_DEATH: String = "seq.human_death"

        /** Marks a death sequence queued/mid-flight so re-deaths are swallowed, not doubled. */
        private val SEQUENCE_ACTIVE: AttributeKey<Boolean> = AttributeKey(temp = true)
    }
}

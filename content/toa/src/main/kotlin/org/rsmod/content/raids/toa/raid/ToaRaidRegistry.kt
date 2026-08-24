package org.rsmod.content.raids.toa.raid

import jakarta.inject.Singleton
import org.rsmod.api.instances.InstanceId
import org.rsmod.game.entity.Player

/**
 * Registry of active Tombs of Amascut raids, keyed by [InstanceId] with a per-player uuid
 * index.
 *
 * Port of NR/Zenyte's static `TOALobbyParty.RAID_PARTIES` list + the per-player
 * `TOAManager.raidParty` back-reference (`com.zenyte.game.content.tombsofamascut`), replaced
 * by an injectable singleton per the OpenRune architecture (the instance framework has no
 * session attribute bag — see or-instances §11.5).
 *
 * The player index covers every ROSTER member — including logged-out members — so
 * `tryRejoin` can find the raid by uuid; a voluntary leaver is detached explicitly. All
 * bookkeeping is the raid controller's responsibility; this class only stores and looks up.
 */
@Singleton
internal class ToaRaidRegistry {
    private val byInstance = HashMap<InstanceId, ToaRaid>()
    private val byPlayer = HashMap<Long, ToaRaid>()

    /** Registers [raid] and indexes every current roster member's uuid. */
    fun register(raid: ToaRaid) {
        byInstance[raid.instanceId] = raid
        for (member in raid.originalMembers) {
            byPlayer[member.uuid] = raid
        }
    }

    /**
     * Removes [raid] and every player-index entry pointing at it. Called from the raid
     * controller's `onInstanceEnded` cleanup. Returns the removed raid, or null.
     */
    fun remove(raid: ToaRaid): ToaRaid? = remove(raid.instanceId)

    /** Removes the raid registered for [instanceId] (see [remove]). */
    fun remove(instanceId: InstanceId): ToaRaid? {
        val removed = byInstance.remove(instanceId) ?: return null
        byPlayer.entries.removeAll { it.value === removed }
        return removed
    }

    fun forInstance(instanceId: InstanceId): ToaRaid? = byInstance[instanceId]

    fun forPlayer(uuid: Long): ToaRaid? = byPlayer[uuid]

    fun forPlayer(player: Player): ToaRaid? = player.uuid?.let(byPlayer::get)

    /** Indexes [uuid] to [raid] — for a member (re)joining after registration. */
    fun attachPlayer(uuid: Long, raid: ToaRaid) {
        byPlayer[uuid] = raid
    }

    /** Drops [uuid]'s index entry — for a voluntary leave (forfeits rejoin lookup). */
    fun detachPlayer(uuid: Long) {
        byPlayer.remove(uuid)
    }

    fun all(): Collection<ToaRaid> = byInstance.values
}

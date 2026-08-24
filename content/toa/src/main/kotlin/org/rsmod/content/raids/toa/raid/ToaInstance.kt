package org.rsmod.content.raids.toa.raid

import jakarta.inject.Inject
import org.rsmod.api.instances.BossInstanceRegistry
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceEnterTransition
import org.rsmod.api.instances.InstanceScript
import org.rsmod.api.instances.withInstanceLeaveTransition
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.content.other.consumables.potion.toa.ToaPotionEffect
import org.rsmod.content.raids.toa.hud.ToaHud
import org.rsmod.content.raids.toa.lobby.ToaPartyInterfaces
import org.rsmod.content.raids.toa.party.ToaPartyRegistry
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.map.Direction
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Tombs of Amascut instance script: registers the raid's instance spec (one large region
 * holding all twelve rooms — see [ToaLayout]) and wires the instance-framework hooks into the
 * raid flow.
 *
 * Port of the instance-side plumbing of NR/Zenyte `TOARaidEntryAction.java` +
 * `TOAManager.enterRaid` (raid entry object routing), `TOAEntranceAction.java` (lobby exit
 * teleport) and the area teardown responsibilities of `TOARaidArea.java`/`TOARaidParty.java`
 * (`com.zenyte.game.content.tombsofamascut[.lobby|.raid]`).
 *
 * Both the enter object (`loc.toa_lobby_raid_entry`, 46089) and exit object
 * (`loc.toa_lobby_exit`, 46087) get custom hooks: the framework's default create/join menu and
 * leave flow are wrong for a party raid — entry routes through the lobby party flow
 * ([ToaRaidController.startRaid] is the only instance touchpoint), and the "exit" object is
 * the LOBBY exit to the surface, not a raid exit.
 */
internal class ToaInstance
@Inject
constructor(
    private val bossRegistry: BossInstanceRegistry,
    private val controller: ToaRaidController,
    private val raidRegistry: ToaRaidRegistry,
    private val partyRegistry: ToaPartyRegistry,
    private val partyInterfaces: ToaPartyInterfaces,
    private val playerList: PlayerList,
    private val potionEffects: ToaPotionEffect,
) : InstanceScript(bossRegistry) {

    override fun settingsRow(): String = ToaConstants.SETTINGS_ROW

    /**
     * The raid area: every room composed into one large region template. Enter/exit coords are
     * left empty here — the DB row's `enter_coord` (3551,5161 — the main hall/nexus) and
     * `exit_coord` (3358,9113 — outside the entrance) fill them via `withDbCoords`. No spec
     * npc spawns: rooms spawn their own NPCs on room start (later sessions).
     */
    override fun area(): InstanceArea = InstanceArea.template(ToaLayout.template())

    /** The last leaver destroys the session — raid teardown per the Session 1 design. */
    override fun destroyWhenEmpty(): Boolean = true

    /**
     * Replaces the base registration to flag the spec with `customDeathHandling`: the instance
     * framework must NOT remove dying occupants ([org.rsmod.api.instances.InstanceManager]
     * `handleDeath` skip) — the ToA death flow (ghosts, wipes) owns deaths.
     */
    override fun ScriptContext.startup() {
        configure()
        bossRegistry.register(key, buildSpec().copy(customDeathHandling = true))
    }

    override fun ScriptContext.configure() {
        onEnterObject { raidEntry() }
        onExitObject { lobbyExit() }
        onPlayerQueueWithArgs<ToaEntryArgs>(ToaRaidController.QUEUE_RAID_ENTRY) {
            with(controller) { runQueuedEntry(it.args) }
        }
        onPlayerQueueWithArgs<ToaWipeArgs>(ToaRaidController.QUEUE_ROOM_WIPE) {
            with(controller) { runQueuedWipe(it.args) }
        }
        onInstanceEnded {
            val raid = raidRegistry.forInstance(instanceId) ?: return@onInstanceEnded
            // Only CURRENT members whose registry entry still points at THIS raid get the
            // teardown side effects — playerStates keeps entries for everyone who ever
            // raided here, and a long-gone leaver may be mid-way through another raid.
            for (member in raid.activeMembers.toList()) {
                if (raidRegistry.forPlayer(member.uuid) !== raid) {
                    continue
                }
                val player = playerList.firstOrNull { it.uuid == member.uuid } ?: continue
                potionEffects.clearSessionEffects(player)
                ToaHud.close(player)
            }
            raidRegistry.remove(instanceId)
        }
    }

    /**
     * Option-1 on the raid entry (46089), replacing the framework's default instance menu:
     * rejoin a live raid if entitled, otherwise route through the lobby party flow — only the
     * party leader may start the raid (NR `TOARaidEntryAction` + `TOAManager.enterRaid`).
     */
    private suspend fun ProtectedAccess.raidEntry() {
        val uuid = player.uuid ?: return
        if (manager.sessionForPlayer(player) != null) {
            mes("You are already inside an instance.")
            return
        }
        val activeRaid = raidRegistry.forPlayer(uuid)
        if (activeRaid != null) {
            if (!controller.tryRejoin(player)) {
                mes("You are unable to rejoin your party.")
            }
            return
        }
        val party = partyRegistry.currentParty(player)
        if (party == null) {
            // NR `TOARaidEntryAction`: an OptionDialogue whose first option simulated
            // clicking the grouping obelisk (opening the party overview list).
            val formParty =
                choice2(
                    "Form or join a party.",
                    true,
                    "Cancel.",
                    false,
                    title = "You are currently not in a raiding party.",
                )
            if (formParty) {
                partyInterfaces.openPartyList(this)
            }
            return
        }
        if (party.leader.uuid != uuid) {
            mesbox("Your leader, ${party.leader.name}, must enter first.")
            return
        }
        controller.startRaid(player, party)
    }

    /**
     * Option-1 on the lobby exit (46087): fade and teleport back to the surface, facing
     * north-west (NR `TOAEntranceAction`). This loc is in the static lobby — it never leaves
     * an instance, so the framework's default leave flow must not run.
     */
    private suspend fun ProtectedAccess.lobbyExit() {
        withInstanceLeaveTransition(InstanceEnterTransition()) {
            telejump(ToaConstants.SURFACE_EXIT)
            faceDirection(Direction.NorthWest)
        }
    }
}

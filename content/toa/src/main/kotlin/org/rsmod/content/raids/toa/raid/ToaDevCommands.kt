package org.rsmod.content.raids.toa.raid

import dev.or2.central.account.Rights
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Admin-only raid debug command (`::toaroom`) for exercising the raid loop before the room
 * content exists: without bosses or puzzles, a room challenge cannot start or complete
 * normally, which would make the Session 2 main-hall logic (Walk the Path rolls, door states,
 * supply spirit, Wardens entry) untestable on a real client.
 *
 * NOT an NR port — a development aid only; remove or gate behind a dev flag for production.
 */
internal class ToaDevCommands
@Inject
constructor(
    private val controller: ToaRaidController,
    private val registry: ToaRaidRegistry,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {

    override fun ScriptContext.startup() {
        onCommand("toaroom") {
            desc = "ToA raid-loop debug: start/complete/advance/goto the current room"
            requiredRights = Rights.ADMINISTRATOR
            invalidArgs = USAGE
            cheat(::toaRoom)
        }
    }

    private fun toaRoom(cheat: Cheat) {
        val player = cheat.player
        val uuid = player.uuid ?: return
        val raid = registry.forPlayer(uuid)
        if (raid == null) {
            player.mes("You are not in a ToA raid.")
            return
        }
        when (cheat.args.firstOrNull()?.lowercase()) {
            "start" -> {
                controller.startRoom(raid)
                player.mes("ToA debug: room=${raid.currentRoom} stage=${raid.stage}.")
            }
            "complete" -> {
                if (raid.stage == ToaRoomStage.NOT_STARTED) {
                    controller.startRoom(raid)
                }
                controller.completeRoom(raid)
                player.mes(
                    "ToA debug: completed ${raid.currentRoom}; " +
                        "paths done=${raid.pathsCompleted.map { it.properName }}."
                )
            }
            "advance" -> {
                controller.advanceRaid(raid)
                player.mes("ToA debug: party room is now ${raid.currentRoom}.")
            }
            "goto" -> {
                protectedAccess.launch(player) {
                    with(controller) { transitionTo(raid, raid.currentRoom) }
                }
            }
            "info" -> {
                player.mes(
                    "ToA debug: room=${raid.currentRoom} stage=${raid.stage} " +
                        "level=${raid.raidLevel} started=${raid.startedPath} " +
                        "path=${raid.currentPath} " +
                        "pathLevels=${raid.pathLevels.toList()} " +
                        "done=${raid.pathsCompleted.map { it.properName }}"
                )
            }
            else -> player.mes(USAGE)
        }
    }

    private companion object {
        const val USAGE: String = "Use as ::toaroom [start|complete|advance|goto|info]"
    }
}

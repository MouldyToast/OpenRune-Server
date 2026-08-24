package org.rsmod.content.raids.toa.death

import jakarta.inject.Inject
import org.rsmod.api.death.PlayerDeathSequenceHook
import org.rsmod.api.script.onPlayerQueue
import org.rsmod.plugin.module.PluginModule
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Script + DI wiring for the Tombs of Amascut death flow.
 *
 * Port of the registration side of NR/Zenyte `TOARaidArea.java`'s `DeathPlugin` membership
 * (`com.zenyte.game.content.tombsofamascut.raid`): where NR overrode `sendDeath` on the raid
 * area, OpenRune's single `"queue.death"` handler cannot be duplicated — instead
 * [ToaDeathSequence] is set-bound as a [PlayerDeathSequenceHook] (consulted by
 * `org.rsmod.api.death.PlayerDeath.death` BEFORE the standard sequence) via [ToaDeathModule],
 * and this script drives the claimed deaths through the [QUEUE_TOA_DEATH] player queue.
 */
internal class ToaDeathScript
@Inject
constructor(private val sequence: ToaDeathSequence) : PluginScript() {

    override fun ScriptContext.startup() {
        onPlayerQueue(QUEUE_TOA_DEATH) { with(sequence) { runDeathSequence() } }
    }

    companion object {
        /** Player queue running the ToA death sequence (module gamevals.toml alias). */
        const val QUEUE_TOA_DEATH: String = "queue.toa_death"
    }
}

/**
 * Registers [ToaDeathSequence] on the `PlayerDeathSequenceHook` set binding (declared empty
 * by default in `org.rsmod.api.death.plugin.DeathDropHooksModule`).
 */
internal class ToaDeathModule : PluginModule() {
    override fun bind() {
        addSetBinding<PlayerDeathSequenceHook>(ToaDeathSequence::class.java)
    }
}

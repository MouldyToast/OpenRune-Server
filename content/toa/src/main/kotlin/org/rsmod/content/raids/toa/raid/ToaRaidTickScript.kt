package org.rsmod.content.raids.toa.raid

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Drives [ToaRaidController.tick] for every active raid each game cycle (late cycle, matching
 * the instance framework's own `InstanceTickScript` pattern).
 *
 * Port of the per-cycle side of NR/Zenyte `TOAManager.refreshTimer` (`com.zenyte.game.content
 * .tombsofamascut`): NR pushed the speedrun clientscript on demand from `enter`/`sendHud`;
 * this port re-syncs it every cycle from a single engine-driven tick, per the Session 1
 * design's controller-owned raid timer (the instance spec's `timeLimitTicks` is never used —
 * its grace flow despawns NPCs).
 */
internal class ToaRaidTickScript
@Inject
constructor(
    private val controller: ToaRaidController,
    private val registry: ToaRaidRegistry,
) : PluginScript() {

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.LateCycle> {
            for (raid in registry.all().toList()) {
                controller.tick(raid)
            }
        }
    }
}

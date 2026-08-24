package org.rsmod.content.raids.toa.hud

import jakarta.inject.Inject
import org.rsmod.api.instances.events.InstancePlayerJoinEvent
import org.rsmod.api.instances.events.InstancePlayerLeaveEvent
import org.rsmod.api.instances.events.instanceEventId
import org.rsmod.api.script.onEvent
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.content.raids.toa.raid.ToaRaidRegistry
import org.rsmod.events.EventBus
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Boot wiring for [ToaHud] plus the instance-event safety net that keeps every member's HUD in
 * sync on raid membership changes.
 *
 * Port of the HUD-refresh call sites of NR/Zenyte `TOARaidArea.java` (`enter()` area hook →
 * `sendHud()` + all-party `refreshHudStates()`; `leave()` → overlay close + all-party refresh)
 * and `TOARaidParty.add/leave` refresh fan-outs
 * (`com.zenyte.game.content.tombsofamascut.raid`).
 *
 * Per the Session 1 design the raid controller drives HUD updates directly through the plain
 * [ToaHud] functions on every state change it owns (entry, transitions, deaths, wipes,
 * leaves); this script only handles the paths the controller cannot see synchronously:
 * - `InstancePlayerJoinEvent` (keyed on the `"toa"` instance key) — fired by
 *   `InstanceManager.finalizeEntry`, i.e. fresh entries AND login rejoins: restores the
 *   joiner's HUD state (raid level, points varp, slots) and re-fans the member list out to the
 *   whole party.
 * - `InstancePlayerLeaveEvent` — fired on leave, logout, eviction and session destroy: closes
 *   the leaver's overlay and refreshes the remaining members' slots. This handler is HUD-only;
 *   the raid-STATE reconciliation for framework-owned removals (logout races, teleport-out
 *   evictions) is `ToaLogoutScript`'s `InstancePlayerLeaveUnboundEvent` handler.
 *
 * The `InstanceEndedEvent` for the same key is owned by `ToaInstance` (one keyed handler per
 * event type per key), which already closes every member's HUD on teardown.
 */
internal class ToaHudScript
@Inject
constructor(
    private val eventBus: EventBus,
    private val playerList: PlayerList,
    private val registry: ToaRaidRegistry,
) : PluginScript() {

    override fun ScriptContext.startup() {
        ToaHud.init(eventBus, playerList)
        onEvent<InstancePlayerJoinEvent>(instanceEventId(ToaConstants.INSTANCE_KEY)) {
            val raid = registry.forInstance(instanceId) ?: return@onEvent
            ToaHud.refreshMember(raid, player)
            ToaHud.refreshParty(raid)
        }
        onEvent<InstancePlayerLeaveEvent>(instanceEventId(ToaConstants.INSTANCE_KEY)) {
            ToaHud.close(player)
            val raid = registry.forInstance(instanceId) ?: return@onEvent
            ToaHud.refreshParty(raid)
        }
    }
}

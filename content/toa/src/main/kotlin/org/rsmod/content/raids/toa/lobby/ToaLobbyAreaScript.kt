package org.rsmod.content.raids.toa.lobby

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.script.onPlayerCoordsChanged
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.content.raids.toa.party.ToaPartyRegistry
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tombs of Amascut lobby area enter/exit behavior.
 *
 * Port of NR/Zenyte `TOALobbyArea.java` (`com.zenyte.game.content.tombsofamascut.lobby`):
 * entering the lobby opens the party overlay (interface 773) reset to empty; leaving it (to
 * anywhere but the raid itself) withdraws any outstanding application and removes the player
 * from their lobby party; logging out does the same.
 *
 * Area-trigger choice (documented per the Session 1 design brief): OpenRune's `onArea`/
 * `onAreaExit` labels come from cache-packed map area definitions and no `area.*` gameval
 * covers the ToA lobby, so this uses the per-tick `PlayerMovementEvent.CoordsMovedEvent`
 * (published only for players OUTSIDE the instancing working area) with a rectangle check on
 * NR's polygon bounds (3340,9100)-(3377,9132). That event never fires inside the raid
 * instance, which reproduces NR's "walking into the raid keeps the party intact" rule for
 * free; returning players re-acquire the overlay through the
 * `ToaPartyInterfaces.ensureLobbyOverlay` self-heal.
 */
internal class ToaLobbyAreaScript
@Inject
constructor(
    private val registry: ToaPartyRegistry,
    private val interfaces: ToaPartyInterfaces,
) : PluginScript() {

    override fun ScriptContext.startup() {
        onPlayerCoordsChanged { onMoved(player, lastKnownCoords) }
        onPlayerLogout { onLogout(player) }
    }

    private fun onMoved(player: Player, lastKnownCoords: CoordGrid) {
        val wasInside = insideLobby(lastKnownCoords)
        val isInside = insideLobby(player.coords)
        when {
            isInside && !wasInside -> interfaces.onLobbyEnter(player)
            isInside -> {
                interfaces.ensureLobbyOverlay(player)
                // Lazily detect "our leader started the raid" (see syncRaidState KDoc).
                player.uuid?.let { uuid ->
                    registry.currentParty(uuid)?.let(interfaces::syncRaidState)
                }
            }
            wasInside -> onLobbyExit(player, logout = false)
        }
    }

    private fun onLogout(player: Player) {
        onLobbyExit(player, logout = true)
        player.uuid?.let(registry::clearPlayerState)
    }

    /**
     * NR `TOALobbyArea.leave`: close the overlay, withdraw any application (refreshing that
     * party leader's screen), leave the current party (with the removal message and, for
     * non-leader leavers, a leader-screen refresh), then clear the viewing/applied pointers.
     */
    private fun onLobbyExit(player: Player, logout: Boolean) {
        val uuid = player.uuid ?: return
        if (!logout) {
            interfaces.closeLobbyOverlay(player)
        }
        val applied = registry.appliedParty(uuid)
        if (applied != null) {
            interfaces.withdrawFrom(applied, uuid)
            interfaces.refreshLeaderIfViewing(applied)
        }
        val current = registry.currentParty(uuid)
        if (current != null) {
            val wasLeader = current.isLeader(uuid)
            interfaces.leaveParty(current, uuid, resetOverlay = !logout)
            if (!logout) {
                player.mes("You have left the lobby, so you have been removed from your party.")
            }
            if (!wasLeader) {
                interfaces.refreshLeaderIfViewing(current)
            }
        }
        registry.setViewingParty(uuid, null)
        registry.setAppliedParty(uuid, null)
    }

    private fun insideLobby(coords: CoordGrid): Boolean = ToaPartyInterfaces.insideLobby(coords)
}

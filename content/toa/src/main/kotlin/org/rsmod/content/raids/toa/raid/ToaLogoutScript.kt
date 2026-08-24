package org.rsmod.content.raids.toa.raid

import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.instances.events.InstancePlayerLeaveUnboundEvent
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Logout/login lifecycle for raid members.
 *
 * Port of NR/Zenyte `TOARaidArea.onLogout` + `TOAManager.onLogin` + `TOAPlayerLogoutState.java`
 * (`com.zenyte.game.content.tombsofamascut[.raid]`):
 * - Logout inside a raid snapshots a rejoin state and — mid-challenge — counts as a death
 *   ([ToaRaidController.onPlayerLogout], which also runs the wipe check).
 * - Login auto-rejoins the raid when it is still alive and the player kept their roster entry
 *   ([ToaRaidController.tryRejoin]); otherwise the NR "unable to rejoin" fallout applies —
 *   a mid-challenge logout in a limited-attempts raid that cannot be rejoined is a raid loss.
 *
 * Interplay with the instance framework: `InstanceLifecycleScript` independently calls
 * `InstanceManager.handleLogout`, which removes the occupant (destroying the session via
 * `destroyWhenEmpty` when they were the last one) and persists
 * `InstanceAttributes.LOGIN_EXIT_COORD` = the DB exit coord (3358,9113). The next login
 * therefore always starts OUTSIDE the raid entrance — NR's `forceLocation(outside)`
 * equivalent — and the rejoin flow telejumps back in from there.
 *
 * `InstanceLifecycleScript` (name-sorted before this script by the plugin loader) runs its
 * `SessionStateEvent.Logout` handler FIRST — and for the last occupant it synchronously
 * destroys the session, clearing [ToaRaidRegistry] before this script's own logout handler
 * can read it. The logout bookkeeping therefore hooks [InstancePlayerLeaveUnboundEvent],
 * which `InstanceManager.removeOccupant` publishes while the raid is still registered; the
 * same hook reconciles raid state for framework evictions (a member teleporting out of the
 * region bounds — nothing gates spell teleports inside the raid), mirroring NR's raid-area
 * leave hook via [ToaRaidController.onPlayerEvicted]. The plain [onPlayerLogout] handler
 * remains for members who log out before ever becoming occupants (e.g. during the entry
 * fade); both paths guard on active membership, so they are mutually idempotent.
 */
internal class ToaLogoutScript
@Inject
constructor(
    private val controller: ToaRaidController,
    private val registry: ToaRaidRegistry,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {

    override fun ScriptContext.startup() {
        onEvent<InstancePlayerLeaveUnboundEvent> {
            if (key != ToaConstants.INSTANCE_KEY) {
                return@onEvent
            }
            val raid = registry.forInstance(instanceId) ?: return@onEvent
            reconcileInstanceLeave(player, raid)
        }
        onPlayerLogout {
            val raid = registry.forPlayer(player) ?: return@onPlayerLogout
            writeLogoutAttributes(player, raid)
            controller.onPlayerLogout(player, raid)
        }
        onPlayerLogin { handleLogin(player) }
    }

    /**
     * Reconciles raid state when the instance framework removes an occupant on a path the
     * module does not own (see the class KDoc): a logging-out member gets the full logout
     * flow while the raid is still registered; anyone else was evicted (left the region
     * bounds) and is handed to [ToaRaidController.onPlayerEvicted]. Members already removed
     * by a ToA-owned path (`leaveRaid`/`failRaid`, or an earlier-run logout handler) fail
     * the membership check and are skipped.
     */
    private fun reconcileInstanceLeave(player: Player, raid: ToaRaid) {
        val uuid = player.uuid ?: return
        if (raid.memberByUuid(uuid) == null) {
            return
        }
        if (player.loggingOut || player.pendingLogout) {
            writeLogoutAttributes(player, raid)
            controller.onPlayerLogout(player, raid)
        } else {
            controller.onPlayerEvicted(player, raid)
        }
    }

    private fun writeLogoutAttributes(player: Player, raid: ToaRaid) {
        val uuid = player.uuid ?: return
        if (raid.memberByUuid(uuid) == null) {
            return
        }
        val room = raid.playerState(uuid)?.currentRoom ?: return
        val duringChallenge =
            raid.stage == ToaRoomStage.STARTED &&
                room == raid.currentRoom &&
                controller.insideChallengeArea(raid, room, player.coords)
        player.attr[ToaLogoutAttributes.PENDING_REJOIN] = true
        player.attr[ToaLogoutAttributes.LOGOUT_DURING_CHALLENGE] = duringChallenge
        player.attr[ToaLogoutAttributes.LOGOUT_SAFE_DEATH] = raid.permittedTeamDeaths == -1
    }

    @OptIn(InternalApi::class)
    private fun handleLogin(player: Player) {
        if (player.attr[ToaLogoutAttributes.PENDING_REJOIN] != true) {
            return
        }
        val duringChallenge = player.attr[ToaLogoutAttributes.LOGOUT_DURING_CHALLENGE] == true
        val safeDeath = player.attr[ToaLogoutAttributes.LOGOUT_SAFE_DEATH] == true
        player.attr.remove(ToaLogoutAttributes.PENDING_REJOIN)
        player.attr.remove(ToaLogoutAttributes.LOGOUT_DURING_CHALLENGE)
        player.attr.remove(ToaLogoutAttributes.LOGOUT_SAFE_DEATH)
        protectedAccess.launchLenient(player) {
            // cs2: fade_overlay — clears a stuck fade from the logout (NR `onLogin`).
            runClientScript(ToaConstants.CS2_FADE_OVERLAY, 0, 0, 0, 255, 50)
            if (controller.tryRejoin(player)) {
                return@launchLenient
            }
            if (duringChallenge && !safeDeath) {
                // NR-PARITY: NR routes the player's items into the TOMBS_OF_AMASCUT retrieval
                // chest here (`triggerTOAFailure(true)`); the retrieval service is out of
                // Session 1 scope — only the failure messaging/jingle is ported.
                mes("You failed to survive the Tombs of Amascut.")
                midiJingle(JINGLE_RAID_FAIL)
            } else {
                mes("You were unable to rejoin your party.")
            }
        }
    }

    private companion object {
        /**
         * Raid failure jingle. NR played its cache's raw jingle 90; the stock rev240 cache
         * has no ToA-failure jingle, so the generic minigame-loss jingle (968) substitutes.
         */
        private const val JINGLE_RAID_FAIL: String = "jingle.game_lose"
    }
}

/**
 * Persisted logout markers consumed at the next login. Port of the persistence role of NR
 * `TOAPlayerLogoutState` (stored on `TOAPlayerData`): the full snapshot (room/coords) lives
 * raid-side on [ToaPlayerState.logoutState] for rejoin placement; these small flags survive
 * the raid itself so the "unable to rejoin" fallout can still apply after the session died.
 */
internal object ToaLogoutAttributes {
    val PENDING_REJOIN: AttributeKey<Boolean> = AttributeKey(persistenceKey = "toa_pending_rejoin")

    val LOGOUT_DURING_CHALLENGE: AttributeKey<Boolean> =
        AttributeKey(persistenceKey = "toa_logout_during_challenge")

    val LOGOUT_SAFE_DEATH: AttributeKey<Boolean> =
        AttributeKey(persistenceKey = "toa_logout_safe_death")
}

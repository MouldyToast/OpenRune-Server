package org.rsmod.content.raids.toa.lobby

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.game.map.Direction
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tombs of Amascut lobby loc interactions: the grouping obelisk, the invocation noticeboard,
 * the overworld entrance teleport and the bank-camel grain sack.
 *
 * Port of NR/Zenyte `GroupingObeliskAction.java`, `InvocationBoardAction.java`,
 * `TOAEntranceAction.java` (entrance half) and `SackAction.java`
 * (`com.zenyte.game.content.tombsofamascut.lobby`).
 *
 * NOT bound here:
 * - The raid entry (46089 `toa_lobby_raid_entry`) and the lobby exit (46087 `toa_lobby_exit`)
 *   are the instance settings row's enter/exit objects — the instance framework
 *   (`InstanceCreateScript`) already binds their op1 and dispatches to the custom hooks in
 *   `ToaInstance` (`.raid` package), which own the NR `TOARaidEntryAction`/`enterRaid` and
 *   exit-teleport ports. Binding them again here would duplicate the event key at boot.
 *   The lobby-side party bookkeeping for a started raid is handled reactively by
 *   `ToaPartyInterfaces.syncRaidState`.
 */
internal class ToaLobbyScript
@Inject
constructor(private val interfaces: ToaPartyInterfaces) : PluginScript() {

    override fun ScriptContext.startup() {
        // 46068 toa_grouping_board, op1 "Inspect" (NR GroupingObeliskAction).
        onOpLoc1(ToaConstants.LOC_GROUPING_BOARD) { obelisk() }
        // 46073 toa_invocation_board, op1 "Read" (NR InvocationBoardAction).
        onOpLoc1(ToaConstants.LOC_INVOCATION_BOARD) { invocationBoard() }
        // 46077 toa_sack_full, op1 "Search" (NR SackAction).
        onOpLoc1(LOC_SACK) { sack() }
        // 45360 toa_scabaras_memorygame_button5 — NR's MatchPlateAction lived in the lobby
        // package but is the Scabaras memory-game pressure plate: outside a Scabaras
        // encounter NR did nothing, and the encounter is a later session. Stub binding so
        // the click is consumed exactly like NR's out-of-area path.
        onOpLoc1(LOC_MATCH_PLATE) { matchPlate() }
        // 44596 toa_entrance is a multiloc shell; loc op events key on the VISIBLE variant,
        // 44010 toa_entrance_open (op1 "Enter") — overworld -> lobby (NR TOAEntranceAction).
        onOpLoc1(LOC_ENTRANCE_OPEN) { fadeTeleport(ToaConstants.LOBBY_ENTRY, Direction.South) }
        // Stock-cache adaptation: the overworld entrance only shows its "Enter" op while
        // varbit toa_entrance_open is set (multiloc); NR dispatched loc ops server-side on
        // the base id and never needed it. Flag the entrance open once per player.
        onPlayerLogin {
            if (player.vars[VARBIT_ENTRANCE_OPEN] == 0) {
                VarPlayerIntMapSetter.set(player, VARBIT_ENTRANCE_OPEN, 1)
            }
        }
    }

    /** NR `GroupingObeliskAction`: opens the party overview (interface 772). */
    private suspend fun ProtectedAccess.obelisk() {
        arriveDelay()
        interfaces.openPartyList(this)
    }

    /** NR `InvocationBoardAction`: read-only interface 776 (content is clientside). */
    private suspend fun ProtectedAccess.invocationBoard() {
        arriveDelay()
        ifOpenMainModal(IF_INVOCATIONS)
    }

    /**
     * NR `SackAction`: 1/21 chance of "taking grain" from the bank camel's sack. Message-only
     * stub per the Session 1 design brief — NR added `grain` (1947; the authentic OSRS item
     * would be 27225 `toa_grain`) via `addOrDrop`, which a later session can wire in.
     */
    private suspend fun ProtectedAccess.sack() {
        arriveDelay()
        if (random.of(SACK_CHANCE) != 0) {
            mesbox(
                "You go to search the sack, but the bank camel glares at you menacingly and " +
                    "spits in your direction"
            )
        } else {
            mesbox("You successfully take some grain while the bank camel isn't looking.")
        }
    }

    /**
     * NR `MatchPlateAction`: delegates to `ScabarasEncounter.handleMatchPlate` when inside
     * the Scabaras encounter, else does nothing. The encounter is a later session — the
     * out-of-area no-op is all that applies today.
     */
    private suspend fun ProtectedAccess.matchPlate() {
        arriveDelay()
        // Session 1 stub: Scabaras room wiring (nr_toa_room_kephri/ScabarasEncounter) lands
        // with the Kephri-path session.
    }

    /**
     * NR `TOAEntranceAction`: fade to black, teleport, face [face], unfade two ticks later.
     */
    private suspend fun ProtectedAccess.fadeTeleport(dest: CoordGrid, face: Direction) {
        arriveDelay()
        fadeOverlay(
            startColour = 0,
            startTransparency = 0,
            endColour = 0,
            endTransparency = 255,
            clientDuration = FADE_CLIENT_DURATION,
        )
        delay(1)
        telejump(dest)
        faceDirection(face)
        delay(2)
        closeFadeOverlay()
    }

    private companion object {
        /** 46077 — the bank camel's grain sack (NR `SackAction`). */
        const val LOC_SACK: String = "loc.toa_sack_full"

        /** 45360 — Scabaras memory-game plate (NR `MatchPlateAction`, misfiled in lobby). */
        const val LOC_MATCH_PLATE: String = "loc.toa_scabaras_memorygame_button5"

        /** 776 — read-only invocation noticeboard interface. */
        const val IF_INVOCATIONS: String = "interface.toa_invocations"

        /** 44010 — op-bearing multiloc variant of `toa_entrance` (44596). */
        const val LOC_ENTRANCE_OPEN: String = "loc.toa_entrance_open"

        /** 13837 — multiloc var opening the overworld entrance. */
        const val VARBIT_ENTRANCE_OPEN: String = "varbit.toa_entrance_open"

        /** NR `Utils.random(20)`: 0..20 inclusive, 0 = success (1/21). */
        const val SACK_CHANCE: Int = 21

        /** cs2 fade args (0, 0, 0, 255, 50) — NR `FadeScreen` equivalent. */
        const val FADE_CLIENT_DURATION: Int = 50
    }
}

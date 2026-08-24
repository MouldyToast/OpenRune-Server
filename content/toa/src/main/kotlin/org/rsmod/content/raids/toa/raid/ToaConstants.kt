package org.rsmod.content.raids.toa.raid

import org.rsmod.map.CoordGrid

/**
 * Shared Tombs of Amascut constants: instance key, lobby geometry, and the RSCM names / cs2
 * ids used across the raid packages.
 *
 * Port of NR/Zenyte `TOAConstants.java`, the static constants of `TOAManager.java`
 * (OUTSIDE_LOCATION, MAX_PARTY_MEMBERS, HUD varbit ids, clientscript ids) and the
 * `TOALobbyArea`/`TOAEntranceAction` coordinates recorded in the lobby module
 * (`com.zenyte.game.content.tombsofamascut`).
 *
 * Every RSCM name below is verified against the repo gamevals (`.data/gamevals-binary/
 * gamevals.dat`, matching the osrs-dumps symbol ids noted in comments). NR's custom
 * points varbit 3586 is NOT ported — stock OSRS transmits personal points via varp
 * `toa_personal_contribution` (see rscm-map mismatch #1).
 */
internal object ToaConstants {

    /** Instance-settings key (`InstanceSettingsTable` row "toa"). */
    const val INSTANCE_KEY: String = "toa"

    /** Instance-settings dbrow (registered in api/instances gamevals.toml). */
    const val SETTINGS_ROW: String = "dbrow.instance_toa"

    /** NR `TOAManager.MAX_PARTY_MEMBERS`. */
    const val MAX_PARTY_MEMBERS: Int = 8

    /** Max concurrent lobby parties (NR `TOALobbyParty.MAX_LOBBY_PARTIES`). */
    const val MAX_LOBBY_PARTIES: Int = 45

    // ------------------------------------------------------------------------------------
    // Lobby / outside geometry (static world — the lobby is NOT instanced).
    // ------------------------------------------------------------------------------------

    /** NR `TOAManager.OUTSIDE_LOCATION` — outside the raid entrance, in the lobby cavern. */
    val OUTSIDE: CoordGrid = CoordGrid(3358, 9113)

    /** Exit scatter: `OUTSIDE.translate(random(0..2), random(0..1))` (NR randomization). */
    const val OUTSIDE_RANDOM_X: Int = 2
    const val OUTSIDE_RANDOM_Z: Int = 1

    /** Lobby cavern bounds, inclusive (NR `TOALobbyArea` polygon (3340,9100)-(3377,9132)). */
    val LOBBY_SOUTH_WEST: CoordGrid = CoordGrid(3340, 9100)
    val LOBBY_NORTH_EAST: CoordGrid = CoordGrid(3377, 9132)

    /** Surface-side tile of the lobby entrance (NR `TOAEntranceAction`, faces NW on exit). */
    val SURFACE_EXIT: CoordGrid = CoordGrid(3357, 2713)

    /** Lobby-side tile of the entrance (NR `TOAEntranceAction`, faces S on entry). */
    val LOBBY_ENTRY: CoordGrid = CoordGrid(3359, 9128)

    /** Grouping obelisk/board location (NR `TOARaidEntryAction.OBELISK_LOCATION`). */
    val OBELISK: CoordGrid = CoordGrid(3358, 9119)

    // ------------------------------------------------------------------------------------
    // Locs (osrs-dumps loc.sym ids in comments).
    // ------------------------------------------------------------------------------------

    /** 46068 — grouping board; "inspect" opens the party list. */
    const val LOC_GROUPING_BOARD: String = "loc.toa_grouping_board"

    /** 46073 — invocation board; "read" opens the invocations interface. */
    const val LOC_INVOCATION_BOARD: String = "loc.toa_invocation_board"

    /** 44596 — surface entrance; teleports into the lobby cavern. */
    const val LOC_ENTRANCE: String = "loc.toa_entrance"

    /** 46087 — lobby exit; teleports back to the surface. */
    const val LOC_LOBBY_EXIT: String = "loc.toa_lobby_exit"

    /** 46089 — raid entry (the settings row's enter_object). */
    const val LOC_RAID_ENTRY: String = "loc.toa_lobby_raid_entry"

    // ------------------------------------------------------------------------------------
    // Npcs (osrs-dumps npc.sym ids in comments).
    // ------------------------------------------------------------------------------------

    /** 11689 — Osmumten teleport NPC spawned after room completion (NR `TELEPORT_NPC_ID`). */
    const val NPC_OSMUMTEN_TELEPORT: String = "npc.toa_osmumten_vis"

    /** 11695 — dead-player ghost transmog (NR `GHOST_PLAYER_NPC_ID`). */
    const val NPC_PLAYER_GHOST: String = "npc.toa_player_ghost"

    // ------------------------------------------------------------------------------------
    // Interfaces / varbits / varps (osrs-dumps ids in comments).
    // ------------------------------------------------------------------------------------

    /** 481 — raid HUD overlay (NR `sendHud`). */
    const val IF_HUD: String = "interface.toa_hud"

    /** 14345 — lobby party status: 0 none, 1 in party, 2 leader entered ("step inside"). */
    const val VARBIT_PARTY_STATUS: String = "varbit.toa_client_partystatus"

    /**
     * 14346-14353 — HUD member-slot states, indexed by HUD slot 0..7: 0 empty, 1-29 HP bucket
     * (`ToaScaling.hudHpBucket`), 30 ghost, 31 in a different room (NR
     * `HUD_PLAYER_LIST_BASE_VARBIT`).
     */
    val VARBIT_HUD_PLAYER_SLOTS: List<String> = List(MAX_PARTY_MEMBERS) {
        "varbit.toa_client_p$it"
    }

    /** 14354 — own HUD slot index + 1 (NR `HUD_PLAYER_ME_VARBIT`). */
    const val VARBIT_HUD_OWN_SLOT: String = "varbit.toa_client_partyslot"

    /** 14362-14369 — Apmeken "sight" markers per HUD slot (NR `HUD_PLAYER_SIGHT_VARBIT`). */
    val VARBIT_HUD_SIGHT_SLOTS: List<String> = List(MAX_PARTY_MEMBERS) {
        "varbit.toa_client_primary$it"
    }

    /**
     * 14376-14379 — HUD path-level varbits, in VARBIT DECLARATION order: crondis, scabaras,
     * het, apmeken. NOTE this is NOT [ToaPath] ordinal order (apmeken, scabaras, het,
     * crondis); NR sent `bossLevels[i]` to varbit 14376+i, which swaps the apmeken and
     * crondis displays if callers index by path ordinal (rscm-map mismatch #7). Map through
     * [pathLevelVarbit] to stay explicit.
     */
    val VARBIT_PATH_LEVELS: List<String> =
        listOf(
            "varbit.toa_client_crondis_level",
            "varbit.toa_client_scabaras_level",
            "varbit.toa_client_het_level",
            "varbit.toa_client_apmeken_level",
        )

    /** The HUD path-level varbit for [path] (resolves rscm-map mismatch #7 by name). */
    fun pathLevelVarbit(path: ToaPath): String =
        when (path) {
            ToaPath.CRONDIS -> "varbit.toa_client_crondis_level"
            ToaPath.SCABARAS -> "varbit.toa_client_scabaras_level"
            ToaPath.HET -> "varbit.toa_client_het_level"
            ToaPath.APMEKEN -> "varbit.toa_client_apmeken_level"
        }

    /** 14380 — raid level shown on the HUD (NR `HUD_RAID_LEVEL_VARBIT`). */
    const val VARBIT_RAID_LEVEL: String = "varbit.toa_client_raid_level"

    /** 14381 — HUD current-path indicator; 0 in the main hall (NR `HUD_PATH_VARBIT`). */
    const val VARBIT_CURRENT_PATH: String = "varbit.toa_client_current_path"

    /**
     * 3606 — personal points transmit varp. Replaces NR's custom varbit 3586 (which is
     * `atjun_med_teak` in the stock cache — NR ran a modified client; see rscm-map #1).
     */
    const val VARP_PERSONAL_POINTS: String = "varp.toa_personal_contribution"

    // ------------------------------------------------------------------------------------
    // Clientscripts — raw int ids (no RSCM overload for runClientScript).
    // ------------------------------------------------------------------------------------

    /** cs2: toa_speedrun_time_update — args `(elapsedTicks, finished 0|1)` (NR refreshTimer). */
    const val CS2_TIMER_UPDATE: Int = 6580

    /** cs2: toa_hud_statusnames — args = 8 member-name strings, "" for empty slots. */
    const val CS2_HUD_STATUS_NAMES: Int = 6585

    /** cs2: fade_overlay — args `(0, 0, 0, 255, 50)` clears a stuck fade on login (NR). */
    const val CS2_FADE_OVERLAY: Int = 948

    // ------------------------------------------------------------------------------------
    // Rune-mechanic modifiers (NR `TOAConstants` — used by Akkha/Wardens room code later).
    // ------------------------------------------------------------------------------------

    const val SOUL_RUNES_MOD: Int = 2
    const val CHAOS_RUNES_MOD: Int = 5
}

package org.rsmod.content.raids.toa.party

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.attr.AttributeKey
import org.rsmod.content.raids.toa.invocation.ToaPartySettings
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

/**
 * Registry of lobby-side Tombs of Amascut parties plus all per-player party pointers.
 *
 * Port of the static state of NR/Zenyte `TOALobbyParty.java` (`LOBBY_PARTIES`,
 * `PLAYER_PARTIES` snapshots, `MAX_LOBBY_PARTIES`) and the per-player pointers of
 * `AbstractTOAManager.java` (`currentParty`, `viewingParty`, `appliedParty`,
 * `currentTOAPartyManagementTab`) and the leader-owned persistent loadout of
 * `TOAManager`/`TOAPlayerData` (`partySettingData`), replaced by an injectable singleton per
 * the OpenRune architecture. Pure storage/lookups — orchestration lives in
 * `ToaPartyInterfaces`.
 *
 * Personal invocation loadouts are persisted through the player's [AttributeKey] save data
 * (three bitmap ints + the kc requirement), mirroring NR's `TOAPartySettingData` player save.
 *
 * NR-BUG-FIX: NR's `PLAYER_PARTIES` snapshot map was keyed by `Player` and never cleaned on
 * logout (leak); every per-player map here is cleared by [clearPlayerState] on logout.
 */
@Singleton
internal class ToaPartyRegistry @Inject constructor(private val playerList: PlayerList) {
    /** Parties currently listed on the grouping obelisk, in creation order. */
    private val listed = ArrayList<ToaParty>()

    private val currentByPlayer = HashMap<Long, ToaParty>()
    private val viewingByPlayer = HashMap<Long, ToaParty>()
    private val appliedByPlayer = HashMap<Long, ToaParty>()
    private val tabByPlayer = HashMap<Long, Int>()

    /**
     * Per-player snapshot of the last party list rendered to them, so a later row click maps
     * to the party that was displayed (NR `PLAYER_PARTIES` / `setPartyList`).
     */
    private val listSnapshots = HashMap<Long, List<ToaParty>>()

    /** Loaded personal (leader-owned) loadouts, keyed by uuid — same instance every call. */
    private val personalSettings = HashMap<Long, ToaPartySettings>()

    fun listedParties(): List<ToaParty> = listed

    /**
     * NR-BUG-FIX: NR's `isLobbyFull()` used `LOBBY_PARTIES.size() > 45`, allowing a 46th
     * party; capped at exactly [ToaConstants.MAX_LOBBY_PARTIES].
     */
    fun isListingFull(): Boolean = listed.size >= ToaConstants.MAX_LOBBY_PARTIES

    fun isListed(party: ToaParty): Boolean = party in listed

    /**
     * Creates a party led by [leader], adopting the leader's personal settings object as the
     * party settings (NR `TOALobbyParty(Player)` semantics), and lists it on the obelisk
     * board. Returns null when the listing is full or the leader has no uuid.
     */
    fun createParty(leader: Player, createdTick: Int): ToaParty? {
        val uuid = leader.uuid ?: return null
        if (isListingFull()) {
            return null
        }
        val member = ToaPartyMember(uuid, leader.displayName)
        val party = ToaParty(member, personalSettings(leader), createdTick)
        listed += party
        currentByPlayer[uuid] = party
        return party
    }

    /** Delists [party] from the obelisk board (NR `removeFromList`). */
    fun removeFromListing(party: ToaParty) {
        listed.remove(party)
    }

    fun currentParty(uuid: Long): ToaParty? = currentByPlayer[uuid]

    fun currentParty(player: Player): ToaParty? = player.uuid?.let(currentByPlayer::get)

    fun setCurrentParty(uuid: Long, party: ToaParty?) {
        if (party == null) currentByPlayer.remove(uuid) else currentByPlayer[uuid] = party
    }

    fun viewingParty(uuid: Long): ToaParty? = viewingByPlayer[uuid]

    fun setViewingParty(uuid: Long, party: ToaParty?) {
        if (party == null) viewingByPlayer.remove(uuid) else viewingByPlayer[uuid] = party
    }

    fun appliedParty(uuid: Long): ToaParty? = appliedByPlayer[uuid]

    fun setAppliedParty(uuid: Long, party: ToaParty?) {
        if (party == null) appliedByPlayer.remove(uuid) else appliedByPlayer[uuid] = party
    }

    /** Management-screen tab: 0 members, 1 applicants, 2 invocations, 3 summary. */
    fun tab(uuid: Long): Int = tabByPlayer[uuid] ?: 0

    fun setTab(uuid: Long, tab: Int) {
        tabByPlayer[uuid] = tab.coerceIn(0, 3)
    }

    /** Stores the rendered party-list snapshot for [uuid] (NR `setPartyList`). */
    fun setListSnapshot(uuid: Long, parties: List<ToaParty>) {
        listSnapshots[uuid] = parties
    }

    /**
     * Resolves a clicked row against the snapshot rendered to [uuid]; null when the row is out
     * of range (NR-BUG-FIX: NR's `slotId <= size` guard indexed out of bounds at `== size`) or
     * the snapshot is missing.
     */
    fun snapshotRow(uuid: Long, row: Int): ToaParty? {
        val snapshot = listSnapshots[uuid] ?: return null
        return snapshot.getOrNull(row)
    }

    /**
     * The player's personal, persistent invocation loadout (NR `TOAManager.partySettings`,
     * saved via `TOAPlayerData.partySettingData`). The same instance is returned every call so
     * a party can alias it; call [persistSettings] after mutating.
     */
    fun personalSettings(player: Player): ToaPartySettings {
        val uuid = player.uuid ?: return ToaPartySettings()
        return personalSettings.getOrPut(uuid) { loadSettings(player) }
    }

    /** Writes [player]'s personal loadout into their persistent attribute save data. */
    fun persistSettings(player: Player) {
        val uuid = player.uuid ?: return
        val settings = personalSettings[uuid] ?: return
        val bitmaps = settings.encode()
        for (i in BITMAP_ATTRS.indices) {
            player.attr[BITMAP_ATTRS[i]] = bitmaps[i]
        }
        player.attr[KC_REQUIREMENT_ATTR] = settings.kcRequirement
    }

    /** Drops every per-player entry for [uuid] — logout cleanup (fixes NR's snapshot leak). */
    fun clearPlayerState(uuid: Long) {
        currentByPlayer.remove(uuid)
        viewingByPlayer.remove(uuid)
        appliedByPlayer.remove(uuid)
        tabByPlayer.remove(uuid)
        listSnapshots.remove(uuid)
        personalSettings.remove(uuid)
    }

    /** Resolves a member identity back to an online [Player], or null when logged out. */
    fun resolve(member: ToaPartyMember): Player? = resolve(member.uuid)

    fun resolve(uuid: Long): Player? = playerList.firstOrNull { it.uuid == uuid }

    private fun loadSettings(player: Player): ToaPartySettings {
        val bitmaps = IntArray(ToaPartySettings.BITMAP_SIZE) { i -> player.attr[BITMAP_ATTRS[i]] ?: 0 }
        val settings = ToaPartySettings.decode(bitmaps)
        settings.kcRequirement = player.attr[KC_REQUIREMENT_ATTR] ?: 0
        return settings
    }

    private companion object {
        /** Persistent loadout bitmaps (NR persisted these via `TOAPlayerData`). */
        private val BITMAP_ATTRS =
            listOf(
                AttributeKey<Int>(persistenceKey = "toa_invocation_bitmap_a"),
                AttributeKey<Int>(persistenceKey = "toa_invocation_bitmap_b"),
                AttributeKey<Int>(persistenceKey = "toa_invocation_bitmap_c"),
            )

        /** Persistent kc-requirement preference (display-only, NR `kcRequirement`). */
        private val KC_REQUIREMENT_ATTR = AttributeKey<Int>(persistenceKey = "toa_kc_requirement")
    }
}

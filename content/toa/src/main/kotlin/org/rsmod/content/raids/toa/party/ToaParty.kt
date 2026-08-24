package org.rsmod.content.raids.toa.party

import org.rsmod.content.raids.toa.invocation.ToaPartySettings
import org.rsmod.content.raids.toa.raid.ToaConstants

/**
 * A lobby-side (recruiting) Tombs of Amascut party.
 *
 * Port of NR/Zenyte `TOALobbyParty.java` (`com.zenyte.game.content.tombsofamascut.lobby`) —
 * the data half. All UI side effects (overlay broadcasts, interface refreshes, dialogues) live
 * in `ToaPartyInterfaces`; registries/lookups live in [ToaPartyRegistry].
 *
 * Members are stored as [ToaPartyMember] identities (uuid + display name), never `Player`
 * references — state must survive across ticks and logouts (NR stored `Player` refs plus
 * username strings; the uuid form is the OpenRune convention).
 *
 * @property settings The party's invocation loadout. Per NR semantics this is an ALIAS of the
 *   leader's personal persistent settings object (party edits mutate the leader's saved
 *   loadout); on leader promotion it is re-homed onto the new leader's personal settings after
 *   copying the old state into them (NR `TOALobbyParty.leave`). `var` only for that re-homing.
 * @property createdTick Map-clock cycle the party was created on (NR `creationTime`, used for
 *   the party-list "age" column).
 * @property raidStarted `true` once the leader has started the raid (NR `raidParty != null` /
 *   `insideRaid()`); the party is delisted from the obelisk board at that point.
 */
internal class ToaParty(
    leader: ToaPartyMember,
    settings: ToaPartySettings,
    val createdTick: Int,
) {
    /** Live members, index 0 = leader (NR `players`). */
    val members: MutableList<ToaPartyMember> = mutableListOf(leader)

    /** Pending applicants, in application order (NR `applicants`). */
    val applicants: MutableList<ToaPartyMember> = mutableListOf()

    /** Declined applicants; may not re-apply until the leader unblocks (NR `blockedPlayers`). */
    val blocked: MutableSet<Long> = LinkedHashSet()

    var settings: ToaPartySettings = settings

    /**
     * Display name of the current leader (NR `leaderDisplayName`) — kept in sync by
     * [promoteLeader]; used in every "party of X" message even after the party empties.
     */
    var leaderDisplayName: String = leader.name

    var raidStarted: Boolean = false

    /** The current leader — `members[0]`. Only valid while the party has members. */
    val leader: ToaPartyMember
        get() = members.first()

    /** Null-safe leader accessor for teardown paths where the party may be empty. */
    val leaderOrNull: ToaPartyMember?
        get() = members.firstOrNull()

    val isFull: Boolean
        get() = members.size >= ToaConstants.MAX_PARTY_MEMBERS

    fun isLeader(uuid: Long): Boolean = members.firstOrNull()?.uuid == uuid

    fun isMember(uuid: Long): Boolean = members.any { it.uuid == uuid }

    fun isApplicant(uuid: Long): Boolean = applicants.any { it.uuid == uuid }

    fun isBlocked(uuid: Long): Boolean = uuid in blocked

    fun memberByUuid(uuid: Long): ToaPartyMember? = members.firstOrNull { it.uuid == uuid }

    fun applicantByUuid(uuid: Long): ToaPartyMember? = applicants.firstOrNull { it.uuid == uuid }

    /** Adds [member] to the roster (caller broadcasts overlay text; NR `addPlayer`). */
    fun addMember(member: ToaPartyMember) {
        if (!isMember(member.uuid)) {
            members += member
        }
    }

    /** Removes [uuid] from the roster; returns the removed member (NR `removePlayer`). */
    fun removeMember(uuid: Long): ToaPartyMember? {
        val removed = members.firstOrNull { it.uuid == uuid } ?: return null
        members.remove(removed)
        return removed
    }

    /**
     * Whether [uuid] may apply right now (NR `TOALobbyParty.apply` guards, minus the applied-
     * party bookkeeping the registry owns).
     *
     * NR-BUG-FIX: NR checked `applicants.size() <= 8`, allowing a 9th applicant; capped at
     * [ToaConstants.MAX_PARTY_MEMBERS].
     */
    fun canApply(uuid: Long): Boolean =
        !raidStarted &&
            !isApplicant(uuid) &&
            applicants.size < ToaConstants.MAX_PARTY_MEMBERS

    /** Adds [applicant] to the applicant list (caller manages the applied-party pointer). */
    fun addApplicant(applicant: ToaPartyMember) {
        if (!isApplicant(applicant.uuid)) {
            applicants += applicant
        }
    }

    /** Removes [uuid] from the applicant list; returns the removed entry (NR `withdraw`). */
    fun removeApplicant(uuid: Long): ToaPartyMember? {
        val removed = applicants.firstOrNull { it.uuid == uuid } ?: return null
        applicants.remove(removed)
        return removed
    }

    /** Updates [leaderDisplayName] to the new index-0 member (NR promotion bookkeeping). */
    fun promoteLeader() {
        leaderDisplayName = members.firstOrNull()?.name ?: leaderDisplayName
    }

    /**
     * The lobby-overlay member text: 8 lines joined with `<br>`, `-` for empty slots (NR
     * `buildPartyString`, shown on `toa_lobby:names`).
     */
    fun membersText(): String =
        (0 until ToaConstants.MAX_PARTY_MEMBERS).joinToString("<br>") { slot ->
            members.getOrNull(slot)?.name ?: "-"
        }
}

/**
 * A party member's stable identity: account uuid for lookups, display name for UI text.
 * (NR keyed on `Player` objects and username strings; see `TOALobbyParty`.)
 */
internal data class ToaPartyMember(val uuid: Long, val name: String)

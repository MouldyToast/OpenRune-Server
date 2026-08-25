package org.rsmod.content.raids.toa.lobby

import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.aconverted.interf.IfButtonOp
import dev.openrune.types.aconverted.interf.IfSubType
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.config.constants
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.output.soundSynth
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.ui.IfPauseButton
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenMainModal
import org.rsmod.api.player.ui.ifOpenOverlay
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.ui.ifSetText
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onProtectedEvent
import org.rsmod.content.raids.toa.invocation.ToaInvocation
import org.rsmod.content.raids.toa.invocation.ToaPresets
import org.rsmod.content.raids.toa.invocation.mode
import org.rsmod.content.raids.toa.party.ToaParty
import org.rsmod.content.raids.toa.party.ToaPartyMember
import org.rsmod.content.raids.toa.party.ToaPartyRegistry
import org.rsmod.content.raids.toa.raid.ToaConstants
import org.rsmod.content.raids.toa.raid.ToaRaidRegistry
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Binds the Tombs of Amascut party interface buttons.
 *
 * Port of the `attach()` maps of NR/Zenyte `TOAPartyInterface.java` (interface 772
 * `toa_partylist`) and `TOAPartyManagementInterface.java` (interface 774 `toa_partydetails`,
 * `com.zenyte.game.content.tombsofamascut.lobby`).
 *
 * The stock client routes almost every button on both screens through a hidden
 * `cc_resume_pausebutton` layer, so clicks arrive as [IfPauseButton] events (see the
 * `ResumePauseButtonHandler` framework patch) keyed by the pause layer with the `comsub`
 * distinguishing the button. The two exceptions are real `IF_BUTTON` ops: the preset slots
 * (`toa_partydetails:presets_button_click`, op1 Select / op2 Clear) and the reward-potential
 * info button (`toa_partydetails:raid_level_info`, op1).
 *
 * NOTE — slot layout: NR's client (an older revision) used slots 20-27 accept / 28-35 decline
 * / 36-79 invocations. The rev-239/240 client cs2 this repo's cache ships allocates: 0-11
 * fixed buttons, 12-19 member rows, 20-27 member move-up, 28-35 member move-down, 36-43
 * applicant accept, 44-51 applicant decline, 52+ invocation toggles (verified against
 * `[clientscript,toa_partydetails_init/kickmember/sortmember]` and `script6746`/`script6754`
 * in the osrs-dumps script dump). This port uses the modern layout.
 */
internal class ToaPartyInterfaceScript
@Inject
constructor(private val interfaces: ToaPartyInterfaces) : PluginScript() {

    override fun ScriptContext.startup() {
        onProtectedEvent<IfPauseButton>(ToaPartyInterfaces.COM_LIST_BUTTONS.asRSCM(RSCMType.COMPONENT)) {
            interfaces.onPartyListButton(this, it.comsub)
        }
        onProtectedEvent<IfPauseButton>(ToaPartyInterfaces.COM_LIST_ROWS.asRSCM(RSCMType.COMPONENT)) {
            interfaces.onPartyListRow(this, it.comsub)
        }
        onProtectedEvent<IfPauseButton>(ToaPartyInterfaces.COM_MGMT_BUTTONS.asRSCM(RSCMType.COMPONENT)) {
            interfaces.onManagementButton(this, it.comsub)
        }
        onIfModalButton(ToaPartyInterfaces.COM_MGMT_PRESETS) {
            interfaces.onPresetButton(this, it.comsub, it.op)
        }
        onIfModalButton(ToaPartyInterfaces.COM_MGMT_REWARD_INFO) { interfaces.onRewardInfo(this) }
    }
}

/**
 * All Tombs of Amascut lobby party UI logic: the lobby overlay (773 `toa_lobby`), the party
 * list (772 `toa_partylist`), the party management screen (774 `toa_partydetails`), and every
 * party mutation those screens drive.
 *
 * Port of NR/Zenyte `TOAPartyInterface.java`, `TOAPartyManagementInterface.java` and the
 * UI/broadcast half of `TOALobbyParty.java` + `TOAManager.sendEmptyPartyList` / the preset and
 * `toggleInvocation` UI flows of `TOAManager.java`
 * (`com.zenyte.game.content.tombsofamascut[.lobby]`).
 *
 * Refresh model: like NR (whose `updatePartyManagementInterface` re-sent the interface every
 * time), a refresh re-opens the modal — the interface `onload` cs2 resets the client-side row
 * state that `toa_partydetails_addmember`/`_addapplicant` append to — then replays rows, the
 * `script6729` state push, and the access masks. Refreshes of OTHER players' screens use the
 * plain [Player] send path, so they work regardless of that player's protected state.
 */
@Singleton
internal class ToaPartyInterfaces
@Inject
constructor(
    private val eventBus: EventBus,
    private val mapClock: MapClock,
    private val registry: ToaPartyRegistry,
    private val raidRegistry: ToaRaidRegistry,
    private val protectedAccess: ProtectedAccessLauncher,
) {
    // ------------------------------------------------------------------------------------
    // Lobby overlay (interface 773 toa_lobby) — NR TOALobbyArea.enter / sendEmptyPartyList.
    // ------------------------------------------------------------------------------------

    /**
     * NR `TOALobbyArea.enter`: open the overlay and reset it (party text follows on edits).
     * Opened at the passive `overlay_hud` toplevel slot — NOT the default floater slot, which
     * the stock client treats as a modal floating screen and captures keyboard/click input
     * while occupied (bank/typing dead the moment a player walked into the lobby).
     */
    fun onLobbyEnter(player: Player) {
        player.ifOpenOverlay(IF_LOBBY_OVERLAY, ToaConstants.COM_OVERLAY_TARGET, eventBus)
        sendEmptyPartyOverlay(player)
    }

    /**
     * Re-opens the overlay with correct state if it went missing (e.g. after returning from
     * the raid, where the HUD replaced it). Not an NR method — NR's area triggers re-fired
     * `enter` on every area change; the coord-watcher port needs an explicit self-heal.
     */
    fun ensureLobbyOverlay(player: Player) {
        if (player.ui.containsOverlay(IF_LOBBY_OVERLAY)) {
            return
        }
        player.ifOpenOverlay(IF_LOBBY_OVERLAY, ToaConstants.COM_OVERLAY_TARGET, eventBus)
        val party = registry.currentParty(player)
        if (party == null) {
            sendEmptyPartyOverlay(player)
        } else {
            player.ifSetText(COM_LOBBY_NAMES, party.membersText())
            setPartyStatus(player, if (party.raidStarted) STATUS_PARTY_IN_RAID else STATUS_IN_PARTY)
        }
    }

    fun closeLobbyOverlay(player: Player) {
        player.ifCloseOverlay(IF_LOBBY_OVERLAY, eventBus)
    }

    /** NR `TOAManager.sendEmptyPartyList`: eight dashes + party-status varbit 0. */
    fun sendEmptyPartyOverlay(player: Player) {
        player.ifSetText(COM_LOBBY_NAMES, EMPTY_PARTY_TEXT)
        setPartyStatus(player, STATUS_NO_PARTY)
    }

    /** Sends the member-name overlay text to every online member (NR `addPlayer` broadcast). */
    fun broadcastPartyText(party: ToaParty) {
        val text = party.membersText()
        for (member in party.members) {
            registry.resolve(member)?.ifSetText(COM_LOBBY_NAMES, text)
        }
    }

    /** `varbit.toa_client_partystatus`: 0 none / 1 in lobby party / 2 party inside the raid. */
    fun setPartyStatus(player: Player, status: Int) {
        VarPlayerIntMapSetter.set(player, ToaConstants.VARBIT_PARTY_STATUS, status)
    }

    // ------------------------------------------------------------------------------------
    // Party list (interface 772 toa_partylist) — NR TOAPartyInterface.
    // ------------------------------------------------------------------------------------

    /** NR `TOAPartyInterface.open`: clear the viewed party, then render (varp + rows). */
    fun openPartyList(access: ProtectedAccess) {
        val uuid = access.player.uuid ?: return
        registry.setViewingParty(uuid, null)
        refreshPartyList(access.player)
    }

    /**
     * NR `TOAPartyInterface.updateMembersTab`: re-open 772, send all 45 rows via cs2 6601 and
     * store the rendered snapshot so row clicks resolve against what was displayed.
     */
    fun refreshPartyList(player: Player) {
        val uuid = player.uuid ?: return
        for (party in registry.listedParties().toList()) {
            syncRaidState(party)
        }
        val inParty = registry.currentParty(uuid) != null
        // NR sends varp 3603: 0 = in a party ("My party" button active), -1 = none.
        VarPlayerIntMapSetter.set(player, VARP_MY_PARTY, if (inParty) 0 else -1)
        player.ifOpenMainModal(IF_PARTY_LIST, eventBus)
        val parties = registry.listedParties().toList()
        for (row in 0 until ToaConstants.MAX_LOBBY_PARTIES) {
            val party = parties.getOrNull(row)
            if (party == null) {
                // cs2: toa_partylist_addline — empty string clears the row.
                player.runClientScript(CS2_PARTYLIST_ADDLINE, row, "")
            } else {
                player.runClientScript(CS2_PARTYLIST_ADDLINE, row, partyLine(uuid, party))
            }
        }
        registry.setListSnapshot(uuid, parties)
        player.ifSetEvents(COM_LIST_BUTTONS, 0..2, IfEvent.PauseButton)
        player.ifSetEvents(COM_LIST_ROWS, 0 until ToaConstants.MAX_LOBBY_PARTIES, IfEvent.PauseButton)
    }

    /** 772 buttons (`toa_partylist:contents` pause slots): 0 Refresh, 1 Make Party, 2 Filter. */
    suspend fun onPartyListButton(access: ProtectedAccess, comsub: Int) {
        when (comsub) {
            LIST_SLOT_REFRESH -> refreshPartyList(access.player)
            LIST_SLOT_MAKE_PARTY -> makeParty(access)
            LIST_SLOT_FILTER -> toggleFriendsFilter(access)
        }
    }

    /** 772 "Select party" (`toa_partylist:list`, comsub = row index). */
    suspend fun onPartyListRow(access: ProtectedAccess, row: Int) {
        val uuid = access.player.uuid ?: return
        // NR-BUG-FIX: NR guarded `slotId <= size` then indexed — IOOBE at row == size.
        val party = registry.snapshotRow(uuid, row) ?: return
        syncRaidState(party)
        val current = registry.currentParty(uuid)
        if (current != null && current.raidStarted) {
            access.mesbox(MSG_JOIN_IN_TOMBS)
            return
        }
        if (!registry.isListed(party) || party.raidStarted) {
            access.mesbox(MSG_NOT_RECRUITING)
            refreshPartyList(access.player)
            return
        }
        registry.setViewingParty(uuid, party)
        refreshManagement(access.player)
    }

    private fun makeParty(access: ProtectedAccess) {
        val player = access.player
        val uuid = player.uuid ?: return
        val current = registry.currentParty(uuid)
        if (current != null) {
            registry.setViewingParty(uuid, current)
            registry.setTab(uuid, TAB_MEMBERS)
            refreshManagement(player)
            return
        }
        if (registry.isListingFull()) {
            access.mes(MSG_LOBBY_FULL)
            return
        }
        val party = registry.createParty(player, mapClock.cycle) ?: return
        // NR TOALobbyParty ctor -> addPlayer: broadcast the member text + party-status 1.
        broadcastPartyText(party)
        setPartyStatus(player, STATUS_IN_PARTY)
        registry.setViewingParty(uuid, party)
        registry.setTab(uuid, TAB_APPLICANTS)
        refreshManagement(player)
    }

    private fun toggleFriendsFilter(access: ProtectedAccess) {
        val current = access.vars[VARBIT_FRIENDS_FILTER]
        access.vars[VARBIT_FRIENDS_FILTER] = if (current == 0) 1 else 0
        // Filtering itself is clientside (varbit-driven); the server always sends all rows.
        refreshPartyList(access.player)
    }

    /**
     * One row of cs2 6601 `toa_partylist_addline`:
     * `leader|m2..m8|memberCount|kcRequirement|activeInvocations|raidLevel|mode|ageTicks|`
     * (leader wrapped in `<col=FFFFFF>` when the viewer is in that party).
     */
    private fun partyLine(viewerUuid: Long, party: ToaParty): String {
        val builder = StringBuilder()
        val viewerIsMember = party.isMember(viewerUuid)
        val leaderName = party.leaderOrNull?.name ?: party.leaderDisplayName
        builder.append(if (viewerIsMember) "<col=FFFFFF>$leaderName" else leaderName).append('|')
        for (slot in 1 until ToaConstants.MAX_PARTY_MEMBERS) {
            builder.append(party.members.getOrNull(slot)?.name ?: "").append('|')
        }
        val settings = party.settings
        builder
            .append(party.members.size).append('|')
            .append(settings.kcRequirement).append('|')
            .append(settings.activeInvocations()).append('|')
            .append(settings.raidLevel()).append('|')
            .append(settings.mode().displayName).append('|')
            .append(mapClock.cycle - party.createdTick).append('|')
        return builder.toString()
    }

    // ------------------------------------------------------------------------------------
    // Party management (interface 774 toa_partydetails) — NR TOAPartyManagementInterface.
    // ------------------------------------------------------------------------------------

    /**
     * NR `updatePartyManagementInterface`: the full refresh — re-open 774 (its onload cs2
     * resets the appended row state), member rows (cs2 6722), applicant rows (cs2 6727), the
     * master state script (cs2 6729) and the access masks. Plain [Player] path so other
     * players' screens can be refreshed too.
     */
    fun refreshManagement(player: Player) {
        val uuid = player.uuid ?: return
        val party = registry.viewingParty(uuid)
        // NR checkViewingParty: no viewable party -> fall back to the overview list.
        if (party == null || party.members.isEmpty()) {
            registry.setViewingParty(uuid, null)
            refreshPartyList(player)
            return
        }
        syncRaidState(party)
        val viewValue = viewValueFor(uuid, party)
        player.ifOpenMainModal(IF_MANAGEMENT, eventBus)
        for (slot in 0 until ToaConstants.MAX_PARTY_MEMBERS) {
            val member = party.members.getOrNull(slot)
            val text = member?.let { statString(it, uuid) } ?: ""
            // cs2: toa_partydetails_addmember — NR-PARITY: first arg is the literal 2.
            player.runClientScript(CS2_MGMT_ADDMEMBER, 2, text)
        }
        for (applicant in party.applicants) {
            // cs2: toa_partydetails_addapplicant.
            player.runClientScript(CS2_MGMT_ADDAPPLICANT, statString(applicant, uuid))
        }
        val settings = party.settings
        val bitmaps = settings.encode()
        // cs2: script6729 — partydetails master state (tab, action label, invocation marks).
        player.runClientScript(
            CS2_MGMT_STATE,
            viewValue,
            settings.kcRequirement,
            settings.activeInvocations(),
            settings.raidLevel(),
            registry.tab(uuid),
            bitmaps[0],
            bitmaps[1],
            bitmaps[2],
        )
        player.ifSetEvents(COM_MGMT_BUTTONS, 0..MGMT_SLOT_TABS_LAST, IfEvent.PauseButton)
        player.ifSetEvents(COM_MGMT_REWARD_INFO, -1..-1, IfEvent.Op1)
        if (viewValue == VIEW_LEADER) {
            player.ifSetEvents(COM_MGMT_BUTTONS, 0..MGMT_SLOT_LAST, IfEvent.PauseButton)
            player.ifSetEvents(COM_MGMT_PRESETS, 0 until ToaPresets.SLOT_COUNT, IfEvent.Op1, IfEvent.Op2)
        }
    }

    /** 774 pause-button dispatch (`toa_partydetails:pausebuttons`; layout in class KDoc). */
    suspend fun onManagementButton(access: ProtectedAccess, comsub: Int) {
        val player = access.player
        val uuid = player.uuid ?: return
        val party = registry.viewingParty(uuid)
        if (party == null || party.members.isEmpty()) {
            registry.setViewingParty(uuid, null)
            refreshPartyList(player)
            return
        }
        val isLeader = party.isLeader(uuid)
        when {
            comsub == MGMT_SLOT_BACK -> openPartyList(access)
            comsub == MGMT_SLOT_REFRESH -> refreshManagement(player)
            comsub == MGMT_SLOT_UNBLOCK -> if (isLeader) unblockAll(access, party)
            comsub == MGMT_SLOT_COMPLETIONS -> if (isLeader) setCompletions(access, party)
            comsub == MGMT_SLOT_ACTION -> memberAction(access, party)
            comsub == MGMT_SLOT_CLEAR_ALL -> if (isLeader) clearAllInvocations(access, party)
            comsub == MGMT_SLOT_LOAD_PRESET -> if (isLeader) loadPreset(access, party)
            comsub == MGMT_SLOT_SAVE_PRESET -> if (isLeader) savePreset(access, party)
            comsub in MGMT_SLOTS_TABS -> {
                registry.setTab(uuid, comsub - MGMT_SLOTS_TABS.first)
                refreshManagement(player)
            }
            comsub in MGMT_SLOTS_MEMBERS ->
                if (isLeader) memberRowClick(access, party, comsub - MGMT_SLOTS_MEMBERS.first)
            comsub in MGMT_SLOTS_SORT_UP || comsub in MGMT_SLOTS_SORT_DOWN ->
                // NR had no member-sort handler; refresh restores the client row state.
                refreshManagement(player)
            comsub in MGMT_SLOTS_ACCEPT ->
                if (isLeader) applicantDecision(access, party, comsub - MGMT_SLOTS_ACCEPT.first, accept = true)
            comsub in MGMT_SLOTS_DECLINE ->
                if (isLeader) applicantDecision(access, party, comsub - MGMT_SLOTS_DECLINE.first, accept = false)
            comsub in MGMT_SLOTS_INVOCATIONS ->
                if (isLeader) toggleInvocation(access, party, comsub - MGMT_SLOTS_INVOCATIONS.first)
        }
    }

    /** Preset slots (`toa_partydetails:presets_button_click`): op1 select, op2 clear. */
    suspend fun onPresetButton(access: ProtectedAccess, comsub: Int, op: IfButtonOp) {
        val player = access.player
        val uuid = player.uuid ?: return
        val party = registry.viewingParty(uuid) ?: return
        if (!party.isLeader(uuid) || comsub !in 0 until ToaPresets.SLOT_COUNT) {
            return
        }
        if (op == IfButtonOp.Op1) {
            // Toggle varbit toa_preset_selected between 0 and slot + 1.
            val selected = ToaPresets.selectedSlot(player)
            ToaPresets.setSelectedSlot(player, if (selected == comsub) -1 else comsub)
            refreshManagement(player)
            return
        }
        val confirm =
            access.choice2(
                "Clear this preset.",
                true,
                "Cancel",
                false,
                title = "Are you sure you wish to clear this preset?",
            )
        if (confirm) {
            // NR-PARITY: NR's runnable called clearInvocationPreset twice (harmless dupe);
            // ported as a single clear.
            ToaPresets.clear(player, comsub)
            refreshManagement(player)
            access.mes("Your preset has been cleared.")
            access.soundSynth(SYNTH_CLEAR) // synth 2381 (unnamed in gamevals).
        } else {
            refreshManagement(player)
        }
    }

    /** 774:96 "Reward info": popupoverlay (289) on `toa_partydetails:popup` + cs2 4212. */
    fun onRewardInfo(access: ProtectedAccess) {
        access.ifOpenSub(IF_POPUP, COM_MGMT_POPUP, IfSubType.Overlay)
        // cs2: script4212 (unnamed) — popupoverlay text builder; second arg is the packed
        // component 774:61 (NR literal 50724925).
        access.runClientScript(CS2_POPUP_FILL, REWARD_POTENTIAL_INFO, COM_MGMT_POPUP.asRSCM(RSCMType.COMPONENT))
    }

    // ------------------------------------------------------------------------------------
    // Management button flows.
    // ------------------------------------------------------------------------------------

    private suspend fun unblockAll(access: ProtectedAccess, party: ToaParty) {
        access.mesbox(MSG_UNBLOCKED)
        party.blocked.clear()
        refreshManagement(access.player)
    }

    private suspend fun setCompletions(access: ProtectedAccess, party: ToaParty) {
        // NR-PARITY: prompt typo ("preffered") kept verbatim from the source.
        val value = access.numberDialog("Set a preffered number of completions up to 100 (or 0 to clear it):")
        party.settings.kcRequirement = minOf(100, value)
        registry.persistSettings(access.player)
        refreshManagement(access.player)
    }

    /** The context "Member options" button: Apply / Leave / Disband / Withdraw by view value. */
    private suspend fun memberAction(access: ProtectedAccess, party: ToaParty) {
        val uuid = access.player.uuid ?: return
        when (viewValueFor(uuid, party)) {
            VIEW_NON_MEMBER -> applyTo(access, party)
            VIEW_MEMBER -> leaveAction(access)
            VIEW_LEADER -> disbandAction(access, party)
            VIEW_APPLICANT -> withdrawAction(access)
            VIEW_BLOCKED -> {
                access.mesbox(MSG_DECLINED_BY_PARTY)
                refreshManagement(access.player)
            }
        }
    }

    private suspend fun applyTo(access: ProtectedAccess, target: ToaParty) {
        val player = access.player
        val uuid = player.uuid ?: return
        val current = registry.currentParty(uuid)
        if (current != null && current.raidStarted) {
            access.mesbox(MSG_JOIN_IN_TOMBS)
            return
        }
        // Auto-withdraw any previous application (one outstanding application per player),
        // refreshing that party leader's applicant list (NR parity).
        val previous = registry.appliedParty(uuid)
        if (previous != null && previous !== target) {
            withdrawFrom(previous, uuid)
            refreshLeaderIfViewing(previous)
        }
        if (current != null) {
            val quit =
                access.choice2(
                    "Stay in my existing party.",
                    false,
                    "Quit that one and apply to this one.",
                    true,
                    title = "You are already in a party",
                )
            if (!quit) {
                refreshManagement(player)
                return
            }
            leaveParty(current, uuid, resetOverlay = true)
            refreshLeaderIfViewing(current)
        }
        if (target.raidStarted || !registry.isListed(target) || !target.canApply(uuid)) {
            access.mesbox(MSG_NOT_RECRUITING)
            openPartyList(access)
            return
        }
        target.addApplicant(ToaPartyMember(uuid, player.displayName))
        registry.setAppliedParty(uuid, target)
        access.mes("You have applied to join the party of ${target.leaderDisplayName}.")
        registry.setTab(uuid, TAB_APPLICANTS)
        refreshManagement(player)
        refreshLeaderIfViewing(target)
    }

    private suspend fun leaveAction(access: ProtectedAccess) {
        val player = access.player
        val uuid = player.uuid ?: return
        val current = registry.currentParty(uuid) ?: return
        if (current.raidStarted) {
            access.mesbox(MSG_JOIN_IN_TOMBS)
            return
        }
        val leaderName = current.leaderDisplayName
        leaveParty(current, uuid, resetOverlay = true)
        // NR-PARITY: message typo ("part of") kept verbatim from the source.
        access.mes("You have left the part of $leaderName.")
        refreshLeaderIfViewing(current)
        openPartyList(access)
    }

    private fun disbandAction(access: ProtectedAccess, party: ToaParty) {
        val uuid = access.player.uuid ?: return
        access.mes("Your party has disbanded.")
        disband(party, excludeUuid = uuid)
        sendEmptyPartyOverlay(access.player)
        openPartyList(access)
    }

    private fun withdrawAction(access: ProtectedAccess) {
        val player = access.player
        val uuid = player.uuid ?: return
        val applied = registry.appliedParty(uuid)
        if (applied == null) {
            refreshManagement(player)
            return
        }
        withdrawFrom(applied, uuid)
        access.mes("You have withdrawn your party application.")
        refreshManagement(player)
        refreshLeaderIfViewing(applied)
    }

    /** 774 member rows (leader only): own row = leave, other rows = kick (NR slot-12 map). */
    private fun memberRowClick(access: ProtectedAccess, party: ToaParty, row: Int) {
        val player = access.player
        val uuid = player.uuid ?: return
        val target = party.members.getOrNull(row) ?: return
        if (target.uuid == uuid) {
            leaveParty(party, uuid, resetOverlay = true)
            // NR-PARITY: no trailing period in the source message.
            access.mes("You have left your party")
            openPartyList(access)
            return
        }
        party.removeMember(target.uuid)
        registry.setCurrentParty(target.uuid, null)
        broadcastPartyText(party)
        refreshManagement(player)
        val kicked = registry.resolve(target)
        if (kicked != null) {
            if (isViewingManagement(kicked, party)) {
                refreshManagement(kicked)
            }
            sendEmptyPartyOverlay(kicked)
            kicked.mes("You have been kicked from the party of ${party.leaderDisplayName}.")
            kicked.soundSynth(SYNTH_DECLINE) // synth 2277 (unnamed in gamevals).
        }
        access.mes("You have kicked ${target.name} from your party.")
    }

    private fun applicantDecision(access: ProtectedAccess, party: ToaParty, row: Int, accept: Boolean) {
        val player = access.player
        val applicant = party.applicants.getOrNull(row) ?: return
        if (accept && party.isFull) {
            access.mes("Your party is full.")
            return
        }
        // NR handleAcceptant guards: not already a member and still applied to THIS party.
        if (party.isMember(applicant.uuid) || registry.appliedParty(applicant.uuid) !== party) {
            refreshManagement(player)
            return
        }
        party.removeApplicant(applicant.uuid)
        registry.setAppliedParty(applicant.uuid, null)
        val applicantPlayer = registry.resolve(applicant)
        if (accept) {
            party.addMember(applicant)
            registry.setCurrentParty(applicant.uuid, party)
            broadcastPartyText(party)
            if (applicantPlayer != null) {
                setPartyStatus(applicantPlayer, STATUS_IN_PARTY)
                if (isViewingManagement(applicantPlayer, party)) {
                    refreshManagement(applicantPlayer)
                }
                applicantPlayer.mes("Your application to the party of ${party.leaderDisplayName} has been accepted.")
                applicantPlayer.soundSynth(SYNTH_ACCEPT) // synth 2655 (unnamed in gamevals).
            }
            access.mes("You have accepted ${applicant.name} into your party.")
        } else {
            // Declined applicants are blocked until the leader presses "Unblock".
            party.blocked += applicant.uuid
            if (applicantPlayer != null) {
                if (isViewingManagement(applicantPlayer, party)) {
                    refreshManagement(applicantPlayer)
                }
                applicantPlayer.mes("Your application to the party of ${party.leaderDisplayName} has been declined.")
                applicantPlayer.soundSynth(SYNTH_DECLINE)
            }
            access.mes("You have declined the party application from ${applicant.name}.")
        }
        refreshManagement(player)
    }

    private suspend fun clearAllInvocations(access: ProtectedAccess, party: ToaParty) {
        val confirm =
            access.choice2(
                "Yes.",
                true,
                "No.",
                false,
                title = "Are you sure you want to clear all active Invocations?",
            )
        if (confirm) {
            party.settings.clearAll()
            registry.persistSettings(access.player)
            access.soundSynth(SYNTH_CLEAR)
        }
        refreshManagement(access.player)
    }

    /** NR `TOAManager.toggleInvocation` UI flow (rules live in `ToaPartySettings.toggle`). */
    private fun toggleInvocation(access: ProtectedAccess, party: ToaParty, index: Int) {
        // rev-239/240 cs2 lists 46 invocation rows; the two post-NR additions are unported.
        val invocation = ToaInvocation.entries.getOrNull(index) ?: return
        val settings = party.settings
        if (!settings.isActive(invocation)) {
            val missing = settings.missingPrerequisite(invocation)
            if (missing != null) {
                access.mes(
                    "You cannot activate this invocation without first enabling " +
                        "<col=ff0000>${missing.displayName}</col>."
                )
                refreshManagement(access.player)
                return
            }
        }
        val active = settings.toggle(invocation)
        registry.persistSettings(access.player)
        // synth 6589 on / 6588 off (unnamed in gamevals; NOT the same-numbered clientscripts).
        access.soundSynth(if (active) SYNTH_INVOCATION_ON else SYNTH_INVOCATION_OFF)
        refreshManagement(access.player)
    }

    private fun loadPreset(access: ProtectedAccess, party: ToaParty) {
        val player = access.player
        val slot = ToaPresets.selectedSlot(player)
        ToaPresets.setSelectedSlot(player, -1)
        if (slot == -1) {
            access.mes("You do not have a valid preset selected to load from.")
            // NR updatePartyManagementInterface parity: every branch refreshes the screen.
            refreshManagement(player)
            return
        }
        val loaded = ToaPresets.load(player, slot)
        if (loaded == null) {
            access.mes("You do not have any invocations stored in this preset.")
            refreshManagement(player)
            return
        }
        // NR loadInvocationPreset replaces only the bitmaps — the kc requirement is kept.
        val kcRequirement = party.settings.kcRequirement
        party.settings.copyFrom(loaded)
        party.settings.kcRequirement = kcRequirement
        registry.persistSettings(player)
        access.mes("Your preset has been loaded.")
        access.soundSynth(SYNTH_ACCEPT)
        refreshManagement(player)
    }

    private suspend fun savePreset(access: ProtectedAccess, party: ToaParty) {
        val player = access.player
        val slot = ToaPresets.selectedSlot(player)
        if (slot == -1) {
            access.mes("You do not have a valid preset selected to save to.")
            // NR updatePartyManagementInterface parity: every branch refreshes the screen.
            refreshManagement(player)
            return
        }
        if (!ToaPresets.isEmpty(player, slot)) {
            val overwrite =
                access.choice2(
                    "Save and overwrite this preset.",
                    true,
                    "Cancel",
                    false,
                    title = "You already have a preset saved in this slot.",
                )
            if (!overwrite) {
                refreshManagement(player)
                return
            }
        }
        ToaPresets.setSelectedSlot(player, -1)
        ToaPresets.save(player, slot, party.settings)
        refreshManagement(player)
        access.mes("Your preset has been saved.")
        access.soundSynth(SYNTH_ACCEPT)
    }

    // ------------------------------------------------------------------------------------
    // Party mutations shared with the lobby scripts — NR TOALobbyParty.
    // ------------------------------------------------------------------------------------

    /**
     * NR `TOALobbyParty.leave`: removes the member, promotes `members[0]` when the leader
     * left (re-homing the party settings onto the new leader's persistent loadout), delists
     * an emptied party, and re-broadcasts the overlay text. [resetOverlay] additionally
     * resets the leaver's own overlay/varbit (NR's `updatePartyList` flag).
     */
    fun leaveParty(party: ToaParty, uuid: Long, resetOverlay: Boolean) {
        val wasLeader = party.isLeader(uuid)
        val leaving = party.removeMember(uuid) ?: return
        registry.setCurrentParty(uuid, null)
        if (party.members.isEmpty()) {
            registry.removeFromListing(party)
        } else {
            if (wasLeader && !party.raidStarted) {
                promoteNewLeader(party)
            } else if (wasLeader) {
                party.promoteLeader()
            }
            broadcastPartyText(party)
        }
        if (party.raidStarted) {
            // NR parity: leaving the lobby party of a started raid forfeits raid membership
            // (`raidParty.leave`); minimal roster detach — only for a member who never
            // entered (or already left) the raid, so an in-raid member's state stays owned
            // by the raid flows, and detaching only the entry that points at that raid.
            val raid = raidRegistry.forPlayer(uuid)
            if (raid != null && raid.playerState(uuid)?.currentRoom == null) {
                raid.removeMember(uuid, keepRosterEntry = false)
                raidRegistry.detachPlayer(uuid)
            }
        }
        if (resetOverlay) {
            registry.resolve(leaving.uuid)?.let(::sendEmptyPartyOverlay)
        }
    }

    /**
     * NR promotion semantics: the outgoing settings are copied into the new leader's
     * persistent loadout, and the party re-aliases that object — the invocation setup
     * survives the leader leaving.
     */
    private fun promoteNewLeader(party: ToaParty) {
        val newLeader = party.members.first()
        party.promoteLeader()
        val player = registry.resolve(newLeader) ?: return
        val settings = registry.personalSettings(player)
        settings.copyFrom(party.settings)
        party.settings = settings
        registry.persistSettings(player)
    }

    /** NR `TOALobbyParty.disband` — kicks every member/applicant with the ported dialogues. */
    fun disband(party: ToaParty, excludeUuid: Long?) {
        registry.removeFromListing(party)
        for (member in party.members.toList()) {
            registry.setCurrentParty(member.uuid, null)
            if (member.uuid == excludeUuid) {
                continue
            }
            val player = registry.resolve(member) ?: continue
            sendEmptyPartyOverlay(player)
            if (isViewingManagement(player, party)) {
                notifyThenPartyList(player, "Your party has disbanded.")
            } else {
                player.mes("Your party has disbanded.")
            }
        }
        party.members.clear()
        for (applicant in party.applicants.toList()) {
            registry.setAppliedParty(applicant.uuid, null)
            val player = registry.resolve(applicant) ?: continue
            if (isViewingManagement(player, party)) {
                notifyThenPartyList(player, MSG_NOT_RECRUITING)
            } else {
                player.mes("The party to which you were applying has disbanded.")
            }
        }
        party.applicants.clear()
        for (blockedUuid in party.blocked.toList()) {
            registry.setAppliedParty(blockedUuid, null)
            val player = registry.resolve(blockedUuid) ?: continue
            if (isViewingManagement(player, party)) {
                notifyThenPartyList(player, "That party has disbanded.")
            }
        }
        party.blocked.clear()
    }

    /** NR `TOALobbyParty.withdraw` + applied-party clear (leader refresh is the caller's). */
    fun withdrawFrom(party: ToaParty, uuid: Long) {
        party.removeApplicant(uuid)
        if (registry.appliedParty(uuid) === party) {
            registry.setAppliedParty(uuid, null)
        }
    }

    /**
     * Refreshes the party leader's management screen when they are viewing [party] (NR
     * `updatePartyApplicants(leader)` / `updatePartyManagementInterface(leader)` calls).
     */
    fun refreshLeaderIfViewing(party: ToaParty) {
        val leader = party.leaderOrNull ?: return
        val player = registry.resolve(leader) ?: return
        if (isViewingManagement(player, party)) {
            refreshManagement(player)
        }
    }

    /**
     * NR `TOAManager.enterRaid`/`enter` raid-start party bookkeeping, run EAGERLY from
     * `ToaRaidController.startRaid`: flags the party as raiding, delists it from the obelisk
     * board (NR `removeFromList`) and clears every member's current-party pointer (NR
     * `enter` did this per entering member). Members NOT in [enteredUuids] — genuine
     * stragglers whose instance join failed or who were offline — get NR's "step inside"
     * notice with party-status 2 so they can follow through the entrance; members being
     * pulled in with the party get no notice (they are already entering).
     *
     * Eager (not lazy) on purpose: once `startRaid` pulls the whole party into the instance
     * no lobby-side trigger can fire again, and a stale `currentParty` pointer would route a
     * mid-raid logout through the lobby leave flow (leader promotion mutating another
     * player's persistent loadout — NR prevented this via `!insideRaid()` + the
     * `currentParty` clear).
     */
    fun markRaidStarted(party: ToaParty, enteredUuids: Set<Long>) {
        if (party.raidStarted) {
            return
        }
        party.raidStarted = true
        registry.removeFromListing(party)
        val leaderName = party.leaderDisplayName
        for (member in party.members) {
            registry.setCurrentParty(member.uuid, null)
            if (member.uuid in enteredUuids) {
                continue
            }
            val memberPlayer = registry.resolve(member) ?: continue
            if (insideLobby(memberPlayer.coords)) {
                val pronoun =
                    if (memberPlayer.appearance.bodyType == constants.bodytype_a) "him" else "her"
                memberPlayer.mes(
                    "$leaderName has entered the Tombs of Amascut. Step inside to join $pronoun..."
                )
                setPartyStatus(memberPlayer, STATUS_PARTY_IN_RAID)
            }
        }
    }

    /**
     * Lazily marks [party] as inside the raid once its leader has actually started one — a
     * safety net behind [markRaidStarted] (which `ToaRaidController.startRaid` now calls
     * eagerly): the leader is registered in an active raid AND is no longer standing in the
     * lobby (a leader idling in the lobby with a rejoin ticket does not count — they cannot
     * start a new raid while the ticket lives).
     */
    fun syncRaidState(party: ToaParty) {
        if (party.raidStarted) {
            return
        }
        val leader = party.leaderOrNull ?: return
        val raid = raidRegistry.forPlayer(leader.uuid) ?: return
        if (raid.tombsFailure || raid.isFinished) {
            return
        }
        val leaderPlayer = registry.resolve(leader)
        if (leaderPlayer != null && insideLobby(leaderPlayer.coords)) {
            return
        }
        party.raidStarted = true
        registry.removeFromListing(party)
        val leaderName = party.leaderDisplayName
        for (member in party.members) {
            registry.setCurrentParty(member.uuid, null)
            if (member.uuid == leader.uuid) {
                continue
            }
            val memberPlayer = registry.resolve(member) ?: continue
            if (insideLobby(memberPlayer.coords)) {
                val pronoun =
                    if (memberPlayer.appearance.bodyType == constants.bodytype_a) "him" else "her"
                memberPlayer.mes(
                    "$leaderName has entered the Tombs of Amascut. Step inside to join $pronoun..."
                )
                setPartyStatus(memberPlayer, STATUS_PARTY_IN_RAID)
            }
        }
    }

    /**
     * NR `viewingManagementInterface`. NR-BUG-FIX: NR compared against the player's
     * *currentParty* leader (so non-members viewing a party never matched); this checks the
     * actual viewed party.
     */
    fun isViewingManagement(player: Player, party: ToaParty): Boolean {
        val uuid = player.uuid ?: return false
        return registry.viewingParty(uuid) === party && player.ui.containsModal(IF_MANAGEMENT)
    }

    /**
     * Cross-player port of NR's `PlainChat(text)` with an on-close "reopen party list"
     * runnable: shows the mesbox in the target's own protected context when possible, falling
     * back to a plain chat message when they are busy.
     */
    private fun notifyThenPartyList(player: Player, text: String) {
        val launched =
            protectedAccess.launch(player) {
                mesbox(text)
                openPartyList(this)
            }
        if (!launched) {
            player.mes(text)
        }
    }

    private fun viewValueFor(uuid: Long, party: ToaParty): Int =
        when {
            party.isLeader(uuid) -> VIEW_LEADER
            party.isMember(uuid) -> VIEW_MEMBER
            party.isApplicant(uuid) -> VIEW_APPLICANT
            party.isBlocked(uuid) -> VIEW_BLOCKED
            else -> VIEW_NON_MEMBER
        }

    /**
     * NR `getPlayerStatString` (member + applicant rows), pipe-delimited:
     * `[<col=FFFFFF> if self]name|combat|att|str|ranged|magic|def|hp|prayer|eKc / nKc / xKc|`
     * (unboosted levels). Kill counts are 0 until a ToA killcount store exists (NR read
     * `NotificationSettings` kill counts — no OpenRune equivalent yet).
     */
    private fun statString(member: ToaPartyMember, viewerUuid: Long): String {
        val self = member.uuid == viewerUuid
        val player = registry.resolve(member)
        val name = if (self) "<col=FFFFFF>${member.name}" else member.name
        val combat = player?.combatLevel ?: 3
        val attack = player?.statBase("stat.attack") ?: 1
        val strength = player?.statBase("stat.strength") ?: 1
        val ranged = player?.statBase("stat.ranged") ?: 1
        val magic = player?.statBase("stat.magic") ?: 1
        val defence = player?.statBase("stat.defence") ?: 1
        val hitpoints = player?.statBase("stat.hitpoints") ?: 10
        val prayer = player?.statBase("stat.prayer") ?: 1
        val killCounts = "0 / 0 / 0"
        return "$name|$combat|$attack|$strength|$ranged|$magic|$defence|$hitpoints|$prayer|$killCounts|"
    }

    internal companion object {
        /** NR `TOALobbyArea` polygon bounds check (level 0, (3340,9100)-(3377,9132)). */
        fun insideLobby(coords: CoordGrid): Boolean =
            coords.level == 0 &&
                coords.x in ToaConstants.LOBBY_SOUTH_WEST.x..ToaConstants.LOBBY_NORTH_EAST.x &&
                coords.z in ToaConstants.LOBBY_SOUTH_WEST.z..ToaConstants.LOBBY_NORTH_EAST.z

        // Interfaces (osrs-dumps interface.sym: 772/773/774/289).
        const val IF_PARTY_LIST: String = "interface.toa_partylist"
        const val IF_MANAGEMENT: String = "interface.toa_partydetails"
        const val IF_LOBBY_OVERLAY: String = "interface.toa_lobby"
        const val IF_POPUP: String = "interface.popupoverlay"

        // Components (osrs-dumps component.sym; all verified in gamevals).
        /** 773:5 — lobby overlay member-name text. */
        const val COM_LOBBY_NAMES: String = "component.toa_lobby:names"
        /** 772:1 — pause layer for Refresh(0) / Make Party(1) / Filter(2). */
        const val COM_LIST_BUTTONS: String = "component.toa_partylist:contents"
        /** 772:16 — pause layer for the 45 party rows (comsub = row). */
        const val COM_LIST_ROWS: String = "component.toa_partylist:list"
        /** 774:1 — the big pause-button layer (slot layout in the script KDoc). */
        const val COM_MGMT_BUTTONS: String = "component.toa_partydetails:pausebuttons"
        /** 774:98 — preset slot buttons (op1 Select / op2 Clear). */
        const val COM_MGMT_PRESETS: String = "component.toa_partydetails:presets_button_click"
        /** 774:96 — reward-potential info button (op1). */
        const val COM_MGMT_REWARD_INFO: String = "component.toa_partydetails:raid_level_info"
        /** 774:61 — popup anchor; packed form is NR's literal 50724925. */
        const val COM_MGMT_POPUP: String = "component.toa_partydetails:popup"

        // Vars.
        /** varp 3603 — 0 = in a lobby party, -1 = none (NR `CURRENT_PARTY_VAR`). */
        const val VARP_MY_PARTY: String = "varp.toa_mycontroller"
        /** varbit 14318 — friends-only list filter (client-side filtering). */
        const val VARBIT_FRIENDS_FILTER: String = "varbit.toa_partylist_filter"

        // varbit.toa_client_partystatus values.
        const val STATUS_NO_PARTY: Int = 0
        const val STATUS_IN_PARTY: Int = 1
        const val STATUS_PARTY_IN_RAID: Int = 2

        // Clientscripts — raw ids (no RSCM overload for runClientScript).
        /** cs2: toa_partylist_addline (rowIndex, line). */
        const val CS2_PARTYLIST_ADDLINE: Int = 6601
        /** cs2: toa_partydetails_addmember (2, statString). */
        const val CS2_MGMT_ADDMEMBER: Int = 6722
        /** cs2: toa_partydetails_addapplicant (statString). */
        const val CS2_MGMT_ADDAPPLICANT: Int = 6727
        /** cs2: script6729 (unnamed) — partydetails master state. */
        const val CS2_MGMT_STATE: Int = 6729
        /** cs2: script4212 (unnamed) — popupoverlay text builder. */
        const val CS2_POPUP_FILL: Int = 4212

        // Sounds — raw synth ids; unnamed in gamevals, so the int overload is used.
        const val SYNTH_DECLINE: Int = 2277
        const val SYNTH_CLEAR: Int = 2381
        const val SYNTH_ACCEPT: Int = 2655
        const val SYNTH_INVOCATION_OFF: Int = 6588
        const val SYNTH_INVOCATION_ON: Int = 6589

        // 772 toa_partylist:contents pause slots.
        const val LIST_SLOT_REFRESH: Int = 0
        const val LIST_SLOT_MAKE_PARTY: Int = 1
        const val LIST_SLOT_FILTER: Int = 2

        // 774 toa_partydetails:pausebuttons slots (rev-239/240 layout; see script KDoc).
        const val MGMT_SLOT_BACK: Int = 0
        const val MGMT_SLOT_REFRESH: Int = 1
        const val MGMT_SLOT_UNBLOCK: Int = 2
        const val MGMT_SLOT_COMPLETIONS: Int = 3
        const val MGMT_SLOT_ACTION: Int = 4
        const val MGMT_SLOT_CLEAR_ALL: Int = 5
        const val MGMT_SLOT_LOAD_PRESET: Int = 6
        const val MGMT_SLOT_SAVE_PRESET: Int = 7
        val MGMT_SLOTS_TABS: IntRange = 8..11
        val MGMT_SLOTS_MEMBERS: IntRange = 12..19
        val MGMT_SLOTS_SORT_UP: IntRange = 20..27
        val MGMT_SLOTS_SORT_DOWN: IntRange = 28..35
        val MGMT_SLOTS_ACCEPT: IntRange = 36..43
        val MGMT_SLOTS_DECLINE: IntRange = 44..51
        val MGMT_SLOTS_INVOCATIONS: IntRange = 52..97
        const val MGMT_SLOT_TABS_LAST: Int = 11
        const val MGMT_SLOT_LAST: Int = 97

        // Management tabs (NR currentTOAPartyManagementTab).
        const val TAB_MEMBERS: Int = 0
        const val TAB_APPLICANTS: Int = 1

        // View values (NR currentTOAPartyViewingValue).
        const val VIEW_NON_MEMBER: Int = 0
        const val VIEW_MEMBER: Int = 1
        const val VIEW_LEADER: Int = 2
        const val VIEW_APPLICANT: Int = 3
        const val VIEW_BLOCKED: Int = 4

        /** NR `TOAManager.sendEmptyPartyList` text — eight dashes. */
        const val EMPTY_PARTY_TEXT: String = "-<br>-<br>-<br>-<br>-<br>-<br>-<br>-"

        // Shared messages (NR verbatim).
        const val MSG_JOIN_IN_TOMBS: String = "You should join your party in the tombs."
        const val MSG_NOT_RECRUITING: String = "That party is no longer recruiting."
        const val MSG_LOBBY_FULL: String =
            "The list of lobby parties is currently full. Please come back later or apply to " +
                "an existing party."
        const val MSG_UNBLOCKED: String =
            "All players rejected from this party have been unblocked and may apply again."
        const val MSG_DECLINED_BY_PARTY: String = "You have been declined by this party."

        /** NR `TOAPartyManagementInterface.REWARD_POTENTIAL_INFO`, verbatim. */
        const val REWARD_POTENTIAL_INFO: String =
            "Reward Potential|Before entering a raid, you can customise the difficulty of the " +
                "challenges you'll face by using <col=ffffff>Invocations</col>. There are a " +
                "large variety of Invocations available, covering both challenge-specific " +
                "mechanics as well as raid-wide systems.<br><br>Enabling or disabling " +
                "Invocations will change the <col=ffffff>Raid Level</col>. Higher Raid Levels " +
                "will result in more rewards becoming available.<br><br>If a reward is " +
                "outlined in <col=ffd270>gold</col>, it is reasonably possible to obtain it " +
                "at this Raid Level. If a reward is outlined in <col=ff7070>red</col>, it is " +
                "not possible to obtain it at this Raid Level. If a reward is not outlined, " +
                "it is still possible, though highly unlikely, to obtain it at this Raid " +
                "Level.|Close|"
    }
}

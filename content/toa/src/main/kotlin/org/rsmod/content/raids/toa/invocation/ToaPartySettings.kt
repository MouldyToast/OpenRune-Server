package org.rsmod.content.raids.toa.invocation

/**
 * Mutable, lobby-side invocation loadout for a Tombs of Amascut party (owned by the party
 * leader; the raid takes a frozen [copy] snapshot at raid start).
 *
 * Port of NR/Zenyte `TOAPartySettings.java` + `TOAPartySettingData.java` and the
 * toggle/prerequisite logic from `TOAManager.toggleInvocation`
 * (`com.zenyte.game.content.tombsofamascut`).
 *
 * Bitmap layout (must match NR and the client's cs2 decoding exactly — the same three raw
 * ints are handed to clientscript 6729 and stored in the preset varps):
 * - Uses the **1-based cache bit index** ([ToaInvocation.bitIndex], struct param 1159), not
 *   the enum ordinal.
 * - `map = if (index > 61) 2 else if (index > 30) 1 else 0`; `bit = 1 shl (index % 31)`.
 * - 31 usable bits per int (the sign bit is never used); indices 1-30 land in `bitmaps[0]`
 *   bits 1-30 (bit 0 unused), indices 31-61 in `bitmaps[1]` bits 0-30, indices 62+ in
 *   `bitmaps[2]` (currently always 0 — all 44 invocations fit in [0] and [1]).
 *
 * Unlike NR, `raidLevel`/`activeInvocations` are recomputed from the bitmaps on demand
 * instead of being maintained incrementally — same observable results, no derived-state
 * drift. `kcRequirement` is display-only (never enforced on join), as in NR.
 */
internal class ToaPartySettings private constructor(val bitmaps: IntArray) {

    constructor() : this(IntArray(BITMAP_SIZE))

    /** Leader-set kill-count requirement, capped at 100 by the UI. Display-only (NR parity). */
    var kcRequirement: Int = 0

    fun isActive(inv: ToaInvocation): Boolean = bitmaps[mapIndex(inv)] and bit(inv) != 0

    /**
     * Toggles [inv], enforcing NR's rules, and returns the new active state:
     * - Deactivating cascades to dependents (`OVERCLOCKED` -> `OVERCLOCKED_2` -> `INSANITY`;
     *   `NOT_JUST_A_HEAD` -> `ARTERIAL_SPRAY` + `BLOOD_THINNERS`).
     * - Activating fails (returns `false`, no state change) while a prerequisite is inactive —
     *   use [missingPrerequisite] to build the "You cannot activate this invocation without
     *   first enabling ..." message.
     * - Activating an invocation of a radio category (ATTEMPTS / TIME_LIMIT / HELPFUL_SPIRIT /
     *   PATH_LEVEL) first deactivates the rest of that category.
     */
    fun toggle(inv: ToaInvocation): Boolean {
        if (isActive(inv)) {
            // NR-BUG-FIX: NR's else-if chain in `TOAManager.toggleInvocation` cleared only ONE
            // dependent of NOT_JUST_A_HEAD per toggle (ARTERIAL_SPRAY shadowed BLOOD_THINNERS),
            // leaving the other active without its prerequisite. Clear every dependent.
            dependents[inv]?.forEach { dependent ->
                if (isActive(dependent)) {
                    unflag(dependent)
                }
            }
            unflag(inv)
            return false
        }
        if (missingPrerequisite(inv) != null) {
            return false
        }
        if (inv.category.radio) {
            unflagCategory(inv.category)
        }
        flag(inv)
        return true
    }

    /**
     * Returns the inactive prerequisite blocking activation of [inv], or `null` if [inv] can
     * be activated. (`INSANITY` requires `OVERCLOCKED_2`, `OVERCLOCKED_2` requires
     * `OVERCLOCKED`, `ARTERIAL_SPRAY`/`BLOOD_THINNERS` require `NOT_JUST_A_HEAD`.)
     */
    fun missingPrerequisite(inv: ToaInvocation): ToaInvocation? {
        val prerequisite = prerequisites[inv] ?: return null
        return if (isActive(prerequisite)) null else prerequisite
    }

    /** Raid level = sum of [ToaInvocation.levelModifier] over active invocations. */
    fun raidLevel(): Int = ToaInvocation.entries.sumOf { if (isActive(it)) it.levelModifier else 0 }

    fun activeInvocations(): Int = ToaInvocation.entries.count(::isActive)

    fun activeList(): List<ToaInvocation> = ToaInvocation.entries.filter(::isActive)

    /** `true` iff every invocation of [category] is active (NR `allActive`). */
    fun allActive(category: ToaInvocationCategory): Boolean =
        ToaInvocation.entries.none { it.category == category && !isActive(it) }

    /** Clears every invocation (NR `clearInvocations`). Leaves [kcRequirement] untouched. */
    fun clearAll() {
        bitmaps.fill(0)
    }

    /**
     * Deep copy.
     *
     * NR-BUG-FIX: NR's `copyPartySettings`/`generateData` passed `invocationBitmaps` by
     * reference, so the raid "snapshot" aliased the leader's live settings (lobby toggles
     * after entering mutated the running raid). This copy shares nothing.
     */
    fun copy(): ToaPartySettings {
        val copy = ToaPartySettings(bitmaps.copyOf())
        copy.kcRequirement = kcRequirement
        return copy
    }

    /**
     * Replaces this instance's state with a deep copy of [other]'s, in place. Used when
     * settings are re-homed onto a new leader (NR `copyPartySettings` semantics, minus the
     * array-aliasing bug — see [copy]).
     */
    fun copyFrom(other: ToaPartySettings) {
        other.bitmaps.copyInto(bitmaps)
        kcRequirement = other.kcRequirement
    }

    /** Returns a defensive copy of the three raw bitmap ints (preset/varp/cs2 wire format). */
    fun encode(): IntArray = bitmaps.copyOf()

    override fun toString(): String =
        "ToaPartySettings(raidLevel=${raidLevel()}, active=${activeInvocations()}, " +
            "kcRequirement=$kcRequirement, bitmaps=${bitmaps.toList()})"

    private fun flag(inv: ToaInvocation) {
        bitmaps[mapIndex(inv)] = bitmaps[mapIndex(inv)] or bit(inv)
    }

    private fun unflag(inv: ToaInvocation) {
        bitmaps[mapIndex(inv)] = bitmaps[mapIndex(inv)] and bit(inv).inv()
    }

    private fun unflagCategory(category: ToaInvocationCategory) {
        for (inv in ToaInvocation.entries) {
            if (inv.category == category && isActive(inv)) {
                unflag(inv)
            }
        }
    }

    companion object {
        const val BITMAP_SIZE: Int = 3

        /**
         * Rebuilds settings from three raw bitmap ints (NR `loadInvocationPreset`, which
         * recomputed the derived counters by scanning all invocations; here derived state is
         * always computed on read). The array is copied, never aliased (NR-BUG-FIX — NR
         * assigned the caller's array by reference).
         */
        fun decode(b: IntArray): ToaPartySettings {
            require(b.size == BITMAP_SIZE) { "Expected $BITMAP_SIZE bitmap ints, got ${b.size}." }
            return ToaPartySettings(b.copyOf())
        }

        /** NR-exact bitmap slot: indices 1-30 -> 0, 31-61 -> 1, 62+ -> 2. */
        private fun mapIndex(inv: ToaInvocation): Int =
            if (inv.bitIndex > 61) 2 else if (inv.bitIndex > 30) 1 else 0

        /** NR-exact bit: `1 shl (index % 31)` — 31-bit packing, sign bit never used. */
        private fun bit(inv: ToaInvocation): Int = 1 shl (inv.bitIndex % 31)

        /** Activation prerequisites (`TOAManager.toggleInvocation` enable-side checks). */
        private val prerequisites: Map<ToaInvocation, ToaInvocation> =
            mapOf(
                ToaInvocation.INSANITY to ToaInvocation.OVERCLOCKED_2,
                ToaInvocation.OVERCLOCKED_2 to ToaInvocation.OVERCLOCKED,
                ToaInvocation.ARTERIAL_SPRAY to ToaInvocation.NOT_JUST_A_HEAD,
                ToaInvocation.BLOOD_THINNERS to ToaInvocation.NOT_JUST_A_HEAD,
            )

        /** Cascade-deactivation targets (disable-side of `TOAManager.toggleInvocation`). */
        private val dependents: Map<ToaInvocation, List<ToaInvocation>> =
            mapOf(
                ToaInvocation.OVERCLOCKED to
                    listOf(ToaInvocation.OVERCLOCKED_2, ToaInvocation.INSANITY),
                ToaInvocation.OVERCLOCKED_2 to listOf(ToaInvocation.INSANITY),
                ToaInvocation.NOT_JUST_A_HEAD to
                    listOf(ToaInvocation.ARTERIAL_SPRAY, ToaInvocation.BLOOD_THINNERS),
            )
    }
}

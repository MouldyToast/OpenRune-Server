package org.rsmod.content.raids.toa.invocation

/**
 * Tombs of Amascut invocation definitions.
 *
 * Port of NR/Zenyte `InvocationType.java` and `InvocationCategoryType.java`
 * (`com.zenyte.game.content.tombsofamascut`).
 *
 * NR loads [bitIndex] (struct param 1159), category (param 1161, `raw - 3` into the category
 * enum) and [levelModifier] (param 1162) from cache structs at class-init. Per the Session 1
 * design brief, this port hardcodes the values verified against the rev240 cache struct dump
 * instead of reading the cache (struct ids kept for reference). Declaration order matches NR's
 * `InvocationType.VALUES` (= struct id order = bit index order); UI code relies on
 * `ordinal` mapping to invocation-toggle slot `36 + ordinal` on `toa_partydetails`.
 *
 * Struct ids 443 and 446 exist in the cache but are not invocations and are skipped, as in NR.
 */
internal enum class ToaInvocation(
    val structId: Int,
    /** 1-based cache bit index (struct param 1159) — NOT the enum ordinal. */
    val bitIndex: Int,
    val category: ToaInvocationCategory,
    val levelModifier: Int,
    val displayName: String,
) {
    TRY_AGAIN(structId = 417, bitIndex = 1, ToaInvocationCategory.ATTEMPTS, levelModifier = 5, "Try Again"),
    PERSISTENCE(structId = 418, bitIndex = 2, ToaInvocationCategory.ATTEMPTS, levelModifier = 10, "Persistence"),
    SOFTCORE_RUN(structId = 419, bitIndex = 3, ToaInvocationCategory.ATTEMPTS, levelModifier = 15, "Softcore Run"),
    HARDCORE_RUN(structId = 420, bitIndex = 4, ToaInvocationCategory.ATTEMPTS, levelModifier = 25, "Hardcore Run"),
    WALK_FOR_IT(structId = 421, bitIndex = 5, ToaInvocationCategory.TIME_LIMIT, levelModifier = 10, "Walk for It"),
    JOG_FOR_IT(structId = 422, bitIndex = 6, ToaInvocationCategory.TIME_LIMIT, levelModifier = 15, "Jog for It"),
    RUN_FOR_IT(structId = 423, bitIndex = 7, ToaInvocationCategory.TIME_LIMIT, levelModifier = 20, "Run for It"),
    SPRINT_FOR_IT(structId = 424, bitIndex = 8, ToaInvocationCategory.TIME_LIMIT, levelModifier = 25, "Sprint for It"),
    NEED_SOME_HELP(structId = 425, bitIndex = 9, ToaInvocationCategory.HELPFUL_SPIRIT, levelModifier = 15, "Need Some Help?"),
    NEED_LESS_HELP(structId = 426, bitIndex = 10, ToaInvocationCategory.HELPFUL_SPIRIT, levelModifier = 25, "Need Less Help?"),
    NO_HELP_NEEDED(structId = 427, bitIndex = 11, ToaInvocationCategory.HELPFUL_SPIRIT, levelModifier = 40, "No Help Needed"),
    WALK_THE_PATH(structId = 428, bitIndex = 12, ToaInvocationCategory.PATHS, levelModifier = 50, "Walk the Path"),
    PATHSEEKER(structId = 429, bitIndex = 13, ToaInvocationCategory.PATH_LEVEL, levelModifier = 15, "Pathseeker"),
    PATHFINDER(structId = 430, bitIndex = 14, ToaInvocationCategory.PATH_LEVEL, levelModifier = 40, "Pathfinder"),
    PATHMASTER(structId = 431, bitIndex = 15, ToaInvocationCategory.PATH_LEVEL, levelModifier = 50, "Pathmaster"),
    QUIET_PRAYERS(structId = 432, bitIndex = 16, ToaInvocationCategory.PRAYER, levelModifier = 20, "Quiet Prayers"),
    DEADLY_PRAYERS(structId = 433, bitIndex = 17, ToaInvocationCategory.PRAYER, levelModifier = 20, "Deadly Prayers"),
    ON_A_DIET(structId = 434, bitIndex = 18, ToaInvocationCategory.RESTORATION, levelModifier = 15, "On a Diet"),
    DEHYDRATION(structId = 435, bitIndex = 19, ToaInvocationCategory.RESTORATION, levelModifier = 30, "Dehydration"),
    OVERLY_DRAINING(structId = 436, bitIndex = 20, ToaInvocationCategory.RESTORATION, levelModifier = 15, "Overly Draining"),
    LIVELY_LARVAE(structId = 437, bitIndex = 21, ToaInvocationCategory.KEPHRI, levelModifier = 5, "Lively Larvae"),
    MORE_OVERLORDS(structId = 438, bitIndex = 22, ToaInvocationCategory.KEPHRI, levelModifier = 15, "More Overlords"),
    BLOWING_MUD(structId = 439, bitIndex = 23, ToaInvocationCategory.KEPHRI, levelModifier = 10, "Blowing Mud"),
    MEDIC(structId = 440, bitIndex = 24, ToaInvocationCategory.KEPHRI, levelModifier = 15, "Medic!"),
    AERIAL_ASSAULT(structId = 441, bitIndex = 25, ToaInvocationCategory.KEPHRI, levelModifier = 10, "Aerial Assault"),
    NOT_JUST_A_HEAD(structId = 442, bitIndex = 26, ToaInvocationCategory.ZEBAK, levelModifier = 15, "Not Just a Head"),
    ARTERIAL_SPRAY(structId = 444, bitIndex = 27, ToaInvocationCategory.ZEBAK, levelModifier = 10, "Arterial Spray"),
    BLOOD_THINNERS(structId = 445, bitIndex = 28, ToaInvocationCategory.ZEBAK, levelModifier = 5, "Blood Thinners"),
    UPSET_STOMACH(structId = 447, bitIndex = 29, ToaInvocationCategory.ZEBAK, levelModifier = 15, "Upset Stomach"),
    DOUBLE_TROUBLE(structId = 448, bitIndex = 30, ToaInvocationCategory.AKKHA, levelModifier = 20, "Double Trouble"),
    KEEP_BACK(structId = 449, bitIndex = 31, ToaInvocationCategory.AKKHA, levelModifier = 10, "Keep Back"),
    STAY_VIGILANT(structId = 542, bitIndex = 32, ToaInvocationCategory.AKKHA, levelModifier = 15, "Stay Vigilant"),
    FEELING_SPECIAL(structId = 543, bitIndex = 33, ToaInvocationCategory.AKKHA, levelModifier = 20, "Feeling Special?"),
    MIND_THE_GAP(structId = 603, bitIndex = 34, ToaInvocationCategory.BA_BA, levelModifier = 10, "Mind the Gap!"),
    GOTTA_HAVE_FAITH(structId = 752, bitIndex = 35, ToaInvocationCategory.BA_BA, levelModifier = 10, "Gotta Have Faith"),
    JUNGLE_JAPES(structId = 949, bitIndex = 36, ToaInvocationCategory.BA_BA, levelModifier = 5, "Jungle Japes"),
    SHAKING_THINGS_UP(structId = 1275, bitIndex = 37, ToaInvocationCategory.BA_BA, levelModifier = 10, "Shaking Things Up"),
    BOULDERDASH(structId = 1276, bitIndex = 38, ToaInvocationCategory.BA_BA, levelModifier = 10, "Boulderdash"),
    ANCIENT_HASTE(structId = 1278, bitIndex = 39, ToaInvocationCategory.THE_WARDENS, levelModifier = 10, "Ancient Haste"),
    ACCELERATION(structId = 1688, bitIndex = 40, ToaInvocationCategory.THE_WARDENS, levelModifier = 10, "Acceleration"),
    PENETRATION(structId = 2874, bitIndex = 41, ToaInvocationCategory.THE_WARDENS, levelModifier = 10, "Penetration"),
    OVERCLOCKED(structId = 2933, bitIndex = 42, ToaInvocationCategory.THE_WARDENS, levelModifier = 10, "Overclocked"),
    OVERCLOCKED_2(structId = 2934, bitIndex = 43, ToaInvocationCategory.THE_WARDENS, levelModifier = 10, "Overclocked 2"),
    INSANITY(structId = 2971, bitIndex = 44, ToaInvocationCategory.THE_WARDENS, levelModifier = 50, "Insanity");

    companion object {
        private val byBitIndex = ToaInvocation.entries.associateBy(ToaInvocation::bitIndex)

        fun fromBitIndex(bitIndex: Int): ToaInvocation? = byBitIndex[bitIndex]
    }
}

/**
 * Port of NR/Zenyte `InvocationCategoryType.java`.
 *
 * Declaration order matches NR (`InvocationCategoryType.VALUES[raw - 3]`); [raw] is the cache
 * struct param 1161 value. Categories flagged [radio] are mutually exclusive — activating one
 * invocation of the category deactivates the others (`TOAManager.toggleInvocation` semantics,
 * enforced in [ToaPartySettings.toggle]).
 */
internal enum class ToaInvocationCategory(val raw: Int, val radio: Boolean) {
    ATTEMPTS(raw = 3, radio = true),
    TIME_LIMIT(raw = 4, radio = true),
    HELPFUL_SPIRIT(raw = 5, radio = true),
    PATH_LEVEL(raw = 6, radio = true),
    PRAYER(raw = 7, radio = false),
    RESTORATION(raw = 8, radio = false),
    PATHS(raw = 9, radio = false),
    AKKHA(raw = 10, radio = false),
    KEPHRI(raw = 11, radio = false),
    ZEBAK(raw = 12, radio = false),
    BA_BA(raw = 13, radio = false),
    THE_WARDENS(raw = 14, radio = false),
}

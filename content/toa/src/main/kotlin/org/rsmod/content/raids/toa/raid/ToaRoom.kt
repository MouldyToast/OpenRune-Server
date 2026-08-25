package org.rsmod.content.raids.toa.raid

import org.rsmod.game.map.Direction
import org.rsmod.map.CoordGrid

/**
 * The twelve Tombs of Amascut rooms, their source-map templates and their placement inside the
 * raid's single large instanced region.
 *
 * Port of NR/Zenyte `EncounterType.java` (room table), `TOAPathType.java` (paths),
 * `EncounterStage.java`, `ChallengeResult.java` and `BaseEncounterType.java`
 * (`com.zenyte.game.content.tombsofamascut.raid`).
 *
 * Coordinate model (differs from NR):
 * - NR allocated a fresh 8x8-chunk dynamic area per room and used absolute static-map
 *   `Location`s, translating per instance via `area.getLocation(...)`.
 * - This port packs ALL rooms into ONE large (40x40-zone) region — see `ToaLayout` — so every
 *   room coordinate here is stored as an OFFSET within the room's 8x8-zone (64x64-tile) box:
 *   `offset = absoluteTemplateCoord - templateChunk * 8` (NR chunkX/chunkY are zone units).
 *   Resolve to live instance coordinates with `ToaLayout.roomCoord(raid, room, offset)`.
 * - Offsets always carry `level = 0`; the room's [copyLevel] supplies the plane.
 *
 * NR-BUG-FIX: NR round-tripped rooms through `BaseEncounterType` for logout persistence and its
 * `fromBase` switch omitted `CRONDIS_BOSS`, restoring Zebak-room logouts at the main hall. This
 * port persists the room by enum name directly, so the mirror enum (and its bug) is dropped.
 *
 * @property copyZoneX Source template zone x (NR `chunkX` — already zone units, tile = x*8).
 * @property copyZoneZ Source template zone z (NR `chunkY`).
 * @property copyLevel Source template plane the room's gameplay is on (NR spawn-tile plane).
 * @property regionZoneX Placement zone-x offset inside the raid region (DESIGN.md layout).
 * @property regionZoneZ Placement zone-z offset inside the raid region (DESIGN.md layout).
 * @property playerSpawn Room-entry spawn tile offset (NR `spawnTile`).
 * @property spawnRandomX Uniform 0..n inclusive x-scatter applied to [playerSpawn] (NR
 *   `xRandomize`, `Utils.random(n)` semantics).
 * @property spawnRandomZ Uniform 0..n inclusive z-scatter applied to [playerSpawn].
 * @property soundTrack Music track name unlocked/played for the room (NR `soundTrack`).
 * @property challengeSpawn Challenge-side spawn offset (teleport-crystal destination; NR
 *   `challengeSpawnLocation`), or null for the main hall.
 * @property challengeMin Inclusive south-west corner offset of the challenge bbox (NR
 *   `minChallengeLocation`), or null when the room has no challenge area.
 * @property challengeMax Inclusive north-east corner offset of the challenge bbox (NR
 *   `maxChallengeLocation`).
 * @property teleportNpcSpawn Post-completion Osmumten (`npc.toa_osmumten_vis`) spawn offset (NR
 *   `npcLocation`), or null where NR spawns none.
 */
internal enum class ToaRoom(
    val copyZoneX: Int,
    val copyZoneZ: Int,
    val copyLevel: Int,
    val regionZoneX: Int,
    val regionZoneZ: Int,
    val playerSpawn: CoordGrid,
    val spawnRandomX: Int,
    val spawnRandomZ: Int,
    val soundTrack: String,
    val challengeSpawn: CoordGrid? = null,
    val challengeMin: CoordGrid? = null,
    val challengeMax: CoordGrid? = null,
    val teleportNpcSpawn: CoordGrid? = null,
) {
    /** NR `MAIN_HALL` — the nexus; path selection hub. Source zones (440,640), plane 0. */
    MAIN_HALL(
        copyZoneX = 440,
        copyZoneZ = 640,
        copyLevel = 0,
        regionZoneX = 0,
        regionZoneZ = 0,
        playerSpawn = CoordGrid(30, 41),
        spawnRandomX = 2,
        spawnRandomZ = 0,
        soundTrack = "Beneath Cursed Sands",
    ),

    /** NR `CRONDIS_PUZZLE` (CrondisPuzzleEncounter). Source zones (488,656), plane 0. */
    CRONDIS_PUZZLE(
        copyZoneX = 488,
        copyZoneZ = 656,
        copyLevel = 0,
        regionZoneX = 8,
        regionZoneZ = 0,
        playerSpawn = CoordGrid(50, 31),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "Test of Resourcefulness",
        challengeSpawn = CoordGrid(39, 32),
        challengeMin = CoordGrid(19, 2),
        challengeMax = CoordGrid(45, 63),
    ),

    /** NR `CRONDIS_BOSS` (ZebakEncounter). Source zones (488,672), plane 0. */
    ZEBAK_BOSS(
        copyZoneX = 488,
        copyZoneZ = 672,
        copyLevel = 0,
        regionZoneX = 16,
        regionZoneZ = 0,
        playerSpawn = CoordGrid(54, 31),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "Jaws of Gluttony",
        challengeSpawn = CoordGrid(37, 32),
        challengeMin = CoordGrid(0, 11),
        challengeMax = CoordGrid(58, 53),
        teleportNpcSpawn = CoordGrid(24, 32),
    ),

    /** NR `SCABARIS_PUZZLE` (ScabarasEncounter). Source zones (440,656), plane 0. */
    SCABARAS_PUZZLE(
        copyZoneX = 440,
        copyZoneZ = 656,
        copyLevel = 0,
        regionZoneX = 24,
        regionZoneZ = 0,
        playerSpawn = CoordGrid(2, 31),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "Test of Isolation",
        challengeSpawn = CoordGrid(55, 32),
        challengeMin = CoordGrid(13, 20),
        challengeMax = CoordGrid(54, 44),
    ),

    /** NR `SCABARIS_BOSS` (KephriEncounter). Source zones (440,672), plane 0. */
    KEPHRI_BOSS(
        copyZoneX = 440,
        copyZoneZ = 672,
        copyLevel = 0,
        regionZoneX = 0,
        regionZoneZ = 8,
        playerSpawn = CoordGrid(15, 32),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "A Mother's Curse",
        challengeSpawn = CoordGrid(24, 32),
        challengeMin = CoordGrid(23, 24),
        challengeMax = CoordGrid(39, 40),
        teleportNpcSpawn = CoordGrid(38, 32),
    ),

    /** NR `APMEKEN_PUZZLE` (ApmekenEncounter). Source zones (472,656), plane 0. */
    APMEKEN_PUZZLE(
        copyZoneX = 472,
        copyZoneZ = 656,
        copyLevel = 0,
        regionZoneX = 8,
        regionZoneZ = 8,
        playerSpawn = CoordGrid(16, 31),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "Test of Companionship",
        challengeSpawn = CoordGrid(38, 32),
        challengeMin = CoordGrid(21, 19),
        challengeMax = CoordGrid(43, 45),
    ),

    /** NR `APMEKEN_BOSS` (BabaEncounter). Source zones (472,672), plane 0. */
    BABA_BOSS(
        copyZoneX = 472,
        copyZoneZ = 672,
        copyLevel = 0,
        regionZoneX = 16,
        regionZoneZ = 8,
        playerSpawn = CoordGrid(14, 31),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "Ape-ex Predator",
        challengeSpawn = CoordGrid(24, 32),
        challengeMin = CoordGrid(20, 23),
        challengeMax = CoordGrid(47, 42),
        teleportNpcSpawn = CoordGrid(41, 32),
    ),

    /** NR `HET_PUZZLE` (HetEncounter). Source zones (456,656), plane 0. */
    HET_PUZZLE(
        copyZoneX = 456,
        copyZoneZ = 656,
        copyLevel = 0,
        regionZoneX = 24,
        regionZoneZ = 8,
        playerSpawn = CoordGrid(50, 31),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "Test of Strength",
        challengeSpawn = CoordGrid(19, 32),
        challengeMin = CoordGrid(22, 19),
        challengeMax = CoordGrid(42, 45),
    ),

    /** NR `HET_BOSS` (AkkhaEncounter). Source zones (456,672), plane 1 (gameplay plane). */
    AKKHA_BOSS(
        copyZoneX = 456,
        copyZoneZ = 672,
        copyLevel = 1,
        regionZoneX = 0,
        regionZoneZ = 16,
        playerSpawn = CoordGrid(50, 30),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        soundTrack = "Sands of Time",
        challengeSpawn = CoordGrid(41, 32),
        challengeMin = CoordGrid(22, 19),
        challengeMax = CoordGrid(43, 43),
        teleportNpcSpawn = CoordGrid(25, 31),
    ),

    /** NR `WARDENS_FIRST_ROOM` (WardenEncounter). Source zones (472,640), plane 1. */
    WARDENS_FIRST_ROOM(
        copyZoneX = 472,
        copyZoneZ = 640,
        copyLevel = 1,
        regionZoneX = 8,
        regionZoneZ = 16,
        playerSpawn = CoordGrid(31, 56),
        spawnRandomX = 2,
        spawnRandomZ = 0,
        soundTrack = "Amascut's Promise",
        challengeSpawn = CoordGrid(32, 46),
        challengeMin = CoordGrid(16, 17),
        challengeMax = CoordGrid(49, 51),
        teleportNpcSpawn = CoordGrid(32, 38),
    ),

    /** NR `WARDENS_SECOND_ROOM` (SecondWardenEncounter). Source zones (488,640), plane 1. */
    WARDENS_SECOND_ROOM(
        copyZoneX = 488,
        copyZoneZ = 640,
        copyLevel = 1,
        regionZoneX = 16,
        regionZoneZ = 16,
        playerSpawn = CoordGrid(31, 48),
        spawnRandomX = 2,
        spawnRandomZ = 0,
        soundTrack = "Amascut's Promise",
        challengeSpawn = CoordGrid(32, 37),
        challengeMin = CoordGrid(20, 31),
        challengeMax = CoordGrid(43, 46),
    ),

    /** NR `REWARD_ROOM` (RewardEncounter). Source zones (456,640), plane 0. */
    REWARD_ROOM(
        copyZoneX = 456,
        copyZoneZ = 640,
        copyLevel = 0,
        regionZoneX = 24,
        regionZoneZ = 16,
        playerSpawn = CoordGrid(32, 50),
        spawnRandomX = 0,
        spawnRandomZ = 0,
        soundTrack = "Laid to Rest",
        challengeSpawn = CoordGrid(32, 50),
        challengeMin = CoordGrid(32, 50),
        challengeMax = CoordGrid(32, 50),
        teleportNpcSpawn = CoordGrid(32, 23),
    );

    /** `true` for the four path puzzle rooms (NR `TOARaidArea.isPuzzleEncounter`). */
    val isPuzzle: Boolean
        get() = this == CRONDIS_PUZZLE || this == SCABARAS_PUZZLE ||
            this == APMEKEN_PUZZLE || this == HET_PUZZLE

    /** `true` for the four path boss rooms (the Wardens rooms are not "path bosses"). */
    val isPathBoss: Boolean
        get() = this == ZEBAK_BOSS || this == KEPHRI_BOSS || this == BABA_BOSS || this == AKKHA_BOSS

    /** The path this room belongs to, or null (main hall, Wardens, reward room). */
    val path: ToaPath?
        get() =
            when (this) {
                CRONDIS_PUZZLE, ZEBAK_BOSS -> ToaPath.CRONDIS
                SCABARAS_PUZZLE, KEPHRI_BOSS -> ToaPath.SCABARAS
                APMEKEN_PUZZLE, BABA_BOSS -> ToaPath.APMEKEN
                HET_PUZZLE, AKKHA_BOSS -> ToaPath.HET
                else -> null
            }

    /**
     * The room `advanceRaid` moves the party to from this room, or null when advancement is
     * routed elsewhere (path bosses return to [MAIN_HALL]; the main hall routes via
     * [ToaPath.firstRoom]).
     *
     * NR computed this as `EncounterType.VALUES[ordinal + 1]` (`TOAManager.advanceRaid`); this
     * enum's declaration order differs from NR's, so the links are explicit: puzzle -> its
     * boss, Wardens 1 -> Wardens 2 -> reward room — exactly the transitions NR's ordinal
     * arithmetic was ever used for.
     */
    val next: ToaRoom?
        get() =
            when (this) {
                CRONDIS_PUZZLE -> ZEBAK_BOSS
                SCABARAS_PUZZLE -> KEPHRI_BOSS
                APMEKEN_PUZZLE -> BABA_BOSS
                HET_PUZZLE -> AKKHA_BOSS
                WARDENS_FIRST_ROOM -> WARDENS_SECOND_ROOM
                WARDENS_SECOND_ROOM -> REWARD_ROOM
                else -> null
            }

    /**
     * Boss challenge display name (NR `TOARaidArea.getCurrentChallengeName(path = false)`), or
     * null for non-boss rooms. Puzzle challenges display as `"Path of <path>"` instead.
     */
    val bossDisplayName: String?
        get() =
            when (this) {
                ZEBAK_BOSS -> "Zebak"
                KEPHRI_BOSS -> "Kephri"
                BABA_BOSS -> "Ba-Ba"
                AKKHA_BOSS -> "Akkha"
                WARDENS_FIRST_ROOM, WARDENS_SECOND_ROOM -> "The Wardens"
                else -> null
            }

    companion object {
        /** Every room spans 8x8 zones (64x64 tiles) — NR `MapBuilder.findEmptyChunk(8, 8)`. */
        const val ROOM_ZONE_SPAN: Int = 8

        /**
         * Zebak's water-lane exclusion rectangles: NR `CRONDIS_BOSS.insideChallengeArea`
         * returns false inside these (inclusive min/max offset pairs, checked BEFORE the
         * challenge bbox test). Absolute NR rects: (3957..3960, 5404..5414) and
         * (3952..3957, 5406..5410).
         */
        val ZEBAK_CHALLENGE_EXCLUSIONS: List<Pair<CoordGrid, CoordGrid>> =
            listOf(
                CoordGrid(53, 28) to CoordGrid(56, 38),
                CoordGrid(48, 30) to CoordGrid(53, 34),
            )
    }
}

/**
 * The four main-hall paths. Port of NR/Zenyte `TOAPathType.java`.
 *
 * Declaration order MUST stay NR's ordinal order (APMEKEN, SCABARAS, HET, CRONDIS): the raid's
 * path-level array (`ToaRaid.pathLevels`) is indexed by this ordinal, mirroring NR
 * `TOARaidParty.bossLevels`. Note the HUD path-level varbits are declared in a DIFFERENT order
 * (crondis, scabaras, het, apmeken) — see `ToaConstants.VARBIT_PATH_LEVELS`.
 *
 * All coordinates are OFFSETS within the [ToaRoom.MAIN_HALL] 8x8-zone box (NR stored them as
 * absolute main-hall template coords; offset = absolute - (440*8, 640*8)).
 *
 * @property properName Display name ("Path of <properName>").
 * @property entrance Path entrance tile offset in the main hall (NR `entranceLocation`).
 * @property mainHallSpawn Main-hall re-entry spawn offset while this path is active (NR
 *   `spawnTile`).
 * @property spawnRandomX Uniform 0..n inclusive x-scatter on [mainHallSpawn] (NR `xRandomize`).
 * @property spawnRandomZ Uniform 0..n inclusive z-scatter on [mainHallSpawn].
 * @property faceDirection Direction players face on main-hall re-entry (NR `faceDirection`).
 */
internal enum class ToaPath(
    val properName: String,
    val entrance: CoordGrid,
    val mainHallSpawn: CoordGrid,
    val spawnRandomX: Int,
    val spawnRandomZ: Int,
    val faceDirection: Direction,
) {
    APMEKEN(
        properName = "Apmeken",
        entrance = CoordGrid(42, 26),
        mainHallSpawn = CoordGrid(41, 26),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        faceDirection = Direction.West,
    ),
    SCABARAS(
        properName = "Scabaras",
        entrance = CoordGrid(39, 35),
        mainHallSpawn = CoordGrid(38, 34),
        spawnRandomX = 0,
        spawnRandomZ = 0,
        faceDirection = Direction.SouthWest,
    ),
    HET(
        properName = "Het",
        entrance = CoordGrid(19, 26),
        mainHallSpawn = CoordGrid(21, 26),
        spawnRandomX = 0,
        spawnRandomZ = 2,
        faceDirection = Direction.East,
    ),
    CRONDIS(
        properName = "Crondis",
        entrance = CoordGrid(21, 35),
        mainHallSpawn = CoordGrid(24, 34),
        spawnRandomX = 0,
        spawnRandomZ = 0,
        faceDirection = Direction.SouthEast,
    );

    /**
     * The first room entered when this path is selected.
     *
     * NR-PARITY: NR's `SCABARAS.firstEncounter` is `SCABARIS_BOSS` (Kephri), not the puzzle —
     * inconsistent with the other three paths. Kept verbatim; the main-hall path-routing code
     * (Session 2) must verify against NR room code before "fixing".
     */
    val firstRoom: ToaRoom
        get() =
            when (this) {
                APMEKEN -> ToaRoom.APMEKEN_PUZZLE
                SCABARAS -> ToaRoom.KEPHRI_BOSS
                HET -> ToaRoom.HET_PUZZLE
                CRONDIS -> ToaRoom.CRONDIS_PUZZLE
            }

    companion object {
        /** NR `TOAPathType.getForIndex(int)`. */
        fun forIndex(index: Int): ToaPath = entries[index]
    }
}

/** Room challenge lifecycle. Port of NR/Zenyte `EncounterStage.java`. */
internal enum class ToaRoomStage {
    NOT_STARTED,
    STARTED,
    COMPLETED,
}

/**
 * A completed room challenge's result. Port of NR/Zenyte `ChallengeResult.java`.
 *
 * @property timeTicks Challenge duration in game ticks (completion tick - challenge start tick).
 * @property mvpName MVP username — always null in NR's foundation (kept for parity/later use).
 * @property room The room the challenge was completed in.
 */
internal data class ToaChallengeResult(
    val timeTicks: Int,
    val mvpName: String?,
    val room: ToaRoom,
)

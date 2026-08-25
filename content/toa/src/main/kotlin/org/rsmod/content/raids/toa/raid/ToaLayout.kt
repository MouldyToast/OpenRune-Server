package org.rsmod.content.raids.toa.raid

import org.rsmod.api.repo.region.RegionStaticTemplate
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid

/**
 * Builds the single large-region template holding ALL twelve Tombs of Amascut rooms, and
 * resolves room-relative offsets to live instance coordinates.
 *
 * Port of the map-construction side of NR/Zenyte `TOARaidParty.constructEncounter` +
 * `EncounterType` chunk sources (`com.zenyte.game.content.tombsofamascut.raid`): where NR
 * allocated a fresh 8x8-chunk dynamic area per room (`MapBuilder.findEmptyChunk(8, 8)`), this
 * port packs every room into ONE 40x40-zone large region so room transitions are plain
 * in-region telejumps (see DESIGN.md architecture).
 *
 * Region layout (each cell is one 8x8-zone room; offsets are region zone x/z, all 4 source
 * planes copied via `copyAllLevels`):
 * ```
 *   z=16 | AKKHA       WARDENS_1  WARDENS_2  REWARD
 *   z=8  | KEPHRI      APMEKEN_P  BABA       HET_P
 *   z=0  | MAIN_HALL   CRONDIS_P  ZEBAK      SCABARAS_P
 *        +------------------------------------------------
 *          x=0          x=8        x=16       x=24
 * ```
 *
 * Each room occupies a unique (x,z) grid position so `copyAllLevels` can copy all 4 source
 * planes without collision. NR's `DynamicArea.constructRegion` copies all planes; the original
 * level-stacked layout forced single-plane copies because rooms shared (x,z) positions across
 * region levels. No two rooms copy the same source zones, so no `uniqueFlag` disambiguation
 * is needed.
 */
internal object ToaLayout {

    /**
     * The raid's region template: one 8x8-zone copy block per [ToaRoom]. Built once; safe to
     * reuse across raids (`RegionTemplate.build` translates a fresh copy per region add).
     */
    private val TEMPLATE: RegionStaticTemplate = RegionTemplate.createLarge {
        for (room in ToaRoom.entries) {
            place(room)
        }
    }

    fun template(): RegionStaticTemplate = TEMPLATE

    private fun RegionStaticTemplate.place(room: ToaRoom) {
        copyAllLevels(room.copyZoneX, room.copyZoneZ) {
            zoneWidth = ToaRoom.ROOM_ZONE_SPAN
            zoneLength = ToaRoom.ROOM_ZONE_SPAN
            regionZoneX = room.regionZoneX
            regionZoneZ = room.regionZoneZ
        }
    }

    /**
     * Resolves a room-relative [offset] (see [ToaRoom] coordinate model; `x`/`z` in `0..63`,
     * `level` ignored — the room's [ToaRoom.copyLevel] supplies the plane) to a live coordinate
     * inside [raid]'s instanced region.
     *
     * The raid's `Region` handle is resolved once at raid creation via
     * `InstanceManager.regionForId(session.id)` and carried on [ToaRaid.region].
     */
    fun roomCoord(raid: ToaRaid, room: ToaRoom, offset: CoordGrid): CoordGrid =
        roomCoord(raid.region, room, offset)

    /** [roomCoord] against an explicit [region] (for use before the [ToaRaid] is built). */
    fun roomCoord(region: Region, room: ToaRoom, offset: CoordGrid): CoordGrid {
        val normal =
            CoordGrid(
                x = room.copyZoneX * ZONE_TILE_SPAN + offset.x,
                z = room.copyZoneZ * ZONE_TILE_SPAN + offset.z,
                level = room.copyLevel,
            )
        return region.normal[normal]
    }

    private const val ZONE_TILE_SPAN: Int = 8
}

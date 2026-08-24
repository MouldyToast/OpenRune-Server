package org.rsmod.api.death

import org.rsmod.game.entity.Player

/**
 * Set-bound hook consulted by [PlayerDeath.death] BEFORE the standard death sequence runs.
 *
 * If any hook returns `true`, the standard sequence is skipped entirely - no death animation, no
 * item drops and no respawn teleport - leaving the hook's owner responsible for the full death
 * flow (e.g. the Tombs of Amascut ghost/respawn sequence, ported from NR
 * `nr_toa_foundation/TOARaidArea.java` (`sendDeath`) and `nr_toa_foundation/TOAManager.java`).
 *
 * Bound via `newSetBinding<PlayerDeathSequenceHook>()` in `DeathDropHooksModule` (empty by
 * default); content registers implementations with
 * `addSetBinding<PlayerDeathSequenceHook>(Impl::class.java)` from a `PluginModule`.
 */
public interface PlayerDeathSequenceHook {
    public fun overrideDeath(player: Player): Boolean
}

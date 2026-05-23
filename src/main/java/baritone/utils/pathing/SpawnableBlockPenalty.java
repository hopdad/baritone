/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.pathing;

import baritone.Baritone;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.precompute.Ternary;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-node spawnable-block cost penalty for the A* pathfinder.
 *
 * <p>A position {@code (x, y, z)} is classified as a <em>potential mob-spawn surface</em> when:
 * <ol>
 *   <li>The floor block at {@code (x, y-1, z)} is solid enough for the player to stand on
 *       (i.e. {@link MovementHelper#canWalkOnBlockState} returns {@link Ternary#YES} — MAYBE
 *       blocks such as water are excluded), and</li>
 *   <li>The block-light level at {@code (x, y, z)} is ≤ 7 — the vanilla mob-spawn threshold.</li>
 * </ol>
 *
 * <p>When both conditions hold, {@link #penalty(int, int, int)} returns the configured
 * {@link baritone.api.Settings#spawnableBlockAvoidanceCoefficient coefficient} (default 1.5),
 * steering the pathfinder away from dark corridors where hostile mobs could ambush the player.
 * Well-lit positions and positions with non-solid floors always return {@code 1.0} (no penalty).
 *
 * <p><b>Threading:</b> instances of this class are created on the main thread (inside
 * {@link baritone.utils.pathing.Favoring}'s constructor) and then used on the pathfinding
 * thread during A*. The {@link BlockPos.MutableBlockPos} used for light-level queries is stored
 * in a {@link ThreadLocal} so no synchronisation is needed.
 *
 * @see Favoring
 * @see baritone.api.Settings#spawnableBlockAvoidance
 * @see baritone.api.Settings#spawnableBlockAvoidanceCoefficient
 */
public final class SpawnableBlockPenalty {

    /**
     * Mob-spawn block-light threshold. In Minecraft 1.18+ hostile mobs spawn only at block-light 0;
     * we use 1 as the threshold to maintain a one-level safety margin (positions with block-light 0
     * or 1 are penalised). This matches {@link baritone.api.Settings#torchPlacementLightThreshold}.
     */
    private static final int SPAWN_LIGHT_THRESHOLD = 1;

    private final Level world;
    private final BlockStateInterface bsi;

    /**
     * Per-thread mutable position to avoid allocations during the A* hot path.
     * Only one thread (the pathing thread) calls {@link #penalty} concurrently,
     * but {@code ThreadLocal} is used defensively in case that assumption changes.
     */
    private final ThreadLocal<BlockPos.MutableBlockPos> mutablePos =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * Creates a new penalty evaluator for the given world and block-state accessor.
     *
     * @param world the level used for block-light lookups
     * @param bsi   the block-state interface for floor-block queries
     */
    public SpawnableBlockPenalty(Level world, BlockStateInterface bsi) {
        this.world = world;
        this.bsi   = bsi;
    }

    /**
     * Returns the cost multiplier for A* node {@code (x, y, z)}.
     *
     * <p>Returns {@link baritone.api.Settings#spawnableBlockAvoidanceCoefficient} when the
     * position sits on a potential mob-spawn surface; returns {@code 1.0} otherwise.
     *
     * @param x block X coordinate of the node (player feet position)
     * @param y block Y coordinate of the node (player feet position)
     * @param z block Z coordinate of the node (player feet position)
     * @return cost multiplier ≥ 1.0; never negative
     */
    public double penalty(int x, int y, int z) {
        try {
            // Check floor: must be solid enough to stand on (YES only — MAYBE blocks like water
            // are not considered stable spawn surfaces and can skew pathfinding negatively).
            BlockState floor = bsi.get0(x, y - 1, z);
            if (MovementHelper.canWalkOnBlockState(floor) != Ternary.YES) {
                return 1.0D;
            }

            // Check block-light at the node itself — this is where the mob would stand.
            int blockLight = world.getBrightness(LightLayer.BLOCK, mutablePos.get().set(x, y, z));
            if (blockLight > SPAWN_LIGHT_THRESHOLD) {
                return 1.0D; // well-lit: mobs cannot spawn here
            }

            return Baritone.settings().spawnableBlockAvoidanceCoefficient.value;
        } catch (Exception ignored) {
            // Defensive catch for out-of-bounds or unloaded-chunk edge cases on the pathing thread.
            return 1.0D;
        }
    }
}

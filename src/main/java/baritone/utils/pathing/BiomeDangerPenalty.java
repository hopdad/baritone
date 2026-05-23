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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-node biome danger penalty for the A* pathfinder.
 *
 * <p>Checks the biome at each evaluated A* node against a configurable set of
 * "dangerous" biome IDs (resource locations such as {@code "minecraft:deep_dark"}).
 * When a match is found, {@link #penalty(int, int, int)} returns
 * {@link baritone.api.Settings#dangerousBiomeCoefficient}, causing the pathfinder to
 * treat routes through those biomes as more expensive and prefer alternatives.
 *
 * <p><b>Typical use-cases:</b>
 * <ul>
 *   <li>{@code minecraft:deep_dark} — avoid Warden territory unless explicitly going there</li>
 *   <li>{@code minecraft:basalt_deltas} — avoid difficult Nether terrain</li>
 *   <li>Any biome where the player knows spawns are especially dangerous or resources are scarce</li>
 * </ul>
 *
 * <p><b>Threading:</b> instances are constructed on the main thread and then evaluated per-node
 * on the pathfinding thread. The {@link BlockPos.MutableBlockPos} is stored in a {@link ThreadLocal}
 * for allocation-free lookups. The biome set is read-only after construction.
 *
 * @see baritone.api.Settings#dangerousBiomeAvoidance
 * @see baritone.api.Settings#dangerousBiomeCoefficient
 * @see baritone.api.Settings#dangerousBiomes
 */
public final class BiomeDangerPenalty {

    private final Level world;

    /**
     * Immutable set of biome resource-location strings (e.g. {@code "minecraft:deep_dark"})
     * that should incur a pathing penalty. Built once at construction from the settings string.
     */
    private final Set<String> dangerBiomeIds;

    /** Per-thread mutable position for zero-allocation biome lookups on the pathing thread. */
    private final ThreadLocal<BlockPos.MutableBlockPos> mutablePos =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * Creates a new biome penalty evaluator, parsing the comma-separated
     * {@link baritone.api.Settings#dangerousBiomes} setting at construction time.
     *
     * @param world the level used for biome queries
     */
    public BiomeDangerPenalty(Level world) {
        this.world = world;
        String raw = Baritone.settings().dangerousBiomes.value.trim();
        if (raw.isEmpty()) {
            this.dangerBiomeIds = Collections.emptySet();
        } else {
            String[] parts = raw.split("\\s*,\\s*");
            Set<String> ids = new HashSet<>(parts.length * 2);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    ids.add(trimmed);
                }
            }
            this.dangerBiomeIds = Collections.unmodifiableSet(ids);
        }
    }

    /**
     * Returns {@code true} when this evaluator has no biomes to penalise.
     * Allows {@link baritone.utils.pathing.Favoring} to skip construction entirely.
     */
    public boolean isEmpty() {
        return dangerBiomeIds.isEmpty();
    }

    /**
     * Returns the cost multiplier for A* node {@code (x, y, z)}.
     *
     * <p>Returns {@link baritone.api.Settings#dangerousBiomeCoefficient} when the node is inside
     * one of the configured dangerous biomes; returns {@code 1.0} otherwise.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return cost multiplier ≥ 1.0; never negative
     */
    public double penalty(int x, int y, int z) {
        if (dangerBiomeIds.isEmpty()) {
            return 1.0D;
        }
        try {
            var biomeHolder = world.getBiome(mutablePos.get().set(x, y, z));
            String biomeId = biomeHolder.unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("");
            return dangerBiomeIds.contains(biomeId)
                    ? Baritone.settings().dangerousBiomeCoefficient.value
                    : 1.0D;
        } catch (Exception ignored) {
            // Defensive catch for unloaded biomes or unexpected API failures on the pathing thread.
            return 1.0D;
        }
    }
}

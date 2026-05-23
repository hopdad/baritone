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
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import java.util.Collections;
import java.util.List;

/**
 * Combines all cost-multiplier signals that steer the pathfinder toward or away from
 * particular positions without altering the underlying movement costs.
 *
 * <p>Two independent signals are blended here:
 * <ol>
 *   <li><b>Backtrack favoring</b> — positions that were on the previous path receive a
 *       coefficient less than {@code 1.0} (cheaper to re-use them) to encourage the planner
 *       to prefer a route it already knows. Stored as a sparse hash map keyed by position
 *       hash, since only a few hundred positions on the old path are tracked.</li>
 *   <li><b>Mob/spawner avoidance</b> — spheres of influence around hostile entities and
 *       spawners raise the cost of nodes near those sources. Evaluated lazily per-node
 *       during A* (rather than pre-rasterized) so that the work moves onto the pathing
 *       thread and is proportional to nodes actually explored, not sphere volume.</li>
 * </ol>
 *
 * @see Avoidance
 * @see MobDangerProfile
 */
public final class Favoring {

    /**
     * Backtrack favoring keyed by position hash. Positions on the previous path
     * are sparse, so a hash map is the natural representation.
     */
    private final Long2DoubleOpenHashMap favorings;

    /**
     * Mob and spawner avoidance spheres. Evaluated on demand rather than
     * pre-rasterized into {@link #favorings}: building the spheres up front is
     * O(radius^3) per source and ran on the main thread, causing a noticeable
     * hitch when many mobs were nearby. Evaluating them lazily moves that work
     * onto the pathfinding thread and only pays for nodes actually explored.
     */
    private final Avoidance[] avoidances;

    /**
     * Per-node spawnable-block penalty, or {@code null} when
     * {@link baritone.api.Settings#spawnableBlockAvoidance} is disabled.
     * Penalises dark, solid-floored positions where hostile mobs can spawn.
     */
    private final SpawnableBlockPenalty spawnPenalty;

    /**
     * Per-node biome danger penalty, or {@code null} when
     * {@link baritone.api.Settings#dangerousBiomeAvoidance} is disabled or the biome list is empty.
     * Penalises nodes inside user-configured dangerous biomes.
     */
    private final BiomeDangerPenalty biomePenalty;

    /**
     * Full constructor: applies both backtrack favoring from a previous path and mob/spawner
     * avoidance spheres derived from the current world state.
     *
     * @param ctx      the player context used to enumerate nearby entities and spawners
     * @param previous the path segment just executed, whose positions will be made cheaper
     *                 to re-traverse; may be {@code null} to skip backtrack favoring
     * @param context  the calculation context, supplies the backtrack coefficient
     */
    public Favoring(IPlayerContext ctx, IPath previous, CalculationContext context) {
        this(previous, context, Avoidance.create(ctx));
    }

    /**
     * Backtrack-only constructor: applies previous-path favoring but no mob avoidance.
     * Useful when the caller knows avoidance is disabled or irrelevant.
     *
     * @param previous the path segment just executed; may be {@code null}
     * @param context  the calculation context, supplies the backtrack coefficient
     */
    public Favoring(IPath previous, CalculationContext context) {
        this(previous, context, Collections.emptyList());
    }

    private Favoring(IPath previous, CalculationContext context, List<Avoidance> avoidances) {
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        double coeff = context.backtrackCostFavoringCoefficient;
        if (coeff != 1D && previous != null) {
            previous.positions().forEach(pos -> favorings.put(BetterBlockPos.longHash(pos), coeff));
        }
        this.avoidances = avoidances.toArray(new Avoidance[0]);
        // Create the spawnable-block penalty evaluator only when the feature is enabled and the
        // coefficient actually penalises something — avoids allocating ThreadLocal state needlessly.
        if (Baritone.settings().avoidance.value
                && Baritone.settings().spawnableBlockAvoidance.value
                && Baritone.settings().spawnableBlockAvoidanceCoefficient.value > 1.0D) {
            this.spawnPenalty = new SpawnableBlockPenalty(context.world, context.bsi);
        } else {
            this.spawnPenalty = null;
        }
        // Create the biome danger penalty evaluator if the feature is enabled.
        if (Baritone.settings().avoidance.value
                && Baritone.settings().dangerousBiomeAvoidance.value
                && Baritone.settings().dangerousBiomeCoefficient.value > 1.0D) {
            BiomeDangerPenalty bp = new BiomeDangerPenalty(context.world);
            this.biomePenalty = bp.isEmpty() ? null : bp;
        } else {
            this.biomePenalty = null;
        }
        Helper.HELPER.logDebug("Favoring size: " + favorings.size() + ", avoidances: " + this.avoidances.length
                + ", spawnPenalty: " + (this.spawnPenalty != null)
                + ", biomePenalty: " + (this.biomePenalty != null));
    }

    /**
     * Returns {@code true} when this instance has no adjustments to apply.
     * When empty, the A* cost multiplier is always {@code 1.0} and this object
     * can be skipped entirely by the caller.
     */
    public boolean isEmpty() {
        return favorings.isEmpty() && avoidances.length == 0 && spawnPenalty == null && biomePenalty == null;
    }

    /**
     * Returns the combined cost multiplier for the position at {@code (x, y, z)}.
     *
     * <p>The result is the product of:
     * <ul>
     *   <li>the backtrack coefficient stored for this position's {@code hash} (defaults
     *       to {@code 1.0} if the position is not on the previous path), and</li>
     *   <li>every avoidance sphere whose centre is within {@code radius} blocks of the
     *       position (each independently multiplicative).</li>
     * </ul>
     *
     * <p>A return value less than {@code 1.0} means the node is preferred (backtrack
     * favoring); greater than {@code 1.0} means it is penalised (avoidance).
     *
     * @param hash the result of {@link baritone.api.utils.BetterBlockPos#longHash(int, int, int)}
     *             for {@code (x, y, z)} — pre-computed by the caller to avoid redundant work
     * @param x    block X coordinate
     * @param y    block Y coordinate
     * @param z    block Z coordinate
     * @return the combined cost multiplier; always positive
     */
    public double calculate(long hash, int x, int y, int z) {
        double result = favorings.get(hash);
        for (Avoidance avoidance : avoidances) {
            result *= avoidance.coefficient(x, y, z);
        }
        if (spawnPenalty != null) {
            result *= spawnPenalty.penalty(x, y, z);
        }
        if (biomePenalty != null) {
            result *= biomePenalty.penalty(x, y, z);
        }
        return result;
    }
}

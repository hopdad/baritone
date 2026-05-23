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

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import java.util.Collections;
import java.util.List;

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

    public Favoring(IPlayerContext ctx, IPath previous, CalculationContext context) {
        this(previous, context, Avoidance.create(ctx));
    }

    public Favoring(IPath previous, CalculationContext context) { // create one just from previous path, no mob avoidances
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
        Helper.HELPER.logDebug("Favoring size: " + favorings.size() + ", avoidances: " + this.avoidances.length);
    }

    public boolean isEmpty() {
        return favorings.isEmpty() && avoidances.length == 0;
    }

    public double calculate(long hash, int x, int y, int z) {
        double result = favorings.get(hash);
        for (Avoidance avoidance : avoidances) {
            result *= avoidance.coefficient(x, y, z);
        }
        return result;
    }
}

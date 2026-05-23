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
import baritone.api.IBaritone;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks a temporary "lingering threat zone" after {@link baritone.process.CombatFleeProcess}
 * ends naturally.
 *
 * <h3>Purpose</h3>
 * When the flee ends and the previous task (mining, building, …) resumes, the pathfinder would
 * normally calculate the shortest route back to the goal — which often passes right through the
 * area where the mob was.  By registering a high-cost {@link Avoidance} sphere around the last
 * recorded threat position, we make the pathfinder prefer a detour, approaching the same work
 * area from a different angle instead of walking back into the mob.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>When flee ends normally, {@link #register} is called with the last recorded mob position
 *       and the current world tick.  The zone expires after
 *       {@link baritone.api.Settings#postFleeAvoidDurationTicks} ticks.</li>
 *   <li>{@link Avoidance#create} calls {@link #get} on every path calculation.  If a non-expired
 *       zone exists, the returned {@link Avoidance} is added to the avoidance list passed to
 *       {@link Favoring}.</li>
 *   <li>Zones are eagerly cleaned up on expiry and on {@link #clear} (flee reset / cancel).</li>
 * </ol>
 *
 * <h3>Multi-instance safety</h3>
 * The registry is keyed by {@link IBaritone} instance reference so multiple Baritone instances
 * (split-screen / multi-player scenarios) each maintain their own independent zone.
 *
 * @see baritone.api.Settings#postFleeAvoidance
 * @see baritone.api.Settings#postFleeAvoidRadius
 * @see baritone.api.Settings#postFleeAvoidanceCoefficient
 * @see baritone.api.Settings#postFleeAvoidDurationTicks
 */
public final class PostFleeThreatZone {

    /**
     * Per-Baritone-instance registry.  Uses {@code ConcurrentHashMap} so that the pathing thread
     * (which calls {@link #get}) and the game thread (which calls {@link #register} /
     * {@link #clear}) don't race.
     */
    private static final Map<IBaritone, PostFleeThreatZone> REGISTRY = new ConcurrentHashMap<>();

    private final BlockPos center;
    private final long expiryTick; // world tick at which this zone expires

    private PostFleeThreatZone(BlockPos center, long expiryTick) {
        this.center     = center;
        this.expiryTick = expiryTick;
    }

    // -------------------------------------------------------------------------
    // Static API
    // -------------------------------------------------------------------------

    /**
     * Registers a lingering threat zone for {@code key}.  Called by
     * {@link baritone.process.CombatFleeProcess} when it determines that the flee ended safely.
     *
     * @param key       the owning {@link IBaritone} instance
     * @param center    world-space block position of the last known threat
     * @param worldTick current {@code world.getGameTime()} value
     */
    public static void register(IBaritone key, BlockPos center, long worldTick) {
        long expiry = worldTick + Baritone.settings().postFleeAvoidDurationTicks.value;
        REGISTRY.put(key, new PostFleeThreatZone(center, expiry));
    }

    /**
     * Removes any active zone for {@code key}.  Called when flee is cancelled or reset
     * so that a forced stop doesn't leave a stale zone that confuses the next path calculation.
     */
    public static void clear(IBaritone key) {
        REGISTRY.remove(key);
    }

    /**
     * Returns an {@link Avoidance} for the active zone if one exists and has not expired.
     * Expired entries are removed eagerly.
     *
     * @param key         the owning {@link IBaritone} instance
     * @param currentTick current {@code world.getGameTime()} value
     * @return an {@link Optional} containing an {@link Avoidance} sphere centred on the last
     *         threat position, or {@link Optional#empty()} if no active zone exists
     */
    public static Optional<Avoidance> get(IBaritone key, long currentTick) {
        PostFleeThreatZone zone = REGISTRY.get(key);
        if (zone == null) {
            return Optional.empty();
        }
        if (currentTick > zone.expiryTick) {
            REGISTRY.remove(key);
            return Optional.empty();
        }
        double coeff  = Baritone.settings().postFleeAvoidanceCoefficient.value;
        int    radius = Baritone.settings().postFleeAvoidRadius.value;
        return Optional.of(new Avoidance(zone.center, coeff, radius));
    }
}

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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.pathing.MobDangerProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Interrupts the current goal and flees from hostile mobs whenever the player takes damage.
 *
 * <h3>Activation</h3>
 * <p>{@link #isActive()} is called every game tick by
 * {@link baritone.utils.PathingControlManager#executeProcesses()}. It samples the player's health
 * and sets {@code fleeing = true} as soon as a health drop is detected. While {@code fleeing}
 * is {@code true}, this process is included in the active-process list and (because its
 * {@link #priority()} of {@value PRIORITY} beats every other built-in process) wins control
 * every tick.
 *
 * <h3>Flee goal</h3>
 * <p>Each tick, the nearest currently-hostile mob is located and a
 * {@link GoalRunAway} goal away from it is issued via
 * {@link PathingCommandType#REVALIDATE_GOAL_AND_PATH}. The goal revalidates continuously so
 * that if the bot reaches the safe distance or the threat moves, the path is recalculated
 * automatically.
 *
 * <h3>Deactivation</h3>
 * <p>The flee ends when health has been stable (no drop) AND no hostile mob is within
 * {@link baritone.api.Settings#fleeDistance} blocks for
 * {@link baritone.api.Settings#fleeStableHealthTicks} consecutive ticks.
 *
 * <h3>Priority</h3>
 * <p>Priority {@value PRIORITY} places this above {@link InventoryPauserProcess} (5.1) and
 * {@link BackfillProcess} (5.0) so that survival takes precedence over any ongoing task.
 *
 * @see baritone.api.Settings#fleeWhenAttacked
 * @see baritone.api.Settings#fleeDistance
 * @see baritone.api.Settings#fleeStableHealthTicks
 * @see baritone.api.Settings#fleePreserveY
 */
public final class CombatFleeProcess extends BaritoneProcessHelper {

    /** Priority of this process — above all other built-in processes. */
    private static final double PRIORITY = 10.0D;

    /** Last observed player health; {@code -1} signals "not yet initialised". */
    private float prevHealth = -1.0f;

    /** Whether the process is currently in flee mode. */
    private boolean fleeing = false;

    /**
     * Number of consecutive ticks with no health drop and no nearby threat while fleeing.
     * When this reaches {@link baritone.api.Settings#fleeStableHealthTicks} the flee ends.
     */
    private int stableHealthTicks = 0;

    public CombatFleeProcess(Baritone baritone) {
        super(baritone);
    }

    // -----------------------------------------------------------------------
    // IBaritoneProcess implementation
    // -----------------------------------------------------------------------

    /**
     * Called every tick by {@link baritone.utils.PathingControlManager#executeProcesses()}.
     *
     * <p>Tracks health and transitions into / out of flee mode. Returns {@code true} while
     * fleeing so that this process participates in the active-process priority queue.
     */
    @Override
    public boolean isActive() {
        if (!Baritone.settings().fleeWhenAttacked.value) {
            // Feature disabled — reset any lingering state and stay inactive.
            reset();
            return false;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }

        float health = ctx.player().getHealth();

        if (prevHealth < 0) {
            // First tick after (re)enable — just record the baseline, don't trigger a flee.
            prevHealth = health;
            return false;
        }

        boolean tookDamage = (health < prevHealth);
        prevHealth = health;

        if (tookDamage) {
            if (!fleeing) {
                logDirect("CombatFlee: took damage, starting flee");
            }
            fleeing = true;
            stableHealthTicks = 0; // reset stability counter on any hit
        }

        return fleeing;
    }

    /**
     * Issues a {@link GoalRunAway} command toward safety on every tick while fleeing.
     *
     * <p>Also advances the stability counter: once health is stable and no threat is nearby
     * for {@link baritone.api.Settings#fleeStableHealthTicks} ticks, flee mode ends and
     * {@link PathingCommandType#DEFER} is returned so lower-priority processes resume.
     */
    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Optional<BlockPos> threatPos = nearestThreatPos();

        if (!threatPos.isPresent()) {
            // No threat visible — advance stability counter as if safe.
            stableHealthTicks++;
        } else {
            double fleeDistSq = (double) Baritone.settings().fleeDistance.value
                    * Baritone.settings().fleeDistance.value;
            Vec3 threatCentre = Vec3.atCenterOf(threatPos.get());
            boolean threatInRange = ctx.player().position().distanceToSqr(threatCentre) <= fleeDistSq;
            if (threatInRange) {
                stableHealthTicks = 0; // still in danger
            } else {
                stableHealthTicks++; // threat exists but is now far enough away
            }
        }

        // Check if we've been safe long enough to stop fleeing.
        if (stableHealthTicks >= Baritone.settings().fleeStableHealthTicks.value) {
            logDirect("CombatFlee: safe for " + stableHealthTicks + " ticks, resuming normal behaviour");
            fleeing = false;
            stableHealthTicks = 0;
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Still fleeing — issue a GoalRunAway toward the nearest threat (or a generic "run"
        // if somehow no threat is visible but we still consider ourselves unsafe).
        if (!threatPos.isPresent()) {
            // No threat in entity list (maybe it died) — just pause briefly.
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        int fleeDistSetting = Baritone.settings().fleeDistance.value;
        Integer maintainY = Baritone.settings().fleePreserveY.value
                ? ctx.playerFeet().getY()
                : null;
        GoalRunAway goal = new GoalRunAway(fleeDistSetting, maintainY, threatPos.get());
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    /** Resets all state when another process forcibly takes control. */
    @Override
    public void onLostControl() {
        reset();
    }

    @Override
    public double priority() {
        return PRIORITY;
    }

    @Override
    public String displayName0() {
        return "Combat Flee";
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link BlockPos} of the nearest currently-hostile mob to the player,
     * or {@link Optional#empty()} if no hostile mobs are detected.
     */
    private Optional<BlockPos> nearestThreatPos() {
        double[] minDistSq = {Double.MAX_VALUE};
        BlockPos[] nearest = {null};

        ctx.entitiesStream().forEach(entity -> {
            if (!MobDangerProfile.isCurrentlyHostile(entity, ctx)) {
                return;
            }
            double distSq = entity.distanceToSqr(ctx.player());
            if (distSq < minDistSq[0]) {
                minDistSq[0] = distSq;
                nearest[0] = entity.blockPosition();
            }
        });

        return Optional.ofNullable(nearest[0]);
    }

    /** Clears all internal state (called on disable or loss of control). */
    private void reset() {
        prevHealth = -1.0f;
        fleeing = false;
        stableHealthTicks = 0;
    }
}

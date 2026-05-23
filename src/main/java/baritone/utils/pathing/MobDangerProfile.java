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
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;

/**
 * Describes how strongly the pathfinder should avoid a particular entity.
 * <p>
 * The previous behavior applied a single flat radius/coefficient to every mob,
 * which simultaneously over-avoids harmless melee mobs and under-avoids lethal
 * ranged or explosive ones. This scales the user-configured base avoidance by
 * the threat a specific mob type actually poses: a creeper is dangerous from
 * its explosion radius, a skeleton from bow range, a warden from much further
 * still, while a plain zombie is only a threat in melee.
 *
 * @see Avoidance
 */
public final class MobDangerProfile {

    /**
     * The avoidance cost multiplier for any A* node that falls within {@link #radius} of this mob.
     * A value of {@code 1.0} means no penalty; higher values steer the path further away.
     * This is derived from the user's {@code mobAvoidanceCoefficient} setting scaled by the
     * per-entity-type danger multiplier.
     */
    public final double coefficient;

    /**
     * The sphere radius (in blocks) around the mob within which {@link #coefficient} is applied.
     * Derived from the user's {@code mobAvoidanceRadius} setting, optionally enlarged for
     * ranged or otherwise wide-threat mobs (e.g. Ghast, Warden).
     */
    public final int radius;

    private MobDangerProfile(double coefficient, int radius) {
        this.coefficient = coefficient;
        this.radius = radius;
    }

    /**
     * Returns {@code true} when {@code entity} is a hostile mob that is currently targeting or
     * capable of targeting the player — independent of any avoidance coefficient settings.
     *
     * <p>This is the raw "is this thing dangerous right now?" check used by features such as
     * {@link baritone.process.CombatFleeProcess} that need threat detection regardless of whether
     * the main avoidance system is configured.
     *
     * @param entity the entity to evaluate
     * @param ctx    the player context (used for light-level dependent checks)
     * @return {@code true} if the entity is currently a hostile threat to the player
     */
    public static boolean isCurrentlyHostile(Entity entity, IPlayerContext ctx) {
        if (!(entity instanceof Mob)) {
            return false;
        }
        if (entity instanceof Spider && entity.getLightLevelDependentMagicValue() >= 0.5) {
            return false;
        }
        if (entity instanceof ZombifiedPiglin && ((ZombifiedPiglin) entity).getLastHurtByMob() == null) {
            return false;
        }
        if (entity instanceof EnderMan && !((EnderMan) entity).isCreepy()) {
            return false;
        }
        // net.minecraft.world.entity.monster.Monster is the marker for most always-hostile mobs.
        // Spider/ZombifiedPiglin/EnderMan passed their conditional checks above.
        if (entity instanceof net.minecraft.world.entity.monster.Monster) {
            return true;
        }
        // A few hostile mobs extend FlyingMob rather than Monster; add them explicitly.
        // (Blaze extends Monster so it is already covered above.)
        EntityType<?> type = entity.getType();
        return type == EntityType.GHAST || type == EntityType.PHANTOM;
    }

    /**
     * Computes the avoidance profile for the given entity.
     *
     * @param entity the entity to evaluate
     * @param ctx    the player context (used for light-level dependent checks)
     * @return the profile describing how to avoid {@code entity}, or {@code null}
     * if the entity poses no threat and should not be avoided at all
     */
    public static MobDangerProfile of(Entity entity, IPlayerContext ctx) {
        if (!(entity instanceof Mob)) {
            return null;
        }
        // Conditionally-hostile mobs: skip while they are still neutral.
        if (entity instanceof Spider && entity.getLightLevelDependentMagicValue() >= 0.5) {
            return null; // spiders don't attack in sufficient light at *the spider's* position
        }
        if (entity instanceof ZombifiedPiglin && ((ZombifiedPiglin) entity).getLastHurtByMob() == null) {
            return null; // piglins are neutral until provoked
        }
        if (entity instanceof EnderMan && !((EnderMan) entity).isCreepy()) {
            return null; // endermen are passive until stared at
        }

        final double baseCoeff = Baritone.settings().mobAvoidanceCoefficient.value;
        final int baseRadius = Baritone.settings().mobAvoidanceRadius.value;
        if (baseCoeff <= 1.0D) {
            // A coefficient at or below 1.0 means avoidance is disabled or the user
            // is intentionally seeking mobs out.  Returning null means no Avoidance
            // sphere is added at all, which is the correct neutral / seek-mob behavior.
            // Returning a non-null profile with coefficient < 1.0 would produce
            // attraction spheres that steer the pathfinder *toward* mobs.
            return null;
        }
        final double extra = baseCoeff - 1.0D; // avoidance configured above the neutral 1.0

        final EntityType<?> type = entity.getType();
        final double coeffMult;
        final double radiusMult;
        final int minRadius;
        if (type == EntityType.WARDEN) {
            coeffMult = 3.0D;
            radiusMult = 2.0D;
            minRadius = 16; // devastating melee plus a ranged sonic boom
        } else if (type == EntityType.CREEPER) {
            coeffMult = 2.0D;
            radiusMult = 1.0D;
            minRadius = 7; // suicidal area-of-effect explosion
        } else if (type == EntityType.GHAST
                || type == EntityType.BLAZE
                || type == EntityType.SKELETON
                || type == EntityType.STRAY
                || type == EntityType.WITCH) {
            coeffMult = 1.4D;
            radiusMult = 1.6D;
            minRadius = 0; // ranged attackers threaten from a distance
        } else if (type == EntityType.WITHER_SKELETON) {
            coeffMult = 1.6D;
            radiusMult = 1.0D;
            minRadius = 0; // melee, but inflicts the wither effect
        } else if (type == EntityType.PHANTOM) {
            coeffMult = 1.2D;
            radiusMult = 1.2D;
            minRadius = 0; // dives in from above
        } else {
            coeffMult = 1.0D;
            radiusMult = 1.0D;
            minRadius = 0; // default melee mob (zombie, etc.)
        }

        final double coefficient = 1.0D + extra * coeffMult;
        final int radius = Math.max(minRadius, (int) Math.round(baseRadius * radiusMult));
        return new MobDangerProfile(coefficient, radius);
    }
}

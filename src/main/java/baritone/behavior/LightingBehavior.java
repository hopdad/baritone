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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Automatically places torches when the player's block-light drops to or below the configured
 * threshold, preventing hostile mob spawns around the player's feet while pathing.
 *
 * <h3>Behavior</h3>
 * <p>On every {@link #CHECK_INTERVAL_TICKS}th tick (roughly once per second) while the player
 * is on the ground and not busy, this behavior:
 * <ol>
 *   <li>Measures the block-light at {@link baritone.api.utils.IPlayerContext#playerFeet()}</li>
 *   <li>If the light is at or below {@link baritone.api.Settings#torchPlacementLightThreshold},
 *       searches the inventory for a {@link Items#TORCH} or {@link Items#SOUL_TORCH}</li>
 *   <li>If one is found, selects it in the hotbar and places it on the block immediately below
 *       the player (the floor), restoring the previous hotbar selection afterward</li>
 * </ol>
 *
 * <p>Placement is a direct {@code processRightClickBlock} call (no camera-rotation required).
 * If the floor block is not solid or the player's hands are busy, the attempt is silently skipped
 * and retried next interval.
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Enable via {@link baritone.api.Settings#autoPlaceTorches}</li>
 *   <li>Configure threshold via {@link baritone.api.Settings#torchPlacementLightThreshold}
 *       (default 1)</li>
 * </ul>
 *
 * @see baritone.api.Settings#autoPlaceTorches
 * @see baritone.api.Settings#torchPlacementLightThreshold
 */
public final class LightingBehavior extends Behavior {

    /** How many ticks between light-level checks. 20 = approximately once per second. */
    private static final int CHECK_INTERVAL_TICKS = 20;

    private int ticksSinceLastCheck = 0;

    public LightingBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        if (!Baritone.settings().autoPlaceTorches.value) {
            ticksSinceLastCheck = 0;
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }

        ticksSinceLastCheck++;
        if (ticksSinceLastCheck < CHECK_INTERVAL_TICKS) {
            return;
        }
        ticksSinceLastCheck = 0;

        // Only place when standing on something — avoids mid-air placements.
        if (!ctx.player().onGround()) {
            return;
        }
        // Don't interfere when the player's hands are already busy (mining, attacking, etc.).
        if (ctx.player().isHandsBusy()) {
            return;
        }

        // Measure block-light at the player's feet position.
        BlockPos feetPos = ctx.playerFeet();
        int blockLight = ctx.world().getBrightness(LightLayer.BLOCK, feetPos);
        if (blockLight > Baritone.settings().torchPlacementLightThreshold.value) {
            return; // Already bright enough.
        }

        // The floor block must be solid to accept a torch placement on its top face.
        BlockPos floorPos = feetPos.below();
        BlockState floorState = ctx.world().getBlockState(floorPos);
        if (!floorState.isSolid()) {
            return;
        }

        // Remember the current hotbar selection so we can restore it after placing.
        int prevSlot = ctx.player().getInventory().getSelectedSlot();

        // Select a torch from inventory (hotbar first, then main inventory if allowInventory).
        boolean hasTorch = baritone.getInventoryBehavior().throwaway(
                true,
                stack -> stack.getItem() == Items.TORCH || stack.getItem() == Items.SOUL_TORCH
        );
        if (!hasTorch) {
            return; // No torch available.
        }

        // Place the torch on the top face of the floor block.
        // The hit-vector is at the centre of the floor block's top face (= player's feet level).
        Vec3 hitVec = Vec3.atBottomCenterOf(feetPos); // centre of the top face of floorPos
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, floorPos, false);

        ctx.playerController().processRightClickBlock(
                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hitResult);

        // Restore the previously selected hotbar slot.
        ctx.player().getInventory().setSelectedSlot(prevSlot);
    }
}

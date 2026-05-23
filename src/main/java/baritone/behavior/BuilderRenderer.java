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
import baritone.api.event.events.RenderEvent;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.awt.*;
import java.util.Collections;
import java.util.Optional;

/**
 * Renders a Litematica-style "build ghost" overlay for the active {@link BuilderProcess} schematic.
 *
 * <h3>Overlay layers</h3>
 * <ul>
 *   <li><b>Ghost (to place)</b> — wireframe in {@link baritone.api.Settings#colorSchematicGhostBlocksToPlace}
 *       at positions where the schematic expects a block but the world currently has air.</li>
 *   <li><b>To break</b> — wireframe in {@link baritone.api.Settings#colorSchematicGhostBlocksToBreak}
 *       at positions where the schematic expects air but the world has a solid block.</li>
 *   <li><b>Wrong block</b> — wireframe in {@link baritone.api.Settings#colorSchematicGhostBlocksWrong}
 *       at positions where both the current and desired states are non-air but differ.</li>
 * </ul>
 *
 * <h3>Bounding box</h3>
 * When {@link baritone.api.Settings#renderSchematicBoundingBox} is enabled, a full-schematic
 * wireframe box is drawn in {@link baritone.api.Settings#colorSchematicBoundingBox}.
 *
 * <h3>Performance</h3>
 * The overlay is clipped to a radius of {@link baritone.api.Settings#renderSchematicOverlayRadius}
 * blocks around the player and hard-capped at
 * {@link baritone.api.Settings#renderSchematicOverlayMaxBlocks} rendered boxes per frame.
 * This keeps the render cost proportional to what the player can actually see.
 *
 * <h3>Prerequisites</h3>
 * Enable via {@link baritone.api.Settings#renderSchematic} (default: {@code false}).
 * A schematic must be loaded into {@link BuilderProcess} (active or paused) for anything to appear.
 */
public final class BuilderRenderer extends Behavior {

    public BuilderRenderer(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }

        IBuilderProcess builder = baritone.getBuilderProcess();
        ISchematic schematic = builder.getActiveSchematic();
        Vec3i origin = builder.getActiveSchematicOrigin();
        if (schematic == null || origin == null) {
            return; // no schematic loaded
        }

        PoseStack stack = event.getModelViewStack();

        // Bounding box and ghost overlay — gated by renderSchematic toggle
        if (Baritone.settings().renderSchematic.value) {
            if (Baritone.settings().renderSchematicBoundingBox.value) {
                drawBoundingBox(stack, schematic, origin);
            }
            drawBlockOverlay(stack, schematic, origin);
        }

        // Layer plane — separate toggle so it can be on even without the full ghost
        if (Baritone.settings().renderSchematicLayer.value) {
            drawLayerPlane(stack, schematic, origin, builder);
        }
    }

    // -------------------------------------------------------------------------
    // Layer plane
    // -------------------------------------------------------------------------

    /**
     * When {@link baritone.api.Settings#buildInLayers} is active, draws two horizontal wireframe
     * rectangles that bracket the current working layer, giving a clear visual indicator of which
     * Y-slice the bot is building.
     *
     * <p>The planes span the full X-Z footprint of the schematic at the bottom and top of the
     * active layer.  Layer-order (bottom-to-top vs top-to-bottom) is accounted for so the
     * highlighted slab always matches where {@link baritone.process.BuilderProcess} is working.</p>
     */
    private void drawLayerPlane(PoseStack stack, ISchematic schematic, Vec3i origin, IBuilderProcess builder) {
        Optional<Integer> minLayerOpt = builder.getMinLayer();
        Optional<Integer> maxLayerOpt = builder.getMaxLayer();
        if (!minLayerOpt.isPresent() || !maxLayerOpt.isPresent()) {
            return; // buildInLayers not active
        }

        int layerIndex = minLayerOpt.get();   // 0-based current layer index
        int stopHeight = maxLayerOpt.get();   // total build height in blocks
        int layerH     = Baritone.settings().layerHeight.value;
        boolean topToBottom = Baritone.settings().layerOrder.value;

        // Compute world-Y range of the current slab
        int slabMinY, slabMaxY;
        if (topToBottom) {
            // Counting down: layer 0 = topmost slab
            slabMaxY = origin.getY() + stopHeight - layerIndex * layerH;
            slabMinY = slabMaxY - layerH;
        } else {
            // Counting up: layer 0 = bottom slab
            slabMinY = origin.getY() + layerIndex * layerH;
            slabMaxY = slabMinY + layerH;
        }

        // Clamp to schematic Y range
        slabMinY = Math.max(slabMinY, origin.getY());
        slabMaxY = Math.min(slabMaxY, origin.getY() + schematic.heightY());

        double x0 = origin.getX();
        double x1 = origin.getX() + schematic.widthX();
        double z0 = origin.getZ();
        double z1 = origin.getZ() + schematic.lengthZ();

        Color color = Baritone.settings().colorSchematicLayerPlane.value;
        float lw = Baritone.settings().goalRenderLineWidthPixels.value;

        // Draw bottom plane of slab
        BufferBuilder buf = IRenderer.startLines(color, 0.75f);
        emitHorizontalRect(buf, stack, x0, x1, z0, z1, slabMinY, lw);
        // Draw top plane of slab only if different from bottom
        if (slabMaxY != slabMinY) {
            emitHorizontalRect(buf, stack, x0, x1, z0, z1, slabMaxY, lw);
            // Connect corners with vertical edges
            double vpX = IRenderer.renderManager.renderPosX();
            double vpY = IRenderer.renderManager.renderPosY();
            double vpZ = IRenderer.renderManager.renderPosZ();
            IRenderer.emitLine(buf, stack, x0 - vpX, slabMinY - vpY, z0 - vpZ, x0 - vpX, slabMaxY - vpY, z0 - vpZ, lw);
            IRenderer.emitLine(buf, stack, x1 - vpX, slabMinY - vpY, z0 - vpZ, x1 - vpX, slabMaxY - vpY, z0 - vpZ, lw);
            IRenderer.emitLine(buf, stack, x1 - vpX, slabMinY - vpY, z1 - vpZ, x1 - vpX, slabMaxY - vpY, z1 - vpZ, lw);
            IRenderer.emitLine(buf, stack, x0 - vpX, slabMinY - vpY, z1 - vpZ, x0 - vpX, slabMaxY - vpY, z1 - vpZ, lw);
        }
        IRenderer.endLines(buf, Baritone.settings().renderSchematicIgnoreDepth.value);
    }

    /** Emits four lines forming a horizontal rectangle at the given Y world-coordinate. */
    private static void emitHorizontalRect(BufferBuilder buf, PoseStack stack,
                                            double x0, double x1, double z0, double z1,
                                            double worldY, float lineWidth) {
        double vpX = IRenderer.renderManager.renderPosX();
        double vpY = IRenderer.renderManager.renderPosY();
        double vpZ = IRenderer.renderManager.renderPosZ();
        double y = worldY - vpY;
        IRenderer.emitLine(buf, stack, x0 - vpX, y, z0 - vpZ, x1 - vpX, y, z0 - vpZ, lineWidth);
        IRenderer.emitLine(buf, stack, x1 - vpX, y, z0 - vpZ, x1 - vpX, y, z1 - vpZ, lineWidth);
        IRenderer.emitLine(buf, stack, x1 - vpX, y, z1 - vpZ, x0 - vpX, y, z1 - vpZ, lineWidth);
        IRenderer.emitLine(buf, stack, x0 - vpX, y, z1 - vpZ, x0 - vpX, y, z0 - vpZ, lineWidth);
    }

    // -------------------------------------------------------------------------
    // Bounding box
    // -------------------------------------------------------------------------

    private void drawBoundingBox(PoseStack stack, ISchematic schematic, Vec3i origin) {
        AABB box = new AABB(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                origin.getX() + schematic.widthX(),
                origin.getY() + schematic.heightY(),
                origin.getZ() + schematic.lengthZ()
        );
        Color color = Baritone.settings().colorSchematicBoundingBox.value;
        BufferBuilder buf = IRenderer.startLines(color, 0.8f);
        IRenderer.emitAABB(buf, stack, box, 0.0, Baritone.settings().goalRenderLineWidthPixels.value);
        IRenderer.endLines(buf, Baritone.settings().renderSchematicIgnoreDepth.value);
    }

    // -------------------------------------------------------------------------
    // Per-block diff overlay
    // -------------------------------------------------------------------------

    private void drawBlockOverlay(PoseStack stack, ISchematic schematic, Vec3i origin) {
        BlockPos playerPos = ctx.playerFeet();
        int radius = Baritone.settings().renderSchematicOverlayRadius.value;
        int maxBlocks = Baritone.settings().renderSchematicOverlayMaxBlocks.value;

        // Intersect the player's render cube with the schematic's world-space AABB.
        int x0 = Math.max(origin.getX(),                          playerPos.getX() - radius);
        int y0 = Math.max(origin.getY(),                          playerPos.getY() - radius);
        int z0 = Math.max(origin.getZ(),                          playerPos.getZ() - radius);
        int x1 = Math.min(origin.getX() + schematic.widthX()  - 1, playerPos.getX() + radius);
        int y1 = Math.min(origin.getY() + schematic.heightY() - 1, playerPos.getY() + radius);
        int z1 = Math.min(origin.getZ() + schematic.lengthZ() - 1, playerPos.getZ() + radius);

        if (x0 > x1 || y0 > y1 || z0 > z1) {
            return; // player is outside the schematic's render range
        }

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        float lineWidth = Baritone.settings().pathRenderLineWidthPixels.value;
        boolean ignoreDepth = Baritone.settings().renderSchematicIgnoreDepth.value;

        // Open one BufferBuilder per category; we'll fill all three in a single pass.
        BufferBuilder toPlace = IRenderer.startLines(Baritone.settings().colorSchematicGhostBlocksToPlace.value, 0.55f);
        BufferBuilder toBreak = IRenderer.startLines(Baritone.settings().colorSchematicGhostBlocksToBreak.value, 0.55f);
        BufferBuilder toFix   = IRenderer.startLines(Baritone.settings().colorSchematicGhostBlocksWrong.value,   0.45f);

        int rendered = 0;
        outer:
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int sx = x - origin.getX();
                    int sy = y - origin.getY();
                    int sz = z - origin.getZ();

                    BlockState current = bsi.get0(x, y, z);

                    // Delegate the "is this position part of the build?" decision to the schematic
                    // (handles MaskSchematic, buildSkipBlocks, map-art modes, etc.).
                    if (!schematic.inSchematic(sx, sy, sz, current)) {
                        continue;
                    }

                    // Pass emptyList for approxPlaceable — the ghost renderer doesn't need
                    // accurate substitution results; it just needs to know what block type
                    // the schematic intends.  This avoids a race with the main-thread list.
                    BlockState desired = schematic.desiredState(sx, sy, sz, current, Collections.emptyList());

                    if (current.equals(desired)) {
                        continue; // already correct — nothing to highlight
                    }

                    boolean currentAir = current.getBlock() instanceof AirBlock;
                    boolean desiredAir = desired.getBlock() instanceof AirBlock;

                    if (currentAir && desiredAir) {
                        continue; // shouldn't normally happen, but be safe
                    }

                    AABB box = new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0);

                    if (desiredAir) {
                        // Schematic wants air here but the world has a block → needs breaking.
                        IRenderer.emitAABB(toBreak, stack, box, 0.002, lineWidth);
                    } else if (currentAir) {
                        // Schematic wants a block here but the world has air → ghost "place" overlay.
                        IRenderer.emitAABB(toPlace, stack, box, 0.002, lineWidth);
                    } else {
                        // Both are non-air but differ → wrong block type or state.
                        IRenderer.emitAABB(toFix, stack, box, 0.002, lineWidth);
                    }

                    if (++rendered >= maxBlocks) {
                        break outer;
                    }
                }
            }
        }

        // Flush all three buffers.
        IRenderer.endLines(toPlace, ignoreDepth);
        IRenderer.endLines(toBreak, ignoreDepth);
        IRenderer.endLines(toFix,   ignoreDepth);
    }
}

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

package baritone.api;

import baritone.api.behavior.IPathingBehavior;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IBuilderProcess;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * An immutable point-in-time snapshot of Baritone's state, suitable for display, logging,
 * or consumption by external mods/tools.
 *
 * <p>Obtain a fresh snapshot via {@link IBaritone#getStatus()}.  All fields are eagerly computed
 * at construction; the object is thread-safe once created.
 *
 * <h3>Typical uses</h3>
 * <ul>
 *   <li>The {@code #status} chat command uses {@link #toDisplayString()} for a human-readable
 *       multi-line summary.</li>
 *   <li>External mods that integrate with Baritone can call {@link #toJson()} and parse the
 *       result without depending on Baritone's internal classes.</li>
 * </ul>
 */
public final class BaritoneStatus {

    // -------------------------------------------------------------------------
    // Process / pathing
    // -------------------------------------------------------------------------

    /** Display name of the process currently controlling Baritone, or {@code "idle"}. */
    public final String activeProcess;

    /** Whether the active process is in a paused state. */
    public final boolean isPaused;

    /**
     * Current pathing state: {@code "pathing"} while executing a path, {@code "calculating"}
     * while the A* search is running in the background, or {@code "stopped"}.
     */
    public final String pathState;

    /** Human-readable description of the current goal, or {@code "none"}. */
    public final String goalDescription;

    // -------------------------------------------------------------------------
    // Builder-specific (null / -1 when builder is not active)
    // -------------------------------------------------------------------------

    /** Name of the currently loaded schematic file, or {@code null}. */
    public final String schematicName;

    /** Blocks still to be placed/broken, or {@code -1} if not yet scanned. */
    public final int blocksRemaining;

    /** Blocks confirmed correct so far, or {@code -1} if builder is not active. */
    public final int blocksPlaced;

    /** 1-based current layer index when {@code buildInLayers} is on, or {@code -1}. */
    public final int currentLayer;

    /** Total number of layers for the active schematic, or {@code -1}. */
    public final int totalLayers;

    // -------------------------------------------------------------------------
    // Player state
    // -------------------------------------------------------------------------

    /** Player health as a fraction of maximum ({@code 0.0} – {@code 1.0}). */
    public final float healthFraction;

    /** {@code true} while the Combat Flee process is in control. */
    public final boolean isFleeing;

    /** Player's current X coordinate (block-centre). */
    public final double playerX;

    /** Player's current Y coordinate (feet). */
    public final double playerY;

    /** Player's current Z coordinate (block-centre). */
    public final double playerZ;

    // -------------------------------------------------------------------------
    // Timing
    // -------------------------------------------------------------------------

    /** {@code world.getGameTime()} at the moment this snapshot was taken. */
    public final long worldTick;

    // -------------------------------------------------------------------------
    // Construction (private — use IBaritone.getStatus())
    // -------------------------------------------------------------------------

    private BaritoneStatus(
            String activeProcess, boolean isPaused, String pathState, String goalDescription,
            String schematicName, int blocksRemaining, int blocksPlaced,
            int currentLayer, int totalLayers,
            float healthFraction, boolean isFleeing,
            double playerX, double playerY, double playerZ,
            long worldTick) {
        this.activeProcess    = activeProcess;
        this.isPaused         = isPaused;
        this.pathState        = pathState;
        this.goalDescription  = goalDescription;
        this.schematicName    = schematicName;
        this.blocksRemaining  = blocksRemaining;
        this.blocksPlaced     = blocksPlaced;
        this.currentLayer     = currentLayer;
        this.totalLayers      = totalLayers;
        this.healthFraction   = healthFraction;
        this.isFleeing        = isFleeing;
        this.playerX          = playerX;
        this.playerY          = playerY;
        this.playerZ          = playerZ;
        this.worldTick        = worldTick;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Builds a fresh {@link BaritoneStatus} from the current state of {@code baritone}.
     * Must be called on the game (client) thread.
     */
    public static BaritoneStatus snapshot(IBaritone baritone) {
        IPlayerContext ctx = baritone.getPlayerContext();
        IPathingBehavior pathing = baritone.getPathingBehavior();

        // --- Process ---
        Optional<IBaritoneProcess> proc = baritone.getPathingControlManager().mostRecentInControl();
        String activeProcess = proc.map(IBaritoneProcess::displayName).orElse("idle");
        boolean isPaused = proc.map(p -> {
            // Any process that supports isPaused() will expose it — check the builder specifically
            if (p instanceof baritone.api.process.IBuilderProcess) {
                return ((IBuilderProcess) p).isPaused();
            }
            return false;
        }).orElse(false);
        boolean isFleeing = "Combat Flee".equals(activeProcess);

        // --- Path state ---
        String pathState;
        if (pathing.isPathing()) {
            pathState = "pathing";
        } else if (pathing.getInProgress().isPresent()) {
            pathState = "calculating";
        } else {
            pathState = "stopped";
        }

        // --- Goal ---
        String goalDescription = "none";
        if (pathing.getGoal() != null) {
            String g = pathing.getGoal().toString();
            goalDescription = g.length() > 60 ? g.substring(0, 58) + "…" : g;
        }

        // --- Builder ---
        IBuilderProcess builder = baritone.getBuilderProcess();
        String schematicName   = builder.getActiveSchematicName();
        int blocksRemaining    = builder.getBlocksRemaining();
        int blocksPlaced       = builder.getBlocksPlaced();
        int currentLayer = -1;
        int totalLayers  = -1;
        if (builder.getMinLayer().isPresent() && builder.getMaxLayer().isPresent()) {
            // layerHeight setting lives in the Baritone settings; access via BaritoneAPI
            int layerH = BaritoneAPI.getSettings().layerHeight.value;
            currentLayer = builder.getMinLayer().get() + 1; // 1-based
            totalLayers  = Math.max(1, builder.getMaxLayer().get() / Math.max(1, layerH));
        }

        // --- Player ---
        Player player = ctx.player();
        float healthFraction = 0f;
        double px = 0, py = 0, pz = 0;
        if (player != null) {
            float maxHp = player.getMaxHealth();
            healthFraction = maxHp > 0 ? player.getHealth() / maxHp : 0f;
            px = player.getX();
            py = player.getY();
            pz = player.getZ();
        }

        long tick = (ctx.world() != null) ? ctx.world().getGameTime() : 0L;

        return new BaritoneStatus(
                activeProcess, isPaused, pathState, goalDescription,
                schematicName, blocksRemaining, blocksPlaced, currentLayer, totalLayers,
                healthFraction, isFleeing, px, py, pz, tick);
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    /**
     * Returns a human-readable multi-line string suitable for printing to the Baritone chat
     * channel (using Minecraft §-code colours for structure).
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        sb.append("§b--- Baritone Status ---\n");

        // Process / path state
        String procLabel = isPaused ? "§e" + activeProcess + " (paused)§r"
                : isFleeing         ? "§c" + activeProcess + "§r"
                :                     "§a" + activeProcess + "§r";
        sb.append("Process: ").append(procLabel).append(" | ").append(pathState).append('\n');
        sb.append("Goal: §7").append(goalDescription).append("§r\n");

        // Builder
        if (schematicName != null) {
            sb.append("Build: §f").append(schematicName).append("§r");
            if (blocksRemaining >= 0 && blocksPlaced >= 0) {
                int total = blocksRemaining + blocksPlaced;
                int pct   = total > 0 ? (int) (100L * blocksPlaced / total) : 0;
                sb.append(" | ").append(blocksPlaced).append('/').append(total)
                  .append(" (").append(pct).append("%)");
            }
            if (currentLayer > 0 && totalLayers > 0) {
                sb.append(" | Layer ").append(currentLayer).append('/').append(totalLayers);
            }
            sb.append('\n');
        }

        // Player
        int hpPct = (int) (healthFraction * 100f);
        sb.append(String.format("Player: §fx=%.1f y=%.1f z=%.1f§r | HP: §f%d%%§r\n",
                playerX, playerY, playerZ, hpPct));
        sb.append("Tick: §7").append(worldTick).append("§r");
        return sb.toString();
    }

    /**
     * Serialises this snapshot as a minimal JSON object with no external dependencies.
     * Keys use camelCase and match the field names in this class.
     */
    public String toJson() {
        return "{"
                + "\"activeProcess\":" + jsonStr(activeProcess)
                + ",\"isPaused\":"     + isPaused
                + ",\"pathState\":"    + jsonStr(pathState)
                + ",\"goalDescription\":" + jsonStr(goalDescription)
                + ",\"schematicName\":"   + jsonStr(schematicName)
                + ",\"blocksRemaining\":" + blocksRemaining
                + ",\"blocksPlaced\":"    + blocksPlaced
                + ",\"currentLayer\":"    + currentLayer
                + ",\"totalLayers\":"     + totalLayers
                + ",\"healthFraction\":"  + String.format("%.4f", healthFraction)
                + ",\"isFleeing\":"       + isFleeing
                + ",\"playerX\":"         + String.format("%.2f", playerX)
                + ",\"playerY\":"         + String.format("%.2f", playerY)
                + ",\"playerZ\":"         + String.format("%.2f", playerZ)
                + ",\"worldTick\":"       + worldTick
                + "}";
    }

    @Override
    public String toString() {
        return toJson();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String jsonStr(String s) {
        if (s == null) return "null";
        // Minimal escaping sufficient for Baritone's own strings (no control chars expected).
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

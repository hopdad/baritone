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

package baritone.ui;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Groups Baritone settings into user-facing tabs for {@link BaritoneSettingsScreen}.
 *
 * <p>Settings are assigned explicitly by field name (resolved via {@link Settings#byLowerName})
 * rather than by reflection scan, so the grouping survives obfuscation and any name-lookup
 * failure simply skips that setting without crashing. The {@link #ALL} category returns every
 * non-Java-only setting.
 */
public enum SettingCategory {

    PATHING("Pathing",
            "allowSprint", "allowBreak", "allowPlace",
            "allowParkour", "allowParkourPlace", "allowParkourAscend",
            "allowJumpAt256", "allowJumpAtBuildLimit",
            "allowWaterBucketFall", "allowDiagonalDescend",
            "allowDiagonalAscend", "allowDownward",
            "costHeuristic", "primaryTimeoutMS", "failureTimeoutMS",
            "planAheadPrimaryTimeoutMS", "planAheadFailureTimeoutMS",
            "backtrackCostFavoringCoefficient", "assumeStep"
    ),

    MOVEMENT("Movement",
            "walkWhileBreaking", "rightClickSpeed",
            "blockBreakAdditionalPenalty",
            "maxFallHeightNoWater", "maxFallHeightBucket",
            "sprintInWater", "jumpPenalty", "movementTimeoutTicks"
    ),

    MOB_AVOIDANCE("Mob Avoidance",
            "avoidance",
            "mobAvoidanceCoefficient", "mobAvoidanceRadius",
            "avoidanceReroute", "avoidanceRerouteDistance", "avoidanceRerouteCooldownTicks",
            "mobSpawnerAvoidanceCoefficient", "mobSpawnerAvoidanceRadius"
    ),

    MINING("Mining",
            "mineGoalUpdateInterval",
            "legitMine", "legitMineYLevel", "legitMineIncludeDiagonals",
            "worldExploringChunkOffset",
            "randomLooking", "randomLooking113"
    ),

    RENDERING("Rendering",
            "renderPath", "renderPathAsLine", "renderPathIgnoreDepth",
            "renderGoal", "renderGoalAnimated", "renderGoalIgnoreDepth", "renderGoalXZBeacon",
            "pathRenderLineWidthPixels", "goalRenderLineWidthPixels"
    ),

    ALL("All" /* no names list — returns every non-Java-only setting */);

    /** Human-readable label shown on the tab button. */
    public final String label;

    /** Field names (case-insensitive) of the settings belonging to this category. */
    private final String[] settingNames;

    SettingCategory(String label, String... settingNames) {
        this.label        = label;
        this.settingNames = settingNames;
    }

    /**
     * Returns the settings that belong to this category, in declaration order.
     *
     * <p>For {@link #ALL}, returns every non-Java-only setting. For other categories,
     * unknown names are silently skipped (future-proof against setting renames).
     */
    public List<Settings.Setting<?>> getSettings() {
        if (this == ALL) {
            return BaritoneAPI.getSettings().allSettings.stream()
                    .filter(s -> !s.isJavaOnly())
                    .collect(Collectors.toList());
        }
        Settings s = BaritoneAPI.getSettings();
        return Arrays.stream(settingNames)
                .map(name -> s.byLowerName.get(name.toLowerCase()))
                .filter(setting -> setting != null && !setting.isJavaOnly())
                .collect(Collectors.toList());
    }
}

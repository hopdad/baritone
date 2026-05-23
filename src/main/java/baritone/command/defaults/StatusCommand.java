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

package baritone.command.defaults;

import baritone.api.BaritoneStatus;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Prints a compact, human-readable summary of Baritone's current state to the chat channel.
 *
 * <p>The output includes:
 * <ul>
 *   <li>Active process name, paused state, and pathing state</li>
 *   <li>Current goal (truncated if very long)</li>
 *   <li>Builder schematic progress and current layer (when building)</li>
 *   <li>Player position and health</li>
 *   <li>Current world tick</li>
 * </ul>
 *
 * <p>Pass {@code json} as the first argument to print the same information as a minimal JSON
 * object — useful for testing external integrations or piping the output to scripts.
 *
 * <pre>
 * Usage:
 *   #status        — coloured multi-line summary
 *   #status json   — single-line JSON object
 * </pre>
 */
public class StatusCommand extends Command {

    public StatusCommand(IBaritone baritone) {
        super(baritone, "status");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);

        BaritoneStatus status = baritone.getStatus();

        if (args.hasAny() && "json".equalsIgnoreCase(args.getString())) {
            logDirect(status.toJson());
        } else {
            logDirect(status.toDisplayString());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("json").filter(s -> s.startsWith(args.peekString().toLowerCase()));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Show a snapshot of Baritone's current state";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Prints a compact summary of Baritone's state to the chat channel.",
                "",
                "Includes: active process, pathing state, goal, builder progress",
                "(when building), player position and health, and the current world tick.",
                "",
                "External mods can call IBaritone.getStatus().toJson() programmatically",
                "without depending on any internal Baritone classes.",
                "",
                "Usage:",
                "> status          — coloured multi-line human-readable summary",
                "> status json     — single-line JSON object"
        );
    }
}

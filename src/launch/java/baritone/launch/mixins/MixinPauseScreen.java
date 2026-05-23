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

package baritone.launch.mixins;

import baritone.ui.BaritoneSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "Baritone…" button into the vanilla pause menu.
 *
 * <p>The button is appended after the standard buttons are initialised (at {@code RETURN}
 * from {@code PauseScreen.init}). It is placed at the same horizontal centre and width
 * as the "Options…" button — 200 px wide, centred — and positioned just below the
 * standard button column using {@code height/4 + 168}.
 *
 * <p>If that Y-coordinate overlaps other buttons in a future MC version, shift it
 * by adjusting the constant below.
 */
@Mixin(PauseScreen.class)
public abstract class MixinPauseScreen extends Screen {

    private MixinPauseScreen() {
        // Required by Mixin: the mixin class inherits Screen so we have access
        // to Screen.width, Screen.height, and Screen.addRenderableWidget without @Shadow.
        super(Component.empty());
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void addBaritoneButton(CallbackInfo ci) {
        final int btnW = 200;
        final int btnX = this.width / 2 - btnW / 2;
        // Standard MC pause menu places buttons starting around height/4 + 48.
        // Each row is 24px tall. After ~5 rows the column ends near height/4 + 144.
        // We add our button one row below that.
        final int btnY = this.height / 4 + 168;

        addRenderableWidget(Button.builder(
                        Component.literal("Baritone…"), // "Baritone…" (U+2026 ellipsis)
                        btn -> Minecraft.getInstance().setScreen(new BaritoneSettingsScreen()))
                .pos(btnX, btnY)
                .size(btnW, 20)
                .build());
    }
}

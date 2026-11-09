package net.muxigame.console.client.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Shared constructor; appearance, focus and disabled states use Minecraft's button renderer. */
final class ConsoleButton extends Button {
    ConsoleButton(int x, int y, int width, int height, Component label, OnPress action) {
        super(x, y, width, height, label, action, DEFAULT_NARRATION);
    }
}

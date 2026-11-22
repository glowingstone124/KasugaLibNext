package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class NebulaScreen extends Screen {
    NebulaScreen() { super(Component.empty()); }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { NebulaDemo.stop(); }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() != InputConstants.KEY_SPACE) return super.keyPressed(event);
        NebulaDemo.warp();
        return true;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return event.button() == InputConstants.MOUSE_BUTTON_RIGHT || super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() != InputConstants.MOUSE_BUTTON_RIGHT) return super.mouseDragged(event, dx, dy);
        NebulaDemo.orbit(dx, dy);
        return true;
    }
}

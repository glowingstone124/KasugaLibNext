package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.joml.Vector2f;

final class WorldUiInputEvents {
    static final int PRESS = InputConstants.PRESS;
    static final int RELEASE = InputConstants.RELEASE;
    static final int REPEAT = InputConstants.REPEAT;

    private WorldUiInputEvents() {}

    static boolean isEscape(int key, int keyCode, int modifiers) { return new KeyEvent(key, keyCode, modifiers).isEscape(); }

    static boolean press(GuiEventListener target, Vector2f position, int button, int modifiers, boolean doubleClick) {
        return target.mouseClicked(mouse(position, button, modifiers), doubleClick);
    }

    static void release(GuiEventListener target, Vector2f position, int button, int modifiers) {
        target.mouseReleased(mouse(position, button, modifiers));
    }

    static void drag(GuiEventListener target, Vector2f position, int button, int modifiers, float dx, float dy) {
        target.mouseDragged(mouse(position, button, modifiers), dx, dy);
    }

    static boolean keyPressed(GuiEventListener target, int key, int keyCode, int modifiers) {
        return target.keyPressed(new KeyEvent(key, keyCode, modifiers));
    }

    static boolean keyReleased(GuiEventListener target, int key, int keyCode, int modifiers) {
        return target.keyReleased(new KeyEvent(key, keyCode, modifiers));
    }

    static boolean character(GuiEventListener target, int codePoint, int modifiers) {
        return target.charTyped(new CharacterEvent(codePoint));
    }

    private static MouseButtonEvent mouse(Vector2f position, int button, int modifiers) {
        return new MouseButtonEvent(position.x, position.y, new MouseButtonInfo(button, modifiers));
    }
}

package lib.kasuga.rendering.models.mc.backend.ui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

final class WorldUiInputEvents {
    static final int PRESS = GLFW.GLFW_PRESS;
    static final int RELEASE = GLFW.GLFW_RELEASE;
    static final int REPEAT = GLFW.GLFW_REPEAT;

    private WorldUiInputEvents() {}

    static boolean isEscape(int key, int scanCode, int modifiers) { return key == GLFW.GLFW_KEY_ESCAPE; }

    static boolean press(GuiEventListener target, Vector2f position, int button, int modifiers, boolean doubleClick) {
        return target.mouseClicked(position.x, position.y, button);
    }

    static void release(GuiEventListener target, Vector2f position, int button, int modifiers) {
        target.mouseReleased(position.x, position.y, button);
    }

    static void drag(GuiEventListener target, Vector2f position, int button, int modifiers, float dx, float dy) {
        target.mouseDragged(position.x, position.y, button, dx, dy);
    }

    static boolean keyPressed(GuiEventListener target, int key, int scanCode, int modifiers) {
        return target.keyPressed(key, scanCode, modifiers);
    }

    static boolean keyReleased(GuiEventListener target, int key, int scanCode, int modifiers) {
        return target.keyReleased(key, scanCode, modifiers);
    }

    static boolean character(GuiEventListener target, int codePoint, int modifiers) {
        boolean handled = false;
        for (char value : Character.toChars(codePoint)) handled |= target.charTyped(value, modifiers);
        return handled;
    }
}

package lib.kasuga.rendering.models.mc.dynamic.physics;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.sdl.SDLMouse;

final class RagdollInput {
    private RagdollInput() {}

    static boolean screenOpen(Minecraft minecraft) { return minecraft.gui.screen() != null; }
    static boolean matches(int configured, int button) { return sdlButton(configured) == button; }

    static boolean pressed(Minecraft minecraft, int button) {
        int mask = 1 << (sdlButton(button) - 1);
        return (SDLMouse.SDL_GetMouseState(null, null) & mask) != 0;
    }

    static Vec3 origin(Minecraft minecraft) { return minecraft.gameRenderer.mainCamera().position(); }
    static Vector3f direction(Minecraft minecraft) { return new Vector3f(minecraft.gameRenderer.mainCamera().forwardVector()); }

    /** Preserve the existing config's GLFW button numbering when reading SDL input. */
    private static int sdlButton(int configured) {
        return switch (configured) {
            case 0 -> SDLMouse.SDL_BUTTON_LEFT;
            case 1 -> SDLMouse.SDL_BUTTON_RIGHT;
            case 2 -> SDLMouse.SDL_BUTTON_MIDDLE;
            default -> configured + 1;
        };
    }
}

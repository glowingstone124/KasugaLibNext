package lib.kasuga.rendering.models.mc.dynamic.physics;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

final class RagdollInput {
    private RagdollInput() {}

    static boolean screenOpen(Minecraft minecraft) { return minecraft.screen != null; }
    static boolean matches(int configured, int button) { return configured == button; }

    static boolean pressed(Minecraft minecraft, int button) {
        return GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
    }

    static Vec3 origin(Minecraft minecraft) { return minecraft.gameRenderer.getMainCamera().getPosition(); }
    static Vector3f direction(Minecraft minecraft) { return new Vector3f(minecraft.gameRenderer.getMainCamera().getLookVector()); }
}

package lib.kasuga.scene;

import lib.kasuga.rendering.models.mc.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

final class SceneClientPlatform {
    private SceneClientPlatform() {}

    static boolean hasScreen(Minecraft client) { return client.screen != null; }
    static long window(Minecraft client) { return client.getWindow().getWindow(); }
    static boolean hudHidden(Minecraft client) { return client.options.hideGui; }
    static void setHudHidden(Minecraft client, boolean hidden) { client.options.hideGui = hidden; }

    static void rebuildTerrain(Minecraft client) { client.levelRenderer.allChanged(); }
    static @Nullable Frustum frustum(Minecraft client) { return client.levelRenderer.getFrustum(); }
    static boolean sectionCompiled(Minecraft client, BlockPos origin) {
        return client.levelRenderer.isSectionCompiled(origin);
    }

    static void placePrompt(Screen prompt, Vec3 position, Matrix4f local) {
        if (Constants.UI_BACKEND == null) return;
        Constants.UI_BACKEND.addRenderable(prompt, new ScenePose(position, local));
    }

    static void removePrompt(Screen prompt) {
        if (Constants.UI_BACKEND != null) Constants.UI_BACKEND.removeRenderable(prompt);
    }

    static void beginGuiLayer(GuiGraphics graphics) {}
}

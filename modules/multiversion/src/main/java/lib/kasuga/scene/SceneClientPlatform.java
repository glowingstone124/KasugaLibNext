package lib.kasuga.scene;

import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

final class SceneClientPlatform {
    private SceneClientPlatform() {}

    static boolean hasScreen(Minecraft client) { return client.gui.screen() != null; }
    static long window(Minecraft client) { return client.getWindow().handle(); }
    static boolean hudHidden(Minecraft client) { return client.gui.hud.isHidden(); }

    static void setHudHidden(Minecraft client, boolean hidden) {
        if (hudHidden(client) != hidden) client.gui.hud.toggle();
    }

    static void rebuildTerrain(Minecraft client) { client.levelExtractor.allChanged(); }

    static @Nullable Frustum frustum(Minecraft client) {
        Camera camera = client.gameRenderer.mainCamera();
        return camera.isInitialized() && client.levelRenderer.sectionRenderDispatcher() != null
                ? camera.getCullFrustum() : null;
    }

    static boolean sectionCompiled(Minecraft client, BlockPos origin) {
        return client.levelRenderer.isSectionCompiledAndVisible(origin, 0);
    }

    static void placePrompt(Screen prompt, Vec3 position, Matrix4f local) {
        UIBackend.get().setPlacement(prompt, new ScenePose(position, local));
    }

    static void removePrompt(Screen prompt) { UIBackend.get().removeRenderable(prompt); }

    static void beginGuiLayer(GuiGraphicsExtractor graphics) { graphics.nextStratum(); }
}

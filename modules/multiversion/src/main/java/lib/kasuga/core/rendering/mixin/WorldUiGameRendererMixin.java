package lib.kasuga.core.rendering.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import lib.kasuga.rendering.models.mc.backend.ui.GuiCanvasAccess;
import lib.kasuga.rendering.effect.pipeline.WorldProjection;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(GameRenderer.class)
abstract class WorldUiGameRendererMixin implements GuiCanvasAccess {
    @Unique private List<PictureInPictureRendererRegistration<?>> kasuga$factories;
    @Unique private boolean kasuga$drawingCanvas;
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow private boolean useUiLightmap;
    @Shadow @Final private FogRenderer fogRenderer;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;<init>(Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;Ljava/util/List;)V"),
            index = 2)
    private List<PictureInPictureRendererRegistration<?>> kasuga$rememberFactories(List<PictureInPictureRendererRegistration<?>> factories) {
        kasuga$factories = List.copyOf(factories);
        return factories;
    }

    @Override public List<PictureInPictureRendererRegistration<?>> kasuga$pictureInPictureFactories() {
        return kasuga$factories;
    }

    @Override public void kasuga$renderCanvas(GuiRenderer renderer, RenderTarget target, WindowRenderState viewport) {
        RenderSystem.assertOnRenderThread();
        if (kasuga$drawingCanvas) throw new IllegalStateException("Recursive world UI canvas draw");
        RenderTarget previousTarget = mainRenderTarget;
        WindowRenderState previousWindow = gameRenderState.windowRenderState;
        GuiWindowStateAccess windowAccess = (GuiWindowStateAccess) gameRenderState;
        boolean previousLightmap = useUiLightmap;
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        kasuga$drawingCanvas = true;
        try {
            // Scope the host's target and viewport so vanilla and other GUI renderers see the same canvas.
            mainRenderTarget = target;
            windowAccess.kasuga$setGuiWindowState(viewport);
            useUiLightmap = true;
            RenderSystem.setShaderFog(fogRenderer.getBuffer(FogRenderer.FogMode.NONE));
            renderer.render();
            renderer.endFrame();
        } finally {
            mainRenderTarget = previousTarget;
            windowAccess.kasuga$setGuiWindowState(previousWindow);
            useUiLightmap = previousLightmap;
            kasuga$drawingCanvas = false;
            RenderSystem.setShaderFog(fog);
        }
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private Matrix4f kasuga$worldUiProjection(Matrix4f projection) {
        WorldProjection.capture(projection);
        return projection;
    }
}

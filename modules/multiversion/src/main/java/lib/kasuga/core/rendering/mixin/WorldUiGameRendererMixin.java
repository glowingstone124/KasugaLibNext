package lib.kasuga.core.rendering.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import lib.kasuga.rendering.models.mc.backend.ui.GuiCanvasAccess;
import lib.kasuga.rendering.effect.pipeline.WorldProjection;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(GameRenderer.class)
abstract class WorldUiGameRendererMixin implements GuiCanvasAccess.Factory {
    @Unique private List<PictureInPictureRendererRegistration<?>> kasuga$factories;
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

    @Override public void kasuga$withGuiLighting(Runnable render) {
        boolean previous = useUiLightmap;
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        useUiLightmap = true;
        RenderSystem.setShaderFog(fogRenderer.getBuffer(FogRenderer.FogMode.NONE));
        try {
            render.run();
        } finally {
            useUiLightmap = previous;
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

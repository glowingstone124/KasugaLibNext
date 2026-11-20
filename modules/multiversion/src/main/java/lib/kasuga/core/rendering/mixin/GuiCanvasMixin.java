package lib.kasuga.core.rendering.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.rendering.models.mc.backend.ui.GuiCanvasAccess;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
abstract class GuiCanvasMixin implements GuiCanvasAccess {
    @Unique private RenderTarget kasuga$target;
    @Unique private WindowRenderState kasuga$viewport;

    @Shadow public abstract void render();
    @Shadow public abstract void endFrame();

    @Override public void kasuga$renderCanvas(RenderTarget target, WindowRenderState viewport) {
        if (kasuga$target != null) throw new IllegalStateException("Recursive world UI canvas draw");
        kasuga$target = target;
        kasuga$viewport = viewport;
        try {
            render();
            endFrame();
        } finally {
            kasuga$target = null;
            kasuga$viewport = null;
        }
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget kasuga$canvasTarget(GameRenderer gameRenderer) {
        return kasuga$target == null ? gameRenderer.mainRenderTarget() : kasuga$target;
    }

    @Redirect(method = {"draw", "enableScissor", "preparePictureInPicture", "getGuiScaleInvalidatingItemAtlasIfChanged"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/GameRenderState;windowRenderState:Lnet/minecraft/client/renderer/state/WindowRenderState;"))
    private WindowRenderState kasuga$canvasViewport(GameRenderState game) {
        return kasuga$viewport == null ? game.windowRenderState : kasuga$viewport;
    }
}

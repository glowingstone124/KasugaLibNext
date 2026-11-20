package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;

import java.util.List;

/** Bridges the version-specific vanilla GUI renderer to a private render target. */
public interface GuiCanvasAccess {
    void kasuga$renderCanvas(RenderTarget target, WindowRenderState viewport);

    interface Factory {
        List<PictureInPictureRendererRegistration<?>> kasuga$pictureInPictureFactories();
        void kasuga$withGuiLighting(Runnable render);
    }
}

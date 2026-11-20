package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;

import java.util.List;

/** Bridges the version-specific vanilla GUI renderer to a private render target. */
public interface GuiCanvasAccess {
    List<PictureInPictureRendererRegistration<?>> kasuga$pictureInPictureFactories();
    void kasuga$renderCanvas(GuiRenderer renderer, RenderTarget target, WindowRenderState viewport);
}

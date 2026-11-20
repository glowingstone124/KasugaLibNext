package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/** Vanilla preview widget whose owning screen closes it when removed. */
public final class ModelPreviewWidget extends AbstractWidget implements AutoCloseable {
    private final GuiModelPreview preview;

    public ModelPreviewWidget(int x, int y, int width, int height, GuiModelPreview preview) {
        super(x, y, width, height, Component.literal("Model preview"));
        this.preview = Objects.requireNonNull(preview, "preview");
    }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                       float frameDelta) {
        preview.render(graphics, getX(), getY(), getWidth(), getHeight());
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    public GuiModelPreview preview() { return preview; }
    @Override public void close() { preview.close(); }
}

package lib.kasuga.scripting.client;

import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Common 26.3 screen for engine failures and missing engines. */
abstract class ScriptEngineDiagnostics extends Screen {
    private final Component details;
    private final Screen previous;
    private final boolean engineFailed;

    protected ScriptEngineDiagnostics(Component title, Component details, Screen previous, boolean engineFailed) {
        super(title);
        this.details = details;
        this.previous = previous;
        this.engineFailed = engineFailed;
    }

    @Override protected void init() {
        int gap = 6;
        int buttonWidth = Math.min(180, Math.max(64, (width - 3 * gap) / 2));
        int left = width / 2 - buttonWidth - gap / 2;
        int right = width / 2 + gap / 2;
        int linksY = height - 52;
        int actionsY = height - 26;
        addRenderableWidget(new FittingMultiLineTextWidget(12, 30, Math.max(16, width - 24),
                Math.max(12, linksY - 36), details, font));
        addRenderableWidget(Button.builder(Component.translatable("fml.button.open.mods.folder"), button ->
                Blaze3D.openPath(minecraft.gameDirectory.toPath().resolve("mods")))
                .bounds(left, linksY, buttonWidth, 20).build());
        if (engineFailed) {
            addRenderableWidget(Button.builder(Component.translatable("fml.button.open.log"), button ->
                    Blaze3D.openPath(minecraft.gameDirectory.toPath().resolve("logs/latest.log")))
                    .bounds(right, linksY, buttonWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Continue without engines"), button ->
                minecraft.gui.setScreen(new ConfirmScreen(confirmed -> minecraft.gui.setScreen(confirmed ? previous : this),
                        Component.literal("Continue without required script engines?"),
                        Component.literal("Content that uses these engines may stop working."))))
                .bounds(left, actionsY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("menu.quit"), button -> minecraft.stop())
                .bounds(right, actionsY, buttonWidth, 20).build());
    }

    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 12, 0xffffffff);
    }
}

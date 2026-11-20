package lib.kasuga.rendering.models.mc.backend.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Transparent, non-pausing pointer layer for world-space panels. */
public final class WorldUiInputScreen extends Screen {
    private final UIBackend backend;
    private @Nullable ClientLevel openedLevel;

    public WorldUiInputScreen(UIBackend backend) {
        super(Component.literal("World UI input"));
        this.backend = Objects.requireNonNull(backend);
    }

    public UIBackend backend() { return backend; }

    @Override protected void init() {
        backend.resetInput();
        openedLevel = minecraft.level;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    @Override public void tick() {
        if (minecraft.level == null || minecraft.level != openedLevel || minecraft.player == null) onClose();
        else if (!minecraft.isWindowActive()) backend.resetInput();
    }

    @Override public void mouseMoved(double x, double y) { backend.getInputRouter().move(x, y); }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        backend.getInputRouter().press(event.x(), event.y(), event.button(), event.modifiers(), doubleClick);
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        backend.getInputRouter().release(event.x(), event.y(), event.button(), event.modifiers());
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        backend.getInputRouter().move(event.x(), event.y());
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        backend.getInputRouter().scroll(x, y, dx, dy);
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) onClose();
        else backend.getInputRouter().keyPressed(event.key(), event.keycode(), event.modifiers());
        return true;
    }

    @Override public boolean keyReleased(KeyEvent event) {
        backend.getInputRouter().keyReleased(event.key(), event.keycode(), event.modifiers());
        return true;
    }

    @Override public boolean charTyped(CharacterEvent event) {
        backend.getInputRouter().charTyped(event.codepoint(), 0);
        return true;
    }

    @Override public boolean preeditUpdated(@Nullable PreeditEvent event) {
        backend.preedit(event);
        return true;
    }

    @Override public void onClose() { minecraft.gui.setScreen(null); }
    @Override public void removed() { backend.resetInput(); openedLevel = null; }
}

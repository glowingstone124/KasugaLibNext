package lib.kasuga.uip.ldlib;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUIClientAccess;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import lib.kasuga.rendering.models.mc.backend.ui.WorldUiTextInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4fc;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Client-owned LDLib2 panel rendered and picked by the normal world-UI backend. */
@EventBusSubscriber(modid = LdLibUiProvider.MOD_ID, value = Dist.CLIENT)
public final class LdElement extends ModularUIScreen implements WorldUiTextInput, AutoCloseable {
    private static final Set<LdElement> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    private final UIBackend backend;
    private ClientLevel level;
    private final boolean tickWhileRendering;
    private boolean closed;

    private LdElement(UIBackend backend, ModularUI ui) {
        super(Objects.requireNonNull(ui), Component.literal("World LDLib2 UI"));
        this.backend = Objects.requireNonNull(backend);
        this.level = Objects.requireNonNull(Minecraft.getInstance().level, "World UI requires an active world");
        this.tickWhileRendering = ui.isTickWhileRending();
        ui.setTickWhileRending(false);
    }

    /** Dimensions are GUI pixels; the transform maps these pixels into absolute world coordinates. */
    public static LdElement attach(UIBackend backend, ModularUI ui, int width, int height, Matrix4fc localToWorld) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("World UI dimensions must be positive");
        if (ModularUIClientAccess.getScreen(Objects.requireNonNull(ui)) != null && !ui.isRemoved()) throw new IllegalArgumentException("UI already has a screen host");
        LdElement panel = new LdElement(backend, ui);
        try {
            panel.init(width, height);
            backend.addRenderable(panel, localToWorld);
            ACTIVE.add(panel);
            return panel;
        } catch (RuntimeException failure) {
            panel.close();
            throw failure;
        }
    }

    @SubscribeEvent
    public static void tickWorldPanels(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        for (LdElement panel : Set.copyOf(ACTIVE)) {
            if (panel.level != mc.level || mc.player == null || !panel.backend.containsRenderable(panel)) panel.close();
            else panel.tick();
        }
    }

    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        for (LdElement panel : Set.copyOf(ACTIVE)) panel.close();
    }

    @Override public void tick() { if (!closed) modularUI.tick(); }

    /** The host screen supplies bounds and widget routing, but is never installed as the active screen. */
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!closed) super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void setFocused(boolean focused) {
        if (!focused) modularUI.clearFocus();
        super.setFocused(focused);
    }

    @Override public boolean hasTextInputFocus() {
        if (closed) return false;
        UIElement focused = modularUI.getFocusedElement();
        if (focused == null) return false;
        for (UIElement element = focused; element != null; element = element.getParent()) {
            if (!element.isActive() || !element.isVisible() || !element.isDisplayed()) return false;
        }
        return focused instanceof TextField field && field.isEditable()
                || focused instanceof TextArea area && area.isEditable();
    }

    public boolean isClosed() { return closed; }

    @Override public void onClose() { modularUI.clearFocus(); }
    @Override public void removed() { close(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ACTIVE.remove(this);
        level = null;
        backend.removeRenderable(this);
        try {
            modularUI.clearFocus();
            modularUI.onRemoved();
        } finally {
            modularUI.setTickWhileRending(tickWhileRendering);
        }
    }
}

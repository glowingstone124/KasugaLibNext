package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.ScenePlacement;
import lib.kasuga.scene.ScenePose;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native screen/widget panels in the main world view, owned by the client thread. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class UIBackend implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UIBackend MAIN_VIEW = new UIBackend();

    private final Map<Renderable, Panel> panels = new LinkedHashMap<>();
    private final WorldUiInputRouter inputRouter = new WorldUiInputRouter();
    private final WorldUiKeyboardCapture keyboard = new WorldUiKeyboardCapture(inputRouter, KeyMapping::releaseAll);
    private final Matrix4f projection = new Matrix4f();
    private @Nullable ClientLevel lastLevel;
    private @Nullable WorldUiCanvas.Painter painter;
    private @Nullable WorldUiCompositor compositor;
    private boolean painting;
    private boolean closeAfterPaint;
    private @Nullable Renderable paintedPanel;

    private UIBackend() {}

    public static UIBackend get() { return MAIN_VIEW; }
    public WorldUiInputRouter getInputRouter() { return inputRouter; }
    public boolean isKeyboardCaptured() { return keyboard.active(); }

    @SubscribeEvent
    public static void registerPipeline(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(WorldUiCompositor.PIPELINE);
    }

    /** Copies the exact projection Minecraft uploads, including camera bob and portal effects. */
    public static void captureProjection(Matrix4fc projection) {
        MAIN_VIEW.projection.set(projection);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void renderWorld(RenderLevelStageEvent.AfterLevel event) {
        if (MAIN_VIEW.panels.isEmpty()) return;
        MAIN_VIEW.render(Minecraft.getInstance(), event.getLevelRenderState().cameraRenderState,
                event.getLevelRenderState().worldPartialTicks);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (MAIN_VIEW.lastLevel != client.level) {
            MAIN_VIEW.lastLevel = client.level;
            MAIN_VIEW.worldChanged();
        }
        for (Map.Entry<Renderable, Panel> entry : new ArrayList<>(MAIN_VIEW.panels.entrySet())) {
            ScenePlacement host = entry.getValue().host;
            if (host != null && !host.isAlive()) MAIN_VIEW.removeRenderable(entry.getKey());
        }
        if (!MAIN_VIEW.canRouteInput(client)) MAIN_VIEW.resetInput();
        MAIN_VIEW.keyboard.refresh(MAIN_VIEW.canCaptureKeyboard(client));
    }

    @SubscribeEvent
    public static void onShutdown(GameShuttingDownEvent event) { MAIN_VIEW.close(); }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() != null || !MAIN_VIEW.canRouteInput(client)) return;
        double x = client.getWindow().getGuiScaledWidth() * .5;
        double y = client.getWindow().getGuiScaledHeight() * .5;
        boolean handled = event.getAction() == InputConstants.PRESS
                ? MAIN_VIEW.inputRouter.press(x, y, event.getButton(), event.getModifiers(), false)
                : MAIN_VIEW.inputRouter.release(x, y, event.getButton(), event.getModifiers());
        MAIN_VIEW.keyboard.refresh(MAIN_VIEW.canCaptureKeyboard(client));
        if (handled) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() != null || !MAIN_VIEW.canRouteInput(client)) return;
        if (MAIN_VIEW.inputRouter.scroll(client.getWindow().getGuiScaledWidth() * .5,
                client.getWindow().getGuiScaledHeight() * .5, event.getScrollDeltaX(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    public static boolean interceptKey(long window, int action, KeyEvent event) {
        Minecraft client = Minecraft.getInstance();
        return window == client.getWindow().handle() && MAIN_VIEW.keyboard.key(
                event.key(), event.keycode(), action, event.modifiers(), MAIN_VIEW.canCaptureKeyboard(client));
    }

    public static boolean interceptCharacter(long window, CharacterEvent event) {
        Minecraft client = Minecraft.getInstance();
        return window == client.getWindow().handle()
                && MAIN_VIEW.keyboard.character(event.codepoint(), 0, MAIN_VIEW.canCaptureKeyboard(client));
    }

    public static void forwardPreedit(long window, @Nullable PreeditEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (window != client.getWindow().handle() || !MAIN_VIEW.canCaptureKeyboard(client)) return;
        if (MAIN_VIEW.keyboard.active()) MAIN_VIEW.preedit(event);
    }

    void preedit(@Nullable PreeditEvent event) {
        GuiEventListener focus = inputRouter.keyboardFocus();
        if (focus != null) KeyboardHandler.submitPreeditEvent(focus, event);
    }

    /** Maps the native EditBox candidate window from its panel to the actual window. */
    public static @Nullable ScreenRectangle projectTextInputArea(int x0, int y0, int x1, int y1) {
        if (!(MAIN_VIEW.paintedPanel instanceof GuiEventListener panel)
                || MAIN_VIEW.inputRouter.keyboardFocus() != panel) return null;
        Vector2f topLeft = MAIN_VIEW.inputRouter.screenPosition(panel, x0, y0);
        Vector2f topRight = MAIN_VIEW.inputRouter.screenPosition(panel, x1, y0);
        Vector2f bottomLeft = MAIN_VIEW.inputRouter.screenPosition(panel, x0, y1);
        Vector2f bottomRight = MAIN_VIEW.inputRouter.screenPosition(panel, x1, y1);
        if (topLeft == null || topRight == null || bottomLeft == null || bottomRight == null) return null;
        int maxX = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int maxY = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int left = Math.clamp((int) Math.floor(Math.min(Math.min(topLeft.x, topRight.x), Math.min(bottomLeft.x, bottomRight.x))), 0, maxX);
        int top = Math.clamp((int) Math.floor(Math.min(Math.min(topLeft.y, topRight.y), Math.min(bottomLeft.y, bottomRight.y))), 0, maxY);
        int right = Math.clamp((int) Math.ceil(Math.max(Math.max(topLeft.x, topRight.x), Math.max(bottomLeft.x, bottomRight.x))), left, maxX);
        int bottom = Math.clamp((int) Math.ceil(Math.max(Math.max(topLeft.y, topRight.y), Math.max(bottomLeft.y, bottomRight.y))), top, maxY);
        return new ScreenRectangle(left, top, right - left, bottom - top);
    }

    public void addRenderable(Renderable renderable) {
        panels.computeIfAbsent(Objects.requireNonNull(renderable), key -> new Panel());
    }

    /** Local X points right and local Y down. All placements are in blocks. */
    public void addRenderable(Renderable renderable, Matrix4fc localToWorld) {
        setTransform(renderable, localToWorld);
    }

    public void addRenderable(Renderable renderable, ScenePose pose) { setPlacement(renderable, pose); }

    public void setTransform(Renderable renderable, Matrix4fc localToWorld) {
        setPlacement(renderable, new ScenePose(Vec3.ZERO, localToWorld));
    }

    public void setPlacement(Renderable renderable, ScenePose pose) {
        addRenderable(renderable);
        Panel panel = panels.get(renderable);
        panel.pose = Objects.requireNonNull(pose);
        panel.host = null;
    }

    /** Follows a borrowed scene host and releases the panel when its host expires. */
    public void addRenderable(Renderable renderable, SceneHost host, Matrix4fc localToHost) {
        ScenePlacement placement = new ScenePlacement(Vec3.ZERO).transform(localToHost).follow(host);
        addRenderable(renderable);
        Panel panel = panels.get(renderable);
        panel.host = placement;
        panel.pose = null;
    }

    /** Supplies the panel's logical rectangle when the renderable has no layout size. */
    public void setBounds(Renderable renderable, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("World UI bounds must be positive");
        addRenderable(renderable);
        panels.get(renderable).bounds = new ScreenRectangle(x, y, width, height);
    }

    public boolean containsRenderable(Renderable renderable) { return panels.containsKey(renderable); }
    public int renderableSize() { return panels.size(); }

    public void removeRenderable(Renderable renderable) {
        Panel panel = panels.remove(renderable);
        if (panel == null) return;
        panel.canvas.close();
        if (renderable instanceof GuiEventListener listener) inputRouter.remove(listener);
        if (panels.isEmpty()) releaseResources();
    }

    public static @Nullable Vector2i getRenderableSize(Renderable renderable) {
        ScreenRectangle bounds = layoutBounds(renderable);
        return bounds == null ? null : new Vector2i(bounds.width(), bounds.height());
    }

    /** Opens an invisible screen for freely moving the pointer over world panels. */
    public WorldUiInputScreen openPointer() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.gui.screen() != null || client.gui.overlay() != null) {
            throw new IllegalStateException("Open world UI input during gameplay");
        }
        WorldUiInputScreen screen = new WorldUiInputScreen(this);
        client.gui.setScreen(screen);
        return screen;
    }

    private void render(Minecraft client, CameraRenderState camera, float partialTick) {
        boolean input = canRouteInput(client);
        if (input) beginInput(client);
        else resetInput();
        List<PanelFrame> frames = new ArrayList<>();
        for (Map.Entry<Renderable, Panel> entry : new ArrayList<>(panels.entrySet())) {
            Renderable renderable = entry.getKey();
            Panel panel = entry.getValue();
            if (panels.get(renderable) != panel) continue;
            ScreenRectangle bounds = panel.bounds == null ? layoutBounds(renderable) : panel.bounds;
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
            Matrix4f view = viewPlacement(client, camera, partialTick, renderable, panel, bounds);
            if (view == null) continue;
            if (input && renderable instanceof GuiEventListener listener) {
                inputRouter.addSurface(listener, view, bounds.left(), bounds.top(), bounds.width(), bounds.height());
            }
            Vector3f center = view.transformPosition(new Vector3f(
                    bounds.left() + bounds.width() * .5f, bounds.top() + bounds.height() * .5f, 0));
            frames.add(new PanelFrame(renderable, panel, bounds, view, center.lengthSquared()));
        }
        if (input) inputRouter.endFrame();
        if (frames.isEmpty() || panels.isEmpty()) return;
        frames.sort(Comparator.comparingDouble(PanelFrame::distance).reversed());
        painting = true;
        try {
            if (painter == null) painter = new WorldUiCanvas.Painter(client.gameRenderer);
            List<WorldUiCompositor.Quad> quads = new ArrayList<>();
            for (PanelFrame frame : frames) {
                if (panels.get(frame.renderable()) != frame.panel()) continue;
                Vector2f mouse = frame.renderable() == inputRouter.hovered() ? inputRouter.hoverPosition() : null;
                GpuTextureView texture;
                paintedPanel = frame.renderable();
                try {
                    texture = frame.panel().canvas.paint(painter, frame.renderable(), frame.bounds(),
                            mouse == null ? -1 : (int) Math.floor(mouse.x),
                            mouse == null ? -1 : (int) Math.floor(mouse.y), partialTick);
                } finally {
                    paintedPanel = null;
                }
                if (texture != null) quads.add(new WorldUiCompositor.Quad(frame.bounds(), frame.view(), texture));
            }
            // Input or a paint callback can remove another panel after it supplied a texture.
            quads.removeIf(quad -> quad.texture().isClosed());
            if (compositor == null) compositor = new WorldUiCompositor();
            compositor.render(client.gameRenderer.mainRenderTarget(), quads);
        } finally {
            painting = false;
            if (closeAfterPaint) releaseResources();
        }
    }

    private void beginInput(Minecraft client) {
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        boolean cursor = client.gui.screen() instanceof WorldUiInputScreen;
        double x = cursor ? client.mouseHandler.getScaledXPos(client.getWindow()) : width * .5;
        double y = cursor ? client.mouseHandler.getScaledYPos(client.getWindow()) : height * .5;
        float depth = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? .5f : 0;
        inputRouter.beginFrame(projection, width, height, x, y, 1, depth);
    }

    private @Nullable Matrix4f viewPlacement(Minecraft client, CameraRenderState camera, float partialTick,
            Renderable renderable, Panel panel, ScreenRectangle bounds) {
        ScenePose placement = panel.pose;
        if (panel.host != null) {
            try {
                placement = panel.host.capture(partialTick);
            } catch (RuntimeException error) {
                LOGGER.error("World UI host failed and was detached", error);
                removeRenderable(renderable);
                return null;
            }
            if (placement == null) {
                removeRenderable(renderable);
                return null;
            }
        }
        if (placement == null) {
            float scale = 1f / client.getWindow().getGuiScaledHeight();
            placement = new ScenePose(Vec3.ZERO, new Matrix4f().scaling(scale, -scale, 1)
                    .translate(-bounds.left() - bounds.width() * .5f, -bounds.top() - bounds.height() * .5f, 0));
        }
        return new Matrix4f(camera.viewRotationMatrix).mul(placement.relativeTo(camera.pos));
    }

    private static @Nullable ScreenRectangle layoutBounds(Renderable renderable) {
        if (renderable instanceof Screen screen) return new ScreenRectangle(0, 0, screen.width, screen.height);
        if (renderable instanceof LayoutElement layout) {
            return new ScreenRectangle(layout.getX(), layout.getY(), layout.getWidth(), layout.getHeight());
        }
        return null;
    }

    private boolean canRouteInput(Minecraft client) {
        Screen screen = client.gui.screen();
        return !panels.isEmpty() && client.isWindowActive() && client.level != null
                && client.player != null && client.gui.overlay() == null
                && (screen == null || screen instanceof WorldUiInputScreen pointer && pointer.backend() == this);
    }

    private boolean canCaptureKeyboard(Minecraft client) {
        return client.gui.screen() == null && canRouteInput(client);
    }

    public void resetInput() {
        keyboard.cancel();
        inputRouter.reset();
    }

    public void worldChanged() {
        resetInput();
        for (Map.Entry<Renderable, Panel> entry : new ArrayList<>(panels.entrySet())) {
            if (entry.getValue().host != null) removeRenderable(entry.getKey());
            else {
                entry.getValue().canvas.close();
                entry.getValue().canvas = new WorldUiCanvas();
            }
        }
        releaseResources();
    }

    private void releaseResources() {
        if (painting) {
            closeAfterPaint = true;
            return;
        }
        closeAfterPaint = false;
        if (painter != null) painter.close();
        if (compositor != null) compositor.close();
        painter = null;
        compositor = null;
    }

    @Override public void close() {
        resetInput();
        for (Renderable renderable : List.copyOf(panels.keySet())) removeRenderable(renderable);
        releaseResources();
    }

    private static final class Panel {
        WorldUiCanvas canvas = new WorldUiCanvas();
        @Nullable ScenePose pose;
        @Nullable ScenePlacement host;
        @Nullable ScreenRectangle bounds;
    }

    private record PanelFrame(Renderable renderable, Panel panel, ScreenRectangle bounds, Matrix4f view, float distance) {}
}

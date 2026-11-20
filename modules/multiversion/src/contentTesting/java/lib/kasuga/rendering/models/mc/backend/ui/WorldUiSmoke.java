package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSources;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.SceneModels;
import lib.kasuga.scene.ScenePose;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class WorldUiSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UIBackend BACKEND = UIBackend.get();
    private static final int LEFT = 12, TOP = 20, WIDTH = 180, HEIGHT = 100;
    private static final int CENTER_X = LEFT + WIDTH / 2, CENTER_Y = TOP + HEIGHT / 2;
    private static Panel panel;
    private static SceneModels occluder;
    private static Vec3 eye;
    private static Vec3 forward;
    private static boolean hostAlive;
    private static boolean capturing;
    private static boolean finished;
    private static int frames;
    private static Pixels baseline;
    private static Phase phase = Phase.VISIBLE;
    private static RuntimeException failure;

    private WorldUiSmoke() {}

    public static void start(Minecraft client) {
        Camera camera = client.gameRenderer.mainCamera();
        eye = camera.position();
        forward = new Vec3(camera.forwardVector());
        Vec3 center = eye.add(forward.scale(3.1));
        Matrix4f rotation = new Matrix4f().rotate(camera.rotation());
        ScenePose hostPose = new ScenePose(center, rotation);
        hostAlive = true;
        panel = new Panel(client);
        BACKEND.addRenderable(panel, new SceneHost() {
            @Override public boolean isAlive() { return hostAlive; }
            @Override public ScenePose pose(float partialTick) { return hostPose; }
        }, new Matrix4f().scaling(.011f, -.011f, .011f).translate(-CENTER_X, -CENTER_Y, 0));
        BACKEND.setBounds(panel, LEFT, TOP, WIDTH, HEIGHT);
    }

    public static boolean started() { return panel != null; }

    public static void afterFrame(Minecraft client) {
        if (failure != null) throw failure;
        if (finished || capturing || client.gui.overlay() != null || ++frames < 12) return;
        frames = 0;
        capturing = true;
        Phase sampled = phase;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path file = client.gameDirectory.toPath().resolve(
                        "kasuga-world-ui-" + sampled.name().toLowerCase(Locale.ROOT) + ".png");
                image.writeToFile(file);
                Pixels pixels = pixels(image);
                client.execute(() -> {
                    try {
                        evaluate(client, sampled, pixels);
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                });
            } catch (IOException error) {
                throw new IllegalStateException("Could not write world UI capture", error);
            }
        });
    }

    private static void evaluate(Minecraft client, Phase sampled, Pixels pixels) {
        LOGGER.info("KASUGA_WORLD_UI_CAPTURE phase={} pixels={}", sampled, pixels);
        long window = client.getWindow().handle();
        KeyEvent forwardKey = new KeyEvent(InputConstants.KEY_W, 'w', 0);
        KeyEvent escape = new KeyEvent(InputConstants.KEY_ESCAPE, 27, 0);
        switch (sampled) {
            case VISIBLE -> {
                require(pixels.panel() > 4000 && pixels.clip() > 100 && pixels.model() > 100 && pixels.title() > 150,
                        "Missing native widgets, text, clipping, or model preview");
                baseline = pixels;
                require(BACKEND.getInputRouter().hovered() == panel, "Crosshair did not pick world panel");
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, forwardKey);
                require(client.options.keyUp.isDown(), "Initial gameplay key did not reach Minecraft");
                click(client);
                require(panel.field.isFocused() && BACKEND.isKeyboardCaptured() && !client.options.keyUp.isDown(),
                        "Crosshair click did not transfer keyboard ownership");
                client.keyboardHandler.textEditing(window, PreeditEvent.fromSdlTextEditing("界", 1, 0));
                require(panel.field.preeditSeen, "IME preedit did not reach the focused EditBox");
                client.keyboardHandler.textInput(window, "Vulkan 界面");
                require(panel.field.getValue().equals("Vulkan 界面"), "Client text input did not reach world panel");
                phase = Phase.EDITING;
            }
            case EDITING -> {
                require(pixels.panel() > 4000 && panel.projectedCandidate, "Focused panel could not project its IME candidate area");
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, escape);
                client.keyboardHandler.keyPress(window, InputConstants.RELEASE, escape);
                client.keyboardHandler.keyPress(window, InputConstants.REPEAT, forwardKey);
                require(client.gui.screen() == null && !BACKEND.isKeyboardCaptured() && !panel.field.isFocused()
                        && !client.options.keyUp.isDown(), "Escape or a stale key repeat leaked into gameplay");
                client.keyboardHandler.keyPress(window, InputConstants.RELEASE, forwardKey);
                require(!BACKEND.openPointer().isPauseScreen(), "Pointer layer paused the game");
                phase = Phase.CURSOR;
            }
            case CURSOR -> {
                require(pixels.panel() > 4000 && client.gui.screen() instanceof WorldUiInputScreen,
                        "Free-cursor screen hid the world panel");
                movePointer(client, CENTER_X - 35, TOP + 76);
                click(client);
                require(panel.clicks == 1, "Free-cursor click did not reach the world panel button");
                movePointer(client, CENTER_X, CENTER_Y);
                MouseButtonInfo button = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);
                client.mouseHandler.onButton(window, button, InputConstants.PRESS);
                movePointer(client, LEFT + WIDTH + 10, CENTER_Y);
                client.mouseHandler.onButton(window, button, InputConstants.RELEASE);
                require(panel.drags > 0 && !panel.isDragging(), "Drag capture did not survive leaving the world panel");
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, escape);
                client.keyboardHandler.keyPress(window, InputConstants.RELEASE, escape);
                require(client.gui.screen() == null, "Escape did not leave the pointer screen");
                Vec3 block = eye.add(forward.scale(2)).add(-.14, -.4, 0);
                occluder = SceneModels.open(client.level);
                occluder.add(MinecraftModelSources.blocks(client.level,
                        List.of(new StructureBlockInfo(BlockPos.ZERO, Blocks.SMOOTH_STONE.defaultBlockState(), null))), block)
                        .transform(new Matrix4f().scaling(.42f, .8f, .22f));
                phase = Phase.OCCLUDED;
            }
            case OCCLUDED -> {
                require(pixels.panel() > 1000 && pixels.panel() < baseline.panel() * .82, "Panel did not respect world depth");
                occluder.close();
                hostAlive = false;
                phase = Phase.REMOVED;
            }
            case REMOVED -> {
                require(pixels.panel() < 40 && !BACKEND.containsRenderable(panel) && BACKEND.getInputRouter().hovered() == null,
                        "Expired host left a world panel or retained input ownership");
                panel.preview.close();
                finished = true;
                LOGGER.info("KASUGA_WORLD_UI_PASS canvas,scissor,text,pip,world-depth,keyboard,ime,pointer,drag,host-expiry");
                client.stop();
            }
        }
        capturing = false;
    }

    private static void click(Minecraft client) {
        long window = client.getWindow().handle();
        MouseButtonInfo button = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);
        client.mouseHandler.onButton(window, button, InputConstants.PRESS);
        client.mouseHandler.onButton(window, button, InputConstants.RELEASE);
    }

    private static void movePointer(Minecraft client, float x, float y) {
        Vector2f cursor = BACKEND.getInputRouter().screenPosition(panel, x, y);
        require(cursor != null, "Panel point could not be projected into the screen");
        client.mouseHandler.onMove(client.getWindow().handle(),
                cursor.x * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
                cursor.y * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight(), 0, 0);
        client.mouseHandler.handleAccumulatedMovement();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static Pixels pixels(NativeImage image) {
        int panel = 0, clip = 0, model = 0, title = 0;
        for (int y = image.getHeight() / 5; y < image.getHeight() * 4 / 5; y++) {
            for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                int color = image.getPixel(x, y);
                int r = color >>> 16 & 255, g = color >>> 8 & 255, b = color & 255;
                if (r > 100 && g < r * .52 && b > 100 && b > g * 1.55) panel++;
                if (r > 225 && g > 105 && g < 170 && b < 52) clip++;
                if (g > 155 && b > 115 && r < 35) model++;
                if (r > 226 && g > 220 && b > 205) title++;
            }
        }
        return new Pixels(panel, clip, model, title);
    }

    private static final class Panel extends AbstractContainerEventHandler implements Renderable {
        private final Minecraft client;
        private final TextField field;
        private final Button button;
        private final GuiModelPreview preview = new GuiModelPreview(
                Identifier.parse("kasuga_lib:models/smoke/animated_triangle.gltf"), null);
        private int clicks;
        private int drags;
        private boolean projectedCandidate;

        private Panel(Minecraft client) {
            this.client = client;
            field = new TextField(client);
            button = Button.builder(Component.literal("Apply"), unused -> clicks++)
                    .bounds(CENTER_X - 65, TOP + 65, 60, 20).build();
        }

        @Override public List<? extends GuiEventListener> children() { return List.of(field, button); }

        @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            drags++;
            return super.mouseDragged(event, dx, dy);
        }

        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            require(graphics.guiWidth() == WIDTH && graphics.guiHeight() == HEIGHT, "Canvas exposed the main window size");
            graphics.fill(LEFT + 3, TOP + 3, LEFT + WIDTH - 3, TOP + HEIGHT - 3, 0xdfbd228d);
            graphics.text(client.font, "Kasuga World UI", LEFT + 10, TOP + 12, 0xffffffff, true);
            graphics.enableScissor(LEFT + 140, TOP + 10, LEFT + 164, TOP + 17);
            graphics.fill(LEFT + 125, TOP + 8, LEFT + WIDTH, TOP + 22, 0xffff830a);
            graphics.disableScissor();
            field.extractRenderState(graphics, mouseX, mouseY, delta);
            if (field.isFocused() && UIBackend.projectTextInputArea(CENTER_X, CENTER_Y, CENTER_X + 1, CENTER_Y + 9) != null) {
                projectedCandidate = true;
            }
            button.extractRenderState(graphics, mouseX, mouseY, delta);
            preview.render(graphics, LEFT + 123, TOP + 36, 44, 44);
        }
    }

    private static final class TextField extends EditBox {
        private boolean preeditSeen;

        private TextField(Minecraft client) {
            super(client.font, CENTER_X - 53, CENTER_Y - 10, 82, 20, Component.literal("Panel text"));
        }

        @Override public boolean preeditUpdated(PreeditEvent event) {
            if (event != null) preeditSeen = true;
            return super.preeditUpdated(event);
        }
    }

    private enum Phase { VISIBLE, EDITING, CURSOR, OCCLUDED, REMOVED }
    private record Pixels(int panel, int clip, int model, int title) {}
}

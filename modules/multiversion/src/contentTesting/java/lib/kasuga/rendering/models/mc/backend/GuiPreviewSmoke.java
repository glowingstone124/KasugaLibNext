package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.backend.ui.ModelPreviewWidget;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public final class GuiPreviewSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static PreviewScreen screen;
    private static Phase phase = Phase.INITIAL;
    private static int frames;
    private static int initialRightPixels;
    private static boolean capturing;
    private static boolean reloaded;
    private static RuntimeException failure;
    private static ModelInstance originalLeft;
    private static ModelInstance originalRight;

    private GuiPreviewSmoke() {}

    public static void start(Minecraft client) {
        screen = new PreviewScreen(Identifier.parse("kasuga_lib:models/smoke/native_java.json"));
        client.gui.setScreen(screen);
    }

    public static boolean started() { return screen != null; }

    public static void afterFrame(Minecraft client) {
        if (failure != null) throw failure;
        if (capturing || client.gui.screen() != screen || client.gui.overlay() != null) return;
        if (phase == Phase.RELOADED && !reloaded) return;
        String modelFailure = screen.left.preview().failure();
        if (modelFailure == null) modelFailure = screen.right.preview().failure();
        if (modelFailure != null) throw new IllegalStateException(modelFailure);
        if (++frames < 12) return;
        frames = 0;
        capturing = true;
        Phase sampled = phase;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path file = client.gameDirectory.toPath().resolve("kasuga-gui-preview-" + sampled.name().toLowerCase(Locale.ROOT) + ".png");
                image.writeToFile(file);
                Pixels pixels = read(image, screen.clipEdge());
                client.execute(() -> {
                    try {
                        evaluate(client, sampled, pixels, file);
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                });
            } catch (IOException error) {
                throw new IllegalStateException("Could not write GUI preview capture", error);
            }
        });
    }

    private static void evaluate(Minecraft client, Phase sampled, Pixels pixels, Path file) {
        LOGGER.info("KASUGA_GUI_PREVIEW_CAPTURE phase={} jade={} magenta={} cyan={} clipped={} file={}",
                sampled, pixels.jade(), pixels.magenta(), pixels.cyan(), pixels.clipped(), file);
        if (pixels.magenta() < 300 || pixels.cyan() < 400 || (sampled != Phase.MMD && pixels.jade() < 700)) {
            throw new IllegalStateException("Both GUI model previews must appear in " + file);
        }
        ModelInstance left = screen.left.preview().instance();
        ModelInstance right = screen.right.preview().instance();
        if (left == null || right == null || left == right) {
            throw new IllegalStateException("GUI previews did not own distinct instances");
        }
        switch (sampled) {
            case INITIAL -> {
                initialRightPixels = pixels.cyan();
                originalLeft = left;
                originalRight = right;
                phase = Phase.RESIZED;
                screen.resizePreview();
                capturing = false;
            }
            case RESIZED -> {
                checkClip(pixels);
                phase = Phase.RELOADED;
                capturing = false;
                client.reloadResourcePacks().whenComplete((ignored, error) -> client.execute(() -> {
                    if (error == null) reloaded = true;
                    else failure = new IllegalStateException("Could not reload preview resources", error);
                }));
            }
            case RELOADED -> {
                checkClip(pixels);
                if (!originalLeft.isClosed() || !originalRight.isClosed()
                        || left == originalLeft || right == originalRight) {
                    throw new IllegalStateException("Resource reload kept stale preview instances");
                }
                client.gui.setScreen(null);
                if (!left.isClosed() || !right.isClosed() || !screen.left.preview().isClosed()
                        || !screen.right.preview().isClosed()) {
                    throw new IllegalStateException("Removing the screen did not close its previews");
                }
                LOGGER.info("KASUGA_GUI_PREVIEW_PASS two-panels,depth-canvas,camera,resize,scissor,reload,closed");
                phase = Phase.MMD;
                screen = new PreviewScreen(Identifier.parse("kasuga_lib:models/smoke/mmd/sample.pmx"));
                client.gui.setScreen(screen);
                capturing = false;
            }
            case MMD -> {
                client.gui.setScreen(null);
                if (!left.isClosed()) throw new IllegalStateException("Removing the screen kept the MMD preview alive");
                LOGGER.info("KASUGA_MMD_GUI_PASS pmx,diffuse,closed");
                StructurePreviewSmoke.start(client);
            }
        }
    }

    private static void checkClip(Pixels pixels) {
        if (pixels.clipped() != 0 || pixels.cyan() >= initialRightPixels * .8) {
            throw new IllegalStateException("Resized GUI preview did not respect its active scissor");
        }
    }

    private static Pixels read(NativeImage image, double clipEdge) {
        int jade = 0, magenta = 0, cyan = 0, clipped = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getPixel(x, y);
                int r = color >>> 16 & 255;
                int g = color >>> 8 & 255;
                int b = color & 255;
                if (x < image.getWidth() / 2) {
                    if (g > 80 && r * 2 < g && b * 5 < g * 4) jade++;
                    if (r > 100 && b > 55 && g * 2 < r) magenta++;
                } else if (g > 80 && b > 80 && r * 4 < g && r * 4 < b) {
                    cyan++;
                    if (x >= Math.ceil(clipEdge * image.getWidth()) + 2) clipped++;
                }
            }
        }
        return new Pixels(jade, magenta, cyan, clipped);
    }

    private record Pixels(int jade, int magenta, int cyan, int clipped) {}
    private enum Phase { INITIAL, RESIZED, RELOADED, MMD }

    private static final class PreviewScreen extends Screen {
        private final ModelPreviewWidget left;
        private final ModelPreviewWidget right = new ModelPreviewWidget(0, 0, 1, 1,
                new GuiModelPreview(Identifier.parse("kasuga_lib:models/smoke/animated_triangle.gltf"), null));
        private boolean clipped;

        private PreviewScreen(Identifier leftResource) {
            super(Component.literal("Model preview smoke"));
            left = new ModelPreviewWidget(0, 0, 1, 1, new GuiModelPreview(leftResource, null));
        }

        @Override protected void init() {
            int size = Math.min(width / 3, height / 2);
            left.setRectangle(size, size, width / 4 - size / 2, height / 2 - size / 2);
            right.setRectangle(size, size, width * 3 / 4 - size / 2, height / 2 - size / 2);
            addRenderableWidget(left);
        }

        void resizePreview() {
            int size = right.getWidth() * 3 / 4;
            right.setRectangle(size, size, width * 3 / 4 - size / 2, height / 2 - size / 2);
            right.preview().setView(.25f, -.12f, 1.07f, .04f, -.03f);
            clipped = true;
        }

        private int clipX() { return right.getX() + right.getWidth() / 2; }
        double clipEdge() { return clipped ? (double) clipX() / width : 1; }

        @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
            graphics.fill(0, 0, width, height, 0xff162239);
        }

        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
            super.extractRenderState(graphics, mouseX, mouseY, tick);
            if (clipped) graphics.enableScissor(right.getX(), right.getY(), clipX(), right.getY() + right.getHeight());
            try {
                right.extractRenderState(graphics, mouseX, mouseY, tick);
            } finally {
                if (clipped) graphics.disableScissor();
            }
        }

        @Override public void removed() {
            left.close();
            right.close();
            super.removed();
        }
    }
}

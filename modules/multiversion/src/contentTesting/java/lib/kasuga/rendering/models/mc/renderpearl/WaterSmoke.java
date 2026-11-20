package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.host.DocumentSmoke;
import lib.kasuga.rendering.effect.builtin.water.WaterEffects;
import lib.kasuga.rendering.effect.builtin.water.WaterSurface;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import lib.kasuga.rendering.water.ShallowWaterGrid;
import lib.kasuga.rendering.water.WaterOptics;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.InputStream;

final class WaterSmoke {
    private static final Identifier TRIANGLE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "models/smoke/triangle.gltf");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "smoke/water/white");
    // Make water easy to distinguish from the sky and the cyan occluder in a readback.
    private static final WaterOptics TEST_OPTICS = new WaterOptics(8, 8, 8, .85f, .03f, .02f, 0);
    private static RenderPearlWorldModels.Mount occluder;
    private static WaterSurface water;
    private static Stage stage = Stage.BASELINE;
    private static int baselineOccluder;
    private static int baselineSky;
    private static int frames;
    private static boolean pending;
    private static volatile Sample sample;

    private WaterSmoke() {}

    private record Sample(int foreground, int background) {}

    private enum Stage { BASELINE, VISIBLE, DISABLED, RESUMED, CLOSED, DONE }

    static boolean needsFrames() { return stage != Stage.DONE; }

    static void afterFrame(Minecraft client) throws IOException {
        if (occluder == null) {
            ModelInstance triangle;
            try (InputStream input = client.getResourceManager().open(TRIANGLE)) {
                triangle = new ModelInstance(GltfModelConverter.convert(GltfLoader.load(input)), null, null, null, null, null);
            }
            NativeImage white = new NativeImage(1, 1, false);
            white.setPixel(0, 0, 0xffffffff);
            client.getTextureManager().register(WHITE, new DynamicTexture(WHITE::toString, white));
            Camera camera = client.gameRenderer.mainCamera();
            Vec3 placement = camera.position().add(new Vec3(camera.forwardVector()).scale(2)).add(0, -.55, 0);
            occluder = RenderPearlWorldModels.mount(client.level, triangle, Vec3.ZERO, name -> WHITE);
            occluder.setPlacement(placement, new Matrix4f().rotate(camera.rotation()));
        }
        Sample completed = sample;
        if (completed != null) {
            sample = null;
            advance(client, completed.foreground(), completed.background());
            return;
        }
        if (pending || ++frames < 8) return;
        pending = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int foreground;
            int background;
            try (image) {
                int y = image.getHeight() * 7 / 10;
                foreground = image.getPixel(image.getWidth() / 2, y);
                background = image.getPixel(image.getWidth() * 7 / 10, y);
            }
            sample = new Sample(foreground, background);
        });
    }

    private static void advance(Minecraft client, int foreground, int background) {
        if (stage != Stage.BASELINE) {
            require(colorDifference(foreground, baselineOccluder) < 12,
                    stage + " water covered foreground: " + Integer.toHexString(foreground));
        }
        switch (stage) {
            case BASELINE -> {
                require((foreground >>> 8 & 255) > 120 && (foreground & 255) > 140
                                && (foreground >>> 16 & 255) < 65,
                        "Opaque triangle did not cover the water occlusion sample: " + Integer.toHexString(foreground));
                baselineOccluder = foreground;
                baselineSky = background;
                Vec3 eye = client.gameRenderer.mainCamera().position();
                water = WaterEffects.open(client.level, eye.x, (float) eye.y - 2, eye.z, 64, .25f, .5f)
                        .followPlayer(false).playerInteraction(false).optics(TEST_OPTICS);
                ShallowWaterGrid grid = water.simulation();
                int middle = grid.size() / 2;
                float rest = grid.cellDepth(middle, middle);
                water.interact(eye, new Vec3(0, 0, 2), 1, .16f);
                require(water.interactionCount() == 1 && grid.cellDepth(middle, middle) < rest - .03f,
                        "Water interaction did not displace the surface");
                stage = Stage.VISIBLE;
            }
            case VISIBLE, RESUMED -> {
                int red = background >>> 16 & 255;
                int green = background >>> 8 & 255;
                require(red > green + 60 && colorDifference(background, baselineSky) > 80,
                        stage + " water did not affect the sky sample: " + Integer.toHexString(background));
                if (stage == Stage.VISIBLE) {
                    water.renderEnabled(false);
                    stage = Stage.DISABLED;
                } else {
                    water.close();
                    require(water.closed() && WaterEffects.activeCount() == 0, "Closed water remained registered");
                    stage = Stage.CLOSED;
                }
            }
            case DISABLED, CLOSED -> {
                require(colorDifference(background, baselineSky) < 20,
                        stage + " water remained on screen: " + Integer.toHexString(background));
                if (stage == Stage.DISABLED) {
                    water.renderEnabled(true);
                    stage = Stage.RESUMED;
                } else {
                    occluder.close();
                    client.getTextureManager().release(WHITE);
                    stage = Stage.DONE;
                    LogUtils.getLogger().info("KASUGA_WATER_PASS displacement,visible-water,foreground-depth,toggle,close");
                    DocumentSmoke.start(client);
                }
            }
            case DONE -> throw new IllegalStateException("Water smoke already completed");
        }
        pending = false;
        frames = 0;
    }

    private static int colorDifference(int first, int second) {
        return Math.abs((first >>> 16 & 255) - (second >>> 16 & 255))
                + Math.abs((first >>> 8 & 255) - (second >>> 8 & 255))
                + Math.abs((first & 255) - (second & 255));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

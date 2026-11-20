package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.host.DocumentSmoke;
import lib.kasuga.rendering.effect.builtin.blackhole.BlackHoleEffect;
import lib.kasuga.rendering.effect.builtin.blackhole.BlackHoleEffects;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.InputStream;

final class BlackHoleSmoke {
    private static final Identifier SOURCE = id("models/smoke/triangle.gltf");
    private static final Identifier TEXTURE = id("smoke/black_hole/white");
    private static final Identifier HOLE = id("smoke/black_hole");
    private static ModelInstance model;
    private static RenderPearlWorldModels.Mount occluder;
    private static BlackHoleEffects.Registration hole;
    private static Stage stage = Stage.BASELINE;
    private static int frames;
    private static int baseline;
    private static boolean pending;
    private static boolean finished;

    private BlackHoleSmoke() {}

    static boolean needsFrames() { return !finished; }

    static void afterFrame(Minecraft client) throws IOException {
        Camera camera = client.gameRenderer.mainCamera();
        if (model == null) {
            try (InputStream input = client.getResourceManager().open(SOURCE)) {
                model = new ModelInstance(GltfModelConverter.convert(GltfLoader.load(input)), null, null, null, null, null);
            }
            NativeImage white = new NativeImage(1, 1, false);
            white.setPixel(0, 0, 0xffffffff);
            client.getTextureManager().register(TEXTURE, new DynamicTexture(TEXTURE::toString, white));
            occluder = RenderPearlWorldModels.mount(client.level, model, Vec3.ZERO, name -> TEXTURE);
            occluder.setPlacement(pointInFront(camera, 1.2), new Matrix4f().rotate(camera.rotation()));
        }
        if (pending || ++frames < 8) return;
        pending = true;
        Stage sampleStage = stage;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int center;
            try (image) {
                center = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
            }
            client.execute(() -> advance(client, sampleStage, center));
        });
    }

    private static void advance(Minecraft client, Stage sampled, int pixel) {
        switch (sampled) {
            case BASELINE -> {
                require((pixel >>> 8 & 255) > 120 && (pixel & 255) > 140,
                        "The opaque cyan triangle did not cover the sample: " + Integer.toHexString(pixel));
                baseline = pixel;
            }
            case VISIBLE -> require(Math.max(pixel >>> 16 & 255, Math.max(pixel >>> 8 & 255, pixel & 255)) < 28,
                    "The visible lens did not darken the opaque sample: " + Integer.toHexString(pixel));
            case OCCLUDED, REMOVED -> require(sameColor(pixel, baseline),
                    sampled + " lens changed the opaque sample: " + Integer.toHexString(pixel));
        }
        Camera camera = client.gameRenderer.mainCamera();
        switch (sampled) {
            case BASELINE -> {
                BlackHoleEffect effect = BlackHoleEffect.builder(HOLE, pointInFront(camera, 3)).eventHorizonRadius(.08f).build();
                BlackHoleEffects.Registration original = BlackHoleEffects.register(effect);
                hole = BlackHoleEffects.register(effect);
                original.close();
                require(BlackHoleEffects.size() == 1, "Stale handle removed the replacement lens");
                stage = Stage.OCCLUDED;
            }
            case OCCLUDED -> {
                hole.update(hole.effect().toBuilder().position(pointInFront(camera, .7)).build());
                stage = Stage.VISIBLE;
            }
            case VISIBLE -> {
                hole.close();
                require(BlackHoleEffects.size() == 0, "Lens handle did not remove the effect");
                stage = Stage.REMOVED;
            }
            case REMOVED -> {
                occluder.close();
                model.close();
                client.getTextureManager().release(TEXTURE);
                finished = true;
                LogUtils.getLogger().info("KASUGA_BLACK_HOLE_PASS generated-shader,reverse-depth,update,replacement,close");
                DocumentSmoke.start(client);
            }
        }
        frames = 0;
        pending = false;
    }

    private static boolean sameColor(int first, int second) {
        return Math.abs((first >>> 16 & 255) - (second >>> 16 & 255)) <= 3
                && Math.abs((first >>> 8 & 255) - (second >>> 8 & 255)) <= 3
                && Math.abs((first & 255) - (second & 255)) <= 3;
    }

    private static Vec3 pointInFront(Camera camera, double distance) {
        Vector3fc forward = camera.forwardVector();
        return camera.position().add(forward.x() * distance, forward.y() * distance, forward.z() * distance);
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private enum Stage { BASELINE, OCCLUDED, VISIBLE, REMOVED }
}

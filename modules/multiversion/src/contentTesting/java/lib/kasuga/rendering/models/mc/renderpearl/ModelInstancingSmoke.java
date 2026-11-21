package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelInstancingSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier MODEL = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "models/smoke/triangle.gltf");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "smoke/instancing_white");
    private static final int COUNT = 64;

    private static List<ModelInstance> direct = List.of();
    private static List<ModelInstance> instanced = List.of();
    private static boolean started;
    private static boolean capturing;
    private static boolean finished;
    private static int frames;
    private static Sample reference;

    private ModelInstancingSmoke() {}

    static boolean needsFrames() {
        return !finished;
    }

    static void afterFrame(Minecraft client) throws IOException {
        if (!started) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixel(0, 0, 0xffffffff);
            client.getTextureManager().register(WHITE, new DynamicTexture(WHITE::toString, image));
            Model first = load(client);
            Model second = load(client);
            direct = new ArrayList<>(COUNT);
            instanced = new ArrayList<>(COUNT);
            for (int i = 0; i < COUNT; i++) {
                direct.add(create(i % 2 == 0 ? first : second));
                instanced.add(create(first));
            }
            started = true;
        }
        if (capturing || ++frames < 8) return;
        capturing = true;
        boolean directFrame = reference == null;
        RenderPearlModelBatcher.FrameStats stats = RenderPearlModelBatcher.lastFrame();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            Sample sample;
            try (image) {
                sample = sample(image);
                image.writeToFile(client.gameDirectory.toPath().resolve(directFrame
                        ? "kasuga-model-batching-direct.png" : "kasuga-model-batching-instanced.png"));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save the model batching frame", failure);
            }
            client.execute(() -> {
                if (directFrame) {
                    if (sample.pixels() < 2000 || stats.instancedModels() != 0 || stats.geometryVertices() < COUNT * 4) {
                        throw new IllegalStateException("Missing direct model reference: " + sample + " " + stats);
                    }
                    reference = sample;
                    frames = 0;
                    capturing = false;
                    return;
                }
                if (stats.instancedModels() != COUNT || stats.draws() != 1 || stats.geometryVertices() > 8
                        || Math.abs(sample.pixels() - reference.pixels()) > 40
                        || Math.abs(sample.averageGreen() - reference.averageGreen()) > 2) {
                    throw new IllegalStateException("Instanced frame differs from direct: " + sample + " vs "
                            + reference + " " + stats);
                }
                LOGGER.info("KASUGA_MODEL_INSTANCING_PASS models={} draws={} geometryVertices={} pixels={}",
                        stats.instancedModels(), stats.draws(), stats.geometryVertices(), sample.pixels());
                for (ModelInstance model : instanced) model.close();
                for (ModelInstance model : direct) model.close();
                client.getTextureManager().release(WHITE);
                finished = true;
            });
        });
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        if (!started || finished) return;
        Camera camera = event.getCamera();
        List<ModelInstance> models = reference == null ? direct : instanced;
        for (int i = 0; i < models.size(); i++) {
            Vector3f location = new Vector3f((i % 8 - 3.5f) * .18f, (i / 8 - 3.5f) * .15f, -1.7f)
                    .rotate(camera.rotation());
            Matrix4f placement = new Matrix4f().translation(location).rotate(camera.rotation()).scale(.11f);
            Vec3 eye = camera.position();
            Matrix4d world = new Matrix4d().translation(eye.x, eye.y, eye.z).mul(new Matrix4d(placement));
            int light = i % 2 == 0 ? LightCoordsUtil.FULL_BRIGHT : LightCoordsUtil.pack(9, 0);
            RenderPearlWorldModels.addToFrame(event, models.get(i), texture -> WHITE, placement, world, light);
        }
    }

    private static Model load(Minecraft client) throws IOException {
        try (InputStream input = client.getResourceManager().open(MODEL)) {
            return GltfModelConverter.convert(GltfLoader.load(input));
        }
    }

    private static ModelInstance create(Model model) {
        return new ModelInstance(model, null, null, null, null, null);
    }

    private static Sample sample(NativeImage image) {
        int pixels = 0;
        long totalGreen = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                int color = image.getPixel(x, y);
                int red = color >>> 16 & 255;
                int green = color >>> 8 & 255;
                int blue = color & 255;
                if (green > 36 && blue > 36 && red * 4 < green && red * 4 < blue) {
                    pixels++;
                    totalGreen += green;
                }
            }
        }
        return new Sample(pixels, pixels == 0 ? 0 : (double) totalGreen / pixels);
    }

    private record Sample(int pixels, double averageGreen) {}
}

package lib.kasuga.rendering.models.mc.renderpearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.backend.DepthBias;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelDepthBiasSmoke {
    private static final Identifier MODEL = id("models/smoke/triangle.gltf");
    private static final Identifier WHITE = id("smoke/depth_white");

    private enum Phase { COPLANAR, DECAL, TRANSLUCENT, DONE }

    private static Phase phase = Phase.COPLANAR;
    private static ModelInstance backing;
    private static ModelInstance decal;
    private static ModelInstance translucent;
    private static boolean capturing;
    private static int frames;

    private ModelDepthBiasSmoke() {}

    static boolean needsFrames() {
        return phase != Phase.DONE;
    }

    static void afterFrame(Minecraft client) throws IOException {
        if (backing == null) {
            backing = load(client, false, false);
            decal = load(client, true, false);
            translucent = load(client, true, true);
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixel(0, 0, 0xffffffff);
            client.getTextureManager().register(WHITE, new DynamicTexture(WHITE::toString, image));
        }
        if (capturing || ++frames < 8) return;
        capturing = true;
        Phase captured = phase;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int center;
            try (image) {
                center = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
            }
            client.execute(() -> evaluate(client, captured, center));
        });
    }

    private static void evaluate(Minecraft client, Phase captured, int color) {
        int red = color >>> 16 & 255;
        int green = color >>> 8 & 255;
        switch (captured) {
            case COPLANAR -> {
                if (green < 90 || red > 15) throw new IllegalStateException("Coplanar backing is missing: " + color);
                decal.setDepthBias(DepthBias.DECAL);
                phase = Phase.DECAL;
            }
            case DECAL -> {
                if (red < 90 || green > 15) throw new IllegalStateException("The depth bias did not retain the decal: " + color);
                phase = Phase.TRANSLUCENT;
            }
            case TRANSLUCENT -> {
                if (red < 30 || green < 30) throw new IllegalStateException("The translucent model did not blend: " + color);
                backing.close();
                decal.close();
                translucent.close();
                client.getTextureManager().release(WHITE);
                phase = Phase.DONE;
                LogUtils.getLogger().info("KASUGA_MODEL_DEPTH_BLEND_PASS coplanar-order,reverse-z-bias,translucent-blend");
            }
            case DONE -> throw new IllegalStateException("Unexpected capture after model depth smoke completion");
        }
        frames = 0;
        capturing = false;
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        if (backing == null || phase == Phase.DONE) return;
        if (phase == Phase.TRANSLUCENT) {
            submit(event, backing, 1.7f);
            submit(event, translucent, 1.6f);
        } else {
            submit(event, decal, 1.7f);
            submit(event, backing, 1.7f);
        }
    }

    private static void submit(ExtractLevelRenderStateEvent event, ModelInstance model, float distance) {
        Camera camera = event.getCamera();
        Vector3f offset = new Vector3f(camera.forwardVector()).mul(distance);
        Matrix4f placement = new Matrix4f().translation(offset).rotate(camera.rotation());
        Vec3 origin = camera.position();
        Matrix4d world = new Matrix4d().translation(origin.x, origin.y, origin.z).mul(new Matrix4d(placement));
        RenderPearlWorldModels.addToFrame(event, model, texture -> WHITE, placement, world, LightCoordsUtil.FULL_BRIGHT);
    }

    private static ModelInstance load(Minecraft client, boolean red, boolean blended) throws IOException {
        try (Reader reader = client.getResourceManager().getResourceOrThrow(MODEL).openAsReader()) {
            JsonObject gltf = JsonParser.parseReader(reader).getAsJsonObject();
            if (red) {
                JsonObject material = gltf.getAsJsonArray("materials").get(0).getAsJsonObject();
                JsonArray color = material.getAsJsonObject("pbrMetallicRoughness").getAsJsonArray("baseColorFactor");
                color.set(0, new com.google.gson.JsonPrimitive(1));
                color.set(1, new com.google.gson.JsonPrimitive(0));
                color.set(2, new com.google.gson.JsonPrimitive(0));
                if (blended) {
                    color.set(3, new com.google.gson.JsonPrimitive(.5f));
                    material.addProperty("alphaMode", "BLEND");
                }
            }
            ByteArrayInputStream input = new ByteArrayInputStream(gltf.toString().getBytes(StandardCharsets.UTF_8));
            return new ModelInstance(GltfModelConverter.convert(GltfLoader.load(input)), null, null, null, null, null);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path);
    }
}

package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.host.FsmClientSmoke;
import lib.kasuga.host.DocumentSmoke;
import lib.kasuga.rendering.models.mc.backend.GuiPreviewSmoke;
import lib.kasuga.rendering.models.mc.backend.StructurePreviewSmoke;
import lib.kasuga.rendering.models.mc.backend.ui.WorldUiSmoke;
import lib.kasuga.host.ResourcePackSmoke;
import lib.kasuga.host.RegistrationSmoke;
import lib.kasuga.rendering.effect.builtin.light.WorldLights;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphSmoke;
import lib.kasuga.rendering.lighting.LightColor;
import lib.kasuga.rendering.lighting.LightSource;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import lib.kasuga.rendering.output.mc.FrameOutputSmoke;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RenderPearlClientSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXPECTED_BACKEND = System.getProperty("kasuga.graphicsSmoke", "");
    private static final Identifier SOURCE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "models/smoke/triangle.gltf");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "smoke/white");
    private static final long START = System.nanoTime();
    private static boolean createdWorld;
    private static boolean cameraRequested;
    private static boolean capturing;
    private static boolean modelsVerified;
    private static int frames;
    private static ModelInstance model;
    private static RenderPearlWorldModels.Mount mount;
    private static WorldLights.Mount light;
    private static SceneLightingSmoke scenes;
    private static Lighting lighting = Lighting.BASELINE;
    private static double baselineRed;
    private static int modelPixels;
    private static double bedrockRestX;

    enum Lighting {
        BASELINE(false), POINT(true), AREA(true), VOLUME(true), OCCLUDED(false), CLEARED(false),
        SCENE_LIT(true), SCENE_MOVED(false), SCENE_HIDDEN(false), SCENE_SHOWN(true), SCENE_CLOSED(false),
        LOCATOR_LIT(true), LOCATOR_BONE_MOVED(false), LOCATOR_RESTORED(true), LOCATOR_CLOSED(false),
        EMBEDDED_TEXTURE(false), OBJ_MATERIAL(false), JAVA_JSON(false), BEDROCK_JSON(false), BEDROCK_POSED(false);

        final boolean lit;

        Lighting(boolean lit) {
            this.lit = lit;
        }
    }

    private RenderPearlClientSmoke() {}

    @SubscribeEvent
    public static void holdSmokeCamera(ViewportEvent.ComputeCameraAngles event) {
        if (!cameraRequested) return;
        event.setYaw(0);
        event.setPitch(0);
        event.setRoll(0);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws IOException {
        if (EXPECTED_BACKEND.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) {
            throw new IllegalStateException("Timed out waiting for the RenderPearl smoke world");
        }
        if (createdWorld || client.gui.overlay() != null || client.gui.screen() == null) return;
        if (!ResourcePackSmoke.clientReady()) return;
        String actualBackend = RenderSystem.getDevice().getDeviceInfo().backendName();
        if (!actualBackend.toLowerCase(Locale.ROOT).contains(EXPECTED_BACKEND.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Expected " + EXPECTED_BACKEND + ", got " + actualBackend);
        }
        String worldName = "kasuga-renderpearl-" + Long.toString(System.currentTimeMillis(), 36);
        LevelSettings settings = new LevelSettings(worldName, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true, WorldDataConfiguration.DEFAULT);
        client.options.pauseOnLostFocus = false;
        createdWorld = true;
        client.createWorldOpenFlows().createFreshLevel(worldName, settings, new WorldOptions(26_003L, true, false),
                WorldPresets::createNormalWorldDimensions, client.gui.screen());
    }

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) throws IOException {
        if (EXPECTED_BACKEND.isEmpty() || capturing || modelsVerified) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.gui.screen() != null || client.gui.overlay() != null) return;
        if (!cameraRequested) {
            positionCamera(client);
            return;
        }
        Camera camera = client.gameRenderer.mainCamera();
        if (camera.position().y < 240 || !client.player.getAbilities().flying) return;
        if (!RegistrationSmoke.clientReady(client) || !FsmClientSmoke.ready()) return;
        if (mount == null) {
            try (InputStream input = client.getResourceManager().open(SOURCE)) {
                model = new ModelInstance(GltfModelConverter.convert(GltfLoader.load(input)),
                        null, null, null, null, null);
            }
            NativeImage white = new NativeImage(1, 1, false);
            white.setPixel(0, 0, 0xffffffff);
            client.getTextureManager().register(WHITE, new DynamicTexture(WHITE::toString, white));
            mount = RenderPearlWorldModels.mount(client.level, model, Vec3.ZERO, texture -> WHITE);
        }
        Vector3fc forward = camera.forwardVector();
        Vec3 position = camera.position().add(forward.x() * 1.2, forward.y() * 1.2, forward.z() * 1.2);
        double distance = lighting == Lighting.OCCLUDED ? 1.9 : .7;
        Vec3 emitter = camera.position().add(forward.x() * distance, forward.y() * distance, forward.z() * distance);
        Matrix4f orientation = new Matrix4f().rotate(camera.rotation());
        if (lighting.ordinal() >= Lighting.SCENE_LIT.ordinal()) {
            if (scenes == null) {
                mount.close();
                scenes = new SceneLightingSmoke(client.level, model, WHITE);
            }
            scenes.update(lighting, position, orientation, emitter);
            return;
        }
        mount.setPlacement(position, orientation);
        LightSource.Builder source = switch (lighting) {
            case POINT -> LightSource.point(emitter.x, emitter.y, emitter.z).range(2).intensity(160);
            case AREA -> LightSource.area(emitter.x, emitter.y, emitter.z, .7f, .7f)
                    .range(2).intensity(180).twoSided(true);
            case VOLUME, OCCLUDED -> LightSource.volumetric(emitter.x, emitter.y, emitter.z)
                    .range(.35f).intensity(260).medium(4, 0);
            default -> null;
        };
        if (source != null) {
            LightSource frame = source.color(LightColor.srgb(1, 0, 0)).build();
            if (light == null) light = WorldLights.mount(client.level, frame);
            else light.update(frame);
        }
    }

    private static void positionCamera(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) throw new IllegalStateException("Smoke test requires an integrated server");
        UUID id = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer observer = server.getPlayerList().getPlayer(id);
            if (observer == null) throw new IllegalStateException("Smoke observer is missing from the integrated server");
            observer.getAbilities().flying = true;
            observer.onUpdateAbilities();
            if (!observer.teleportTo(server.overworld(), .5, 240, .5, Set.of(), 0, 0, true)) {
                throw new IllegalStateException("Could not move the smoke observer above the terrain");
            }
        });
        cameraRequested = true;
    }

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) throws Exception {
        if (modelsVerified) {
            Minecraft client = Minecraft.getInstance();
            if (ModelInstancingSmoke.needsFrames()) ModelInstancingSmoke.afterFrame(client);
            else if (MmdModelSmoke.needsFrames()) MmdModelSmoke.afterFrame(client);
            else if (PostProcessGraphSmoke.needsFrames()) PostProcessGraphSmoke.afterFrame(client);
            else if (BlackHoleSmoke.needsFrames()) BlackHoleSmoke.afterFrame(client);
            else if (ParticleSmoke.needsFrames()) ParticleSmoke.afterFrame(client);
            else if (WaterSmoke.needsFrames()) WaterSmoke.afterFrame(client);
            else if (CloudSmoke.needsFrames()) CloudSmoke.afterFrame(client);
            else if (FrameOutputSmoke.started()) FrameOutputSmoke.afterFrame(client);
            else if (WorldUiSmoke.started()) WorldUiSmoke.afterFrame(client);
            else if (StructurePreviewSmoke.started()) StructurePreviewSmoke.afterFrame(client);
            else if (GuiPreviewSmoke.started()) GuiPreviewSmoke.afterFrame(client);
            else DocumentSmoke.afterFrame(client);
            return;
        }
        if (!EXPECTED_BACKEND.isEmpty() && cameraRequested && mount == null) {
            FsmClientSmoke.afterFrame(Minecraft.getInstance());
        }
        if (mount == null || capturing || ++frames < 12) return;
        capturing = true;
        Minecraft client = Minecraft.getInstance();
        Lighting captured = lighting;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                String name = captured == Lighting.BASELINE ? "kasuga-renderpearl-smoke.png"
                        : "kasuga-renderpearl-light-" + captured.name().toLowerCase(Locale.ROOT) + ".png";
                Path path = client.gameDirectory.toPath().resolve(name);
                image.writeToFile(path);
                int matching = 0;
                for (int y = image.getHeight() / 3; y < image.getHeight() * 2 / 3; y++) {
                    for (int x = image.getWidth() / 3; x < image.getWidth() * 2 / 3; x++) {
                        int color = image.getPixel(x, y);
                        int red = color >>> 16 & 255;
                        int green = color >>> 8 & 255;
                        int blue = color & 255;
                        if (green > 90 && blue > 90 && red * 4 < green && red * 4 < blue) matching++;
                    }
                }
                double red = centerRed(image);
                LOGGER.info("KASUGA_LIGHT_CAPTURE phase={} centerRed={} file={}", captured, red, path);
                if (captured == Lighting.BASELINE) {
                    if (matching < 100) throw new IllegalStateException("Model pixels missing from " + path);
                    baselineRed = red;
                    modelPixels = matching;
                } else if (captured == Lighting.EMBEDDED_TEXTURE) {
                    if (!blueTextureAtCenter(image)) {
                        throw new IllegalStateException("Embedded Blockbench texture pixels missing from " + path);
                    }
                } else if (captured == Lighting.OBJ_MATERIAL) {
                    if (!objCutoutAtCenter(image)) {
                        throw new IllegalStateException("OBJ diffuse texture or its cutout is missing from " + path);
                    }
                } else if (captured == Lighting.JAVA_JSON) {
                    if (nativeTexture(image, false).count() < 500 || nativeTexture(image, true).count() < 500) {
                        throw new IllegalStateException("Java Edition model did not render both inherited face textures in " + path);
                    }
                } else if (captured == Lighting.BEDROCK_JSON || captured == Lighting.BEDROCK_POSED) {
                    PixelSample sample = nativeTexture(image, false);
                    if (sample.count() < 500) throw new IllegalStateException("Bedrock geometry is missing from " + path);
                    if (captured == Lighting.BEDROCK_JSON) {
                        if (!objCutoutAtCenter(image)) throw new IllegalStateException("Bedrock texture cutout failed in " + path);
                        bedrockRestX = sample.centerX();
                    } else if (Math.abs(sample.centerX() - bedrockRestX) < 30) {
                        throw new IllegalStateException("Bedrock bone did not move the rendered geometry in " + path);
                    }
                } else if (!captured.lit) {
                    if (Math.abs(red - baselineRed) > 8 || matching < 100) {
                        throw new IllegalStateException(captured + " changed model colors at " + path);
                    }
                } else if (red < baselineRed + 15) {
                    throw new IllegalStateException(captured + " did not illuminate the model at " + path);
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save RenderPearl smoke capture", failure);
            }
            client.execute(() -> {
                if (captured == Lighting.BEDROCK_POSED) {
                    LOGGER.info("KASUGA_RENDERPEARL_PASS backend={} modelPixels={}",
                            RenderSystem.getDevice().getDeviceInfo().backendName(), modelPixels);
                    LOGGER.info("KASUGA_WORLD_LIGHTS_PASS point,area,volume,occluded,closed");
                    LOGGER.info("KASUGA_SCENE_LIGHTS_PASS nested,independent-move,hidden,restored,closed");
                    LOGGER.info("KASUGA_BLOCKBENCH_LIGHT_PASS native-locator,bone-move,restored,closed");
                    LOGGER.info("KASUGA_BLOCKBENCH_TEXTURE_PASS embedded-png");
                    LOGGER.info("KASUGA_OBJ_PASS resource-pack,sibling-mtl,namespaced-diffuse,cutout");
                    LOGGER.info("KASUGA_NATIVE_JSON_PASS java-parent,face-textures,implicit-uv,bedrock-selection,locator,cutout,bone-pose");
                    scenes.close();
                    model.close();
                    client.getTextureManager().release(WHITE);
                    modelsVerified = true;
                    return;
                }
                lighting = Lighting.values()[captured.ordinal() + 1];
                if (lighting == Lighting.CLEARED) {
                    light.close();
                    light = null;
                }
                frames = 0;
                capturing = false;
            });
        });
    }

    private static PixelSample nativeTexture(NativeImage image, boolean greenTexture) {
        int count = 0;
        long total = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = image.getWidth() / 8; x < image.getWidth() * 7 / 8; x++) {
                int pixel = image.getPixel(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                boolean matches = greenTexture ? green > 80 && red * 2 < green && blue * 5 < green * 4
                        : red > 100 && blue > 55 && green * 2 < red;
                if (!matches) continue;
                count++;
                total += x;
            }
        }
        return new PixelSample(count, count == 0 ? 0 : (double) total / count);
    }

    private record PixelSample(int count, double centerX) {}

    private static boolean objCutoutAtCenter(NativeImage image) {
        int centerX = image.getWidth() / 2;
        int centerY = image.getHeight() / 2;
        int coloredLeft = 0;
        int coloredRight = 0;
        int clearLeft = 0;
        int clearRight = 0;
        for (int y = centerY - 24; y < centerY + 24; y++) {
            for (int x = centerX - 64; x < centerX + 64; x++) {
                int pixel = image.getPixel(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                if (red > 100 && blue > 55 && green * 2 < red) {
                    if (x < centerX) coloredLeft++;
                    else coloredRight++;
                }
                if (green > 90 && blue > 90 && red * 4 < green && red * 4 < blue) {
                    if (x < centerX) clearLeft++;
                    else clearRight++;
                }
            }
        }
        return (coloredLeft > 200 && clearRight > 200) || (coloredRight > 200 && clearLeft > 200);
    }

    private static double centerRed(NativeImage image) {
        int side = 24;
        int left = image.getWidth() / 2 + image.getWidth() / 30;
        int top = image.getHeight() / 2 - side / 2;
        int red = 0;
        for (int y = top; y < top + side; y++) {
            for (int x = left; x < left + side; x++) {
                red += image.getPixel(x, y) >>> 16 & 255;
            }
        }
        return (double) red / (side * side);
    }

    private static boolean blueTextureAtCenter(NativeImage image) {
        int centerX = image.getWidth() / 2;
        int centerY = image.getHeight() / 2;
        int bluePixels = 0;
        for (int y = centerY - 60; y < centerY + 60; y++) {
            for (int x = centerX - 48; x < centerX + 48; x++) {
                int pixel = image.getPixel(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                if (red > 20 && green > red + 10 && blue > green + 5) bluePixels++;
            }
        }
        return bluePixels > 300;
    }
}

package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
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
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RenderPearlClientSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXPECTED_BACKEND = System.getProperty("kasuga.graphicsSmoke", "");
    private static final Identifier SOURCE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "models/smoke/triangle.gltf");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "smoke/white");
    private static final long START = System.nanoTime();
    private static boolean createdWorld;
    private static boolean capturing;
    private static int frames;
    private static ModelInstance model;
    private static RenderPearlWorldModels.Mount mount;

    private RenderPearlClientSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (EXPECTED_BACKEND.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) {
            throw new IllegalStateException("Timed out waiting for the RenderPearl smoke world");
        }
        if (createdWorld || client.gui.overlay() != null || client.gui.screen() == null) return;
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
        if (EXPECTED_BACKEND.isEmpty() || capturing) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.gui.screen() != null || client.gui.overlay() != null) return;
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
        Camera camera = client.gameRenderer.mainCamera();
        Vector3fc forward = camera.forwardVector();
        Vec3 position = camera.position().add(forward.x() * 1.2, forward.y() * 1.2, forward.z() * 1.2);
        mount.setPlacement(position, new Matrix4f().rotate(camera.rotation()));
    }

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        if (mount == null || capturing || ++frames < 12) return;
        capturing = true;
        Minecraft client = Minecraft.getInstance();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path path = client.gameDirectory.toPath().resolve("kasuga-renderpearl-smoke.png");
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
                if (matching < 100) throw new IllegalStateException("Model pixels missing from " + path);
                LOGGER.info("KASUGA_RENDERPEARL_PASS backend={} modelPixels={} capture={}",
                        RenderSystem.getDevice().getDeviceInfo().backendName(), matching, path);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save RenderPearl smoke capture", failure);
            }
            client.execute(() -> {
                mount.close();
                model.close();
                client.getTextureManager().release(WHITE);
                client.stop();
            });
        });
    }
}

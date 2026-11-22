package test.kasuga.modelling.demo.nebula;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone, opt-in development client used by scripts/nebula-sim.py. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class NebulaOfflineRun {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testNebula");
    private static final int TIMEOUT = Integer.getInteger("kasuga.nebula.timeoutSeconds", 600);
    private static final Path OUTPUT = Path.of(System.getProperty("kasuga.nebula.output", "debug/nebula-sim"));
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static NebulaVerification verification;
    private static long started;
    private static boolean requestedWorld;
    private static boolean finished;

    private NebulaOfflineRun() {}

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) throws IOException {
        if (!ENABLED || finished) return;
        Minecraft client = Minecraft.getInstance();
        try {
            if (started == 0) started = System.nanoTime();
            if (!HeadlessClient.enabled()) throw new IllegalStateException("Nebula offline verification requires a headless client");
            if (System.nanoTime() - started > TIMEOUT * 1_000_000_000L) {
                throw new IllegalStateException("Nebula offline verification timed out with screen " + client.gui.screen());
            }
            if (verification == null) {
                if (client.gui.overlay() != null) return;
                if (client.level == null) {
                    if (!requestedWorld && client.gui.screen() instanceof TitleScreen) {
                        requestedWorld = true;
                        if (Boolean.getBoolean("kasuga.nebula.createWorld")) createWorld(client);
                        else {
                            String world = System.getProperty("kasuga.nebula.world");
                            if (world == null || !client.getLevelSource().levelExists(world)) {
                                throw new IllegalStateException("The isolated Nebula world is missing");
                            }
                            client.createWorldOpenFlows().openWorld(world, () -> {
                                throw new IllegalStateException("Could not open the isolated Nebula world " + world);
                            });
                        }
                    }
                    return;
                }
                if (client.player == null || client.gui.screen() != null) return;
                Files.createDirectories(OUTPUT);
                REPORT.put("backend", RenderSystem.getDevice().getDeviceInfo().backendName());
                REPORT.put("headlessDriver", HeadlessClient.backend());
                REPORT.put("playerUuid", client.player.getUUID().toString());
                verification = new NebulaVerification(OUTPUT, REPORT, Integer.getInteger("kasuga.nebula.recordFrames", 0),
                        Integer.getInteger("kasuga.nebula.captureFps", 30), TIMEOUT,
                        Integer.getInteger("kasuga.nebula.width", 0), Integer.getInteger("kasuga.nebula.height", 0));
            }
            verification.afterFrame(client);
            if (verification.needsFrames()) return;
            verification.close();
            verification = null;
            REPORT.put("passed", true);
            writeReport();
            finished = true;
            client.stop();
        } catch (IOException | RuntimeException failure) {
            REPORT.put("passed", false);
            REPORT.put("failure", failure.toString());
            try { cleanup(); } catch (IOException | RuntimeException error) { failure.addSuppressed(error); }
            try { writeReport(); } catch (IOException error) { failure.addSuppressed(error); }
            throw failure;
        }
    }

    private static void createWorld(Minecraft client) {
        String name = "nebula-sim-" + Long.toString(System.currentTimeMillis(), 36);
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        client.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(26_003L, true, false),
                WorldPresets::createNormalWorldDimensions, client.gui.screen());
    }

    private static void writeReport() throws IOException {
        Files.createDirectories(OUTPUT);
        Files.writeString(OUTPUT.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
    }

    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event) throws IOException { cleanup(); }

    private static void cleanup() throws IOException {
        if (verification == null) return;
        try { verification.close(); }
        finally { verification = null; }
    }
}

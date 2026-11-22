package test.kasuga.modelling.demo.nebula;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.scene.MinecraftStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Development-only flight through the shared Nebula scene. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class NebulaDemo {
    private static final Vec3 OBSERVER = new Vec3(.5, 128, .5);
    private static final ResourceKey<Level> STAGE = ResourceKey.create(Registries.DIMENSION, NebulaRenderer.id("nebula"));
    private static final NebulaSimulation SIMULATION = new NebulaSimulation(0x4e4542554c41L);
    private static final int CAPTURE_FPS = Integer.getInteger("kasuga.nebula.captureFps", 30);
    private static Session current;
    private static boolean autoHandled;
    private static boolean capturing;

    private NebulaDemo() {}

    @SubscribeEvent public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        NebulaRenderer.registerPipelines(event);
    }

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_nebula")
                .executes(context -> start() ? 1 : 0)
                .then(Commands.literal("stop").executes(context -> { stop(); return 1; }))
                .then(Commands.literal("warp").executes(context -> warp() ? 1 : 0)));
    }

    public static boolean start() {
        if (current != null) return true;
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        if (client.player == null || server == null || server.isPublished()) {
            if (client.player != null) client.player.sendSystemMessage(
                    Component.literal("Nebula Sim requires a private singleplayer world."));
            return false;
        }
        UUID playerId = client.player.getUUID();
        CompletableFuture<MinecraftStage> opening = server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("The Nebula observer left the world");
            MinecraftStage stage = MinecraftStage.open(player, STAGE, OBSERVER, 0, 0, ignored -> {});
            player.setGameMode(GameType.SPECTATOR);
            return stage;
        });
        current = new Session(server, opening, client.gui.hud.isHidden());
        return true;
    }

    public static void stop() {
        Session session = current;
        if (session == null) return;
        current = null;
        capturing = false;
        Minecraft client = Minecraft.getInstance();
        if (session.renderer != null) {
            session.renderer.close();
            if (client.gui.hud.isHidden() != session.previousHud) client.gui.hud.toggle();
        }
        if (client.gui.screen() instanceof NebulaScreen) client.gui.setScreen(null);
        session.opening.thenAccept(MinecraftStage::close);
    }

    public static boolean active() { return current != null && current.renderer != null; }
    public static boolean idle() { return current == null; }
    public static int drawCount() { return active() ? current.renderer.drawCount() : 0; }
    public static int triangleCount() { return active() ? current.renderer.triangles() : 0; }
    public static boolean warp() { return active() && SIMULATION.triggerWarp(); }
    public static void probe(int mode) { if (active()) current.renderer.probe(mode); }
    public static void capture(boolean enabled) {
        if (!active()) return;
        capturing = enabled;
        current.renderer.capturing(enabled);
    }

    static void orbit(double dx, double dy) { if (active()) current.renderer.orbit(dx, dy); }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (!autoHandled && Boolean.getBoolean("kasuga.nebula.auto") && client.level != null
                && client.gui.overlay() == null && client.gui.screen() == null) autoHandled = start();
        Session session = current;
        if (session == null) return;
        if (client.player == null || client.level == null || client.getSingleplayerServer() != session.server) {
            stop();
            return;
        }
        if (session.renderer == null) {
            if (session.opening.isCompletedExceptionally()) {
                session.opening.whenComplete((stage, failure) -> LogUtils.getLogger().error("Could not enter Nebula", failure));
                client.player.sendSystemMessage(Component.literal("Could not enter the Nebula stage."));
                stop();
                return;
            }
            if (++session.waitTicks > 400) {
                stop();
                return;
            }
            if (!session.opening.isDone() || !client.level.dimension().equals(STAGE)
                    || client.gui.screen() != null || client.gui.overlay() != null) return;
            session.level = client.level;
            SIMULATION.reset();
            session.renderer = new NebulaRenderer(session.level, SIMULATION);
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            client.gui.setScreen(new NebulaScreen());
        }
        if (client.level != session.level) {
            stop();
            return;
        }
        if (!client.isPaused() && !capturing) SIMULATION.tick();
    }

    @SubscribeEvent public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (active() && capturing) SIMULATION.captureFrame(CAPTURE_FPS);
    }

    @SubscribeEvent public static void onReload(ClientResourceReloadFinishEvent event) {
        if (active()) current.renderer.reload();
    }

    @SubscribeEvent public static void onShutdown(GameShuttingDownEvent event) { stop(); }

    private static final class Session {
        private final IntegratedServer server;
        private final CompletableFuture<MinecraftStage> opening;
        private final boolean previousHud;
        private ClientLevel level;
        private NebulaRenderer renderer;
        private int waitTicks;

        private Session(IntegratedServer server, CompletableFuture<MinecraftStage> opening, boolean previousHud) {
            this.server = server;
            this.opening = opening;
            this.previousHud = previousHud;
        }
    }
}

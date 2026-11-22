package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.OutputFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.sdl.SDLVideo;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class HeadlessClientSmoke {
    private static final String GRAPHICS = System.getProperty("kasuga.testHeadless", "");
    private static final long STARTED = System.nanoTime();
    private static boolean created;
    private static boolean pending;
    private static boolean sampled;
    private static long initialTick;
    private static int delivered;
    private static RuntimeException failure;
    private static FrameOutputRouter<GpuTextureView>.Registration output;
    private static CompletableFuture<Void> formatReadback;

    private HeadlessClientSmoke() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if (GRAPHICS.isEmpty()) return;
        if (failure != null) throw failure;
        if (System.nanoTime() - STARTED > 120_000_000_000L) throw new IllegalStateException("Headless client smoke timed out");
        Minecraft client = Minecraft.getInstance();
        if (client.gui.overlay() != null) return;
        if (!created && client.level == null && client.gui.screen() != null) {
            created = true;
            String world = "kasuga-headless-" + Long.toString(System.currentTimeMillis(), 36);
            LevelSettings options = new LevelSettings(world, GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel(world, options, new WorldOptions(26_003L, true, false),
                    WorldPresets::createNormalWorldDimensions, client.gui.screen());
            return;
        }
        if (client.level == null || client.player == null) return;
        if (output == null && client.gui.screen() == null) {
            checkPlatform(client);
            initialTick = client.level.getGameTime();
            client.gui.setScreen(new Screen(Component.literal("Headless frame smoke")) {
                @Override public boolean isPauseScreen() { return false; }
                @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float partialTick) {}
                @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float partialTick) {
                    graphics.fill(0, 0, width, height, 0xffb42c65);
                }
            });
            output = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, HeadlessClientSmoke::read);
            formatReadback = FrameOutputFormatSmoke.verify();
            return;
        }
        if (output == null || !formatReadback.isDone()) return;
        formatReadback.join();
        if (output.isClosed() || !sampled || delivered < 4 || client.level.getGameTime() <= initialTick) return;
        checkPlatform(client);
        output.close();
        LogUtils.getLogger().info("KASUGA_HEADLESS_PASS graphics={} driver={} hidden,unpresented,world-ticks,final-gui-readback,hdr-format-switch",
                GRAPHICS, SDLVideo.SDL_GetCurrentVideoDriver());
        client.stop();
    }

    private static void checkPlatform(Minecraft client) {
        long flags = SDLVideo.SDL_GetWindowFlags(client.getWindow().handle());
        String graphics = RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT);
        if (!HeadlessClient.enabled() || !graphics.contains(GRAPHICS) || (flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0
                || client.windowSurface().isAcquired() || HeadlessClient.backend().equals("offscreen")
                        && !"offscreen".equals(SDLVideo.SDL_GetCurrentVideoDriver())) {
            throw new IllegalStateException("The headless client selected an unexpected window or graphics configuration");
        }
    }

    private static void read(OutputFrame<GpuTextureView> frame) {
        delivered++;
        if (pending) return;
        pending = true;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Headless frame pixel",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 4);
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(frame.resource().texture(), buffer, 0, () -> {
                try (buffer; GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                    int red = mapped.data().get(0) & 255;
                    int green = mapped.data().get(1) & 255;
                    int blue = mapped.data().get(2) & 255;
                    if (red != 180 || green != 44 || blue != 101) {
                        throw new IllegalStateException("Headless framebuffer did not contain the composed GUI color");
                    }
                    sampled = true;
                } catch (RuntimeException error) {
                    failure = error;
                }
            }, 0, frame.width() / 2, frame.height() / 2, 1, 1);
        } catch (RuntimeException error) {
            buffer.close();
            failure = error;
        }
    }
}

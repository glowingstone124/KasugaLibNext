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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private static CompletableFuture<Void> offscreenChecks;

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
            ItemStack item = new ItemStack(Items.STICK);
            item.set(DataComponents.ITEM_MODEL, Identifier.parse("kasuga_lib:native_smoke_item"));
            client.gui.setScreen(new Screen(Component.literal("Headless frame smoke")) {
                @Override public boolean isPauseScreen() { return false; }
                @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float partialTick) {}
                @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float partialTick) {
                    graphics.fill(0, 0, width, height, 0xffb42c65);
                    graphics.item(item, width / 2 - 8, height / 2 - 8);
                }
            });
            output = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, HeadlessClientSmoke::read);
            offscreenChecks = CompletableFuture.allOf(FrameOutputFormatSmoke.verify(), IndexedDrawBatchSmoke.verify());
            return;
        }
        if (output == null || !offscreenChecks.isDone()) return;
        offscreenChecks.join();
        if (output.isClosed() || !sampled || delivered < 4 || client.level.getGameTime() <= initialTick) return;
        checkPlatform(client);
        output.close();
        LogUtils.getLogger().info("KASUGA_ITEM_MODEL_PASS resource-model,final-gui-readback");
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
        int size = Math.min(64, Math.min(frame.width(), frame.height()));
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Headless GUI item and backdrop",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size * size * 4);
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(frame.resource().texture(), buffer, 0, () -> {
                try (buffer; GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                    int backdrop = 0;
                    int item = 0;
                    for (int offset = 0; offset < size * size * 4; offset += 4) {
                        int red = mapped.data().get(offset) & 255;
                        int green = mapped.data().get(offset + 1) & 255;
                        int blue = mapped.data().get(offset + 2) & 255;
                        if (red == 180 && green == 44 && blue == 101) backdrop++;
                        if (red < 20 && green > 90 && blue > 90) item++;
                    }
                    if (backdrop < 16 || item < 16) {
                        throw new IllegalStateException("Missing composed GUI backdrop or resource-backed item: "
                                + backdrop + " backdrop pixels, " + item + " item pixels");
                    }
                    sampled = true;
                } catch (RuntimeException error) {
                    failure = error;
                }
            }, 0, (frame.width() - size) / 2, (frame.height() - size) / 2, size, size);
        } catch (RuntimeException error) {
            buffer.close();
            failure = error;
        }
    }
}

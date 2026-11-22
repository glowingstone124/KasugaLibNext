package lib.kasuga.rendering.output.mc;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.sdl.SDLHints;

import java.util.List;
import java.util.Optional;

/** Launch-time SDL policy for the normal Minecraft renderer without presentation. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class HeadlessClient {
    private static final String BACKEND = System.getProperty("kasuga.headless", "disabled");
    private static boolean initialized;

    private HeadlessClient() {}

    public static boolean enabled() { return !BACKEND.equals("disabled"); }
    public static String backend() { return BACKEND; }

    public static void initializePlatform() {
        if (!enabled() || BACKEND.equals("hidden")) return;
        if (!BACKEND.equals("offscreen")) throw new IllegalArgumentException("Unknown headless backend " + BACKEND);
        if (!SDLHints.SDL_SetHintWithPriority("SDL_VIDEO_DRIVER", "offscreen", SDLHints.SDL_HINT_OVERRIDE)) {
            throw new IllegalStateException("Could not select the SDL offscreen video driver");
        }
    }

    public static GpuSurface surface() { return new UnpresentedSurface(); }

    @SubscribeEvent public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (!enabled() || initialized) return;
        Minecraft.getInstance().options.pauseOnLostFocus = false;
        initialized = true;
        LogUtils.getLogger().info("Kasuga headless client is rendering without presentation ({})", BACKEND);
    }

    private static final class UnpresentedSurface implements GpuSurface {
        private Configuration configuration;

        @Override public void configure(Configuration config) { configuration = config; }
        @Override public Optional<Configuration> currentConfiguration() { return Optional.ofNullable(configuration); }
        @Override public List<PresentMode> supportedPresentModes() { return List.of(PresentMode.FIFO); }
        @Override public boolean isSuboptimal() { return false; }
        @Override public boolean isAcquired() { return false; }
        @Override public void acquireNextTexture() {}

        @Override public void blitFromTexture(CommandEncoder encoder, GpuTextureView source) {
            throw new IllegalStateException("The headless client has no presentation surface");
        }

        @Override public void present() { throw new IllegalStateException("The headless client has no presentation surface"); }
        @Override public void close() { configuration = null; }
    }
}

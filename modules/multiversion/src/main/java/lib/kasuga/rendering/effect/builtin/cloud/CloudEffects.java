package lib.kasuga.rendering.effect.builtin.cloud;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.cloud.SkyCloudField;
import lib.kasuga.rendering.cloud.SkyCloudSettings;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.EffectRenderPipeline;
import lib.kasuga.rendering.effect.PipelineRegistration;
import lib.kasuga.rendering.effect.WorldRenderPipelineRegistry;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Built-in placeable volumetric clouds on the Minecraft graphics device. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CloudEffects {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "effects/cumulonimbus");
    private static final Identifier SKY_ID = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "effects/sky_clouds");
    private static final CloudVolumeAtlas ATLAS = new CloudVolumeAtlas();
    private static final CloudVolumeRenderer RENDERER = new CloudVolumeRenderer(ATLAS);
    private static final SkyCloudRenderer SKY_RENDERER = new SkyCloudRenderer(ATLAS);
    private static EffectRenderPipeline<CumulonimbusCloud> pipeline;
    private static PipelineRegistration skyPipeline;
    private static volatile boolean enabled;
    private static volatile boolean skyEnabled = true;
    private static SkyCloudField sky = new SkyCloudField(SkyCloudSettings.DEFAULT);
    private static ClientLevel previousLevel;

    private CloudEffects() {}

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        CloudVolumeRenderer.registerPipelines(event);
        SkyCloudRenderer.registerPipelines(event);
    }

    public static synchronized EffectHandle<CumulonimbusCloud> spawn(CumulonimbusCloud cloud) {
        if (pipeline == null) {
            RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(ID, RenderPhase.POST_PROCESS).priority(500).build();
            pipeline = EffectRenderPipeline.register(ID, descriptor, DuplicatePolicy.FAIL, true, RENDERER);
        }
        return pipeline.spawn(cloud);
    }

    public static boolean isEnabled() { return enabled; }

    public static synchronized void setEnabled(boolean value) {
        if (value && skyPipeline == null) {
            RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(SKY_ID, RenderPhase.POST_PROCESS).priority(499).build();
            skyPipeline = WorldRenderPipelineRegistry.register(SKY_ID, descriptor, DuplicatePolicy.FAIL, SKY_RENDERER::render);
        }
        enabled = value;
    }

    public static boolean isSkyEnabled() { return skyEnabled; }
    public static void setSkyEnabled(boolean value) { skyEnabled = value; }
    public static synchronized SkyCloudField sky() { return sky; }

    @SubscribeEvent
    public static synchronized void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != previousLevel) {
            sky = new SkyCloudField(sky.settings());
            SKY_RENDERER.close();
            previousLevel = client.level;
        }
        if (enabled && skyEnabled && client.level != null && !client.isPaused()) sky.tick(.05);
        else if (!enabled || !skyEnabled) SKY_RENDERER.clearHistory();
    }

    public static void releaseView(String viewId) { SKY_RENDERER.releaseView(viewId); }

    public static synchronized void clear() {
        if (pipeline != null) pipeline.clear();
    }

    public static synchronized int visibleCount() {
        return !enabled || pipeline == null ? 0 : pipeline.lastVisibleCount();
    }

    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        shutdown();
        skyEnabled = true;
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) { shutdown(); }

    public static synchronized void shutdown() {
        enabled = false;
        if (pipeline != null) pipeline.close();
        pipeline = null;
        if (skyPipeline != null) skyPipeline.close();
        skyPipeline = null;
        SKY_RENDERER.close();
        RENDERER.close();
        ATLAS.close();
    }
}

package lib.kasuga.rendering.effect.builtin.cloud;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.EffectRenderPipeline;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Built-in placeable volumetric clouds on the Minecraft graphics device. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CloudEffects {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "effects/cumulonimbus");
    private static final CloudVolumeRenderer RENDERER = new CloudVolumeRenderer();
    private static EffectRenderPipeline<CumulonimbusCloud> pipeline;
    private static volatile boolean enabled;

    private CloudEffects() {}

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        CloudVolumeRenderer.registerPipelines(event);
    }

    public static synchronized EffectHandle<CumulonimbusCloud> spawn(CumulonimbusCloud cloud) {
        if (pipeline == null) {
            RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(ID, RenderPhase.POST_PROCESS).priority(500).build();
            pipeline = EffectRenderPipeline.register(ID, descriptor, DuplicatePolicy.FAIL, true, RENDERER);
        }
        return pipeline.spawn(cloud);
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        if (value) CloudVolumeAtlas.ready(); // Start preparing noise without blocking client rendering.
        enabled = value;
    }

    public static synchronized void clear() {
        if (pipeline != null) pipeline.clear();
    }

    public static synchronized int visibleCount() {
        return !enabled || pipeline == null ? 0 : pipeline.lastVisibleCount();
    }

    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) { shutdown(); }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) { shutdown(); }

    public static synchronized void shutdown() {
        enabled = false;
        if (pipeline != null) pipeline.close();
        pipeline = null;
        RENDERER.close();
    }
}

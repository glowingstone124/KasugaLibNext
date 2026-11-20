package lib.kasuga.rendering.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.commands.RenderPass;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Owner-scoped scheduling for callbacks in the native world render stages. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldRenderPipelineRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PipelineRegistryCore<RenderPhase, RegisteredPipeline> PIPELINES = new PipelineRegistryCore<>();
    private static final ConcurrentMap<RenderPhase, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private WorldRenderPipelineRegistry() {}

    public static PipelineRegistration register(Identifier owner, RenderPipelineDescriptor descriptor,
                                                DuplicatePolicy duplicates, WorldRenderPipeline pipeline) {
        Objects.requireNonNull(descriptor, "descriptor");
        PipelineRegistryCore.Registration registration = PIPELINES.register(owner, descriptor.id(), descriptor.phase(),
                descriptor.priority(), duplicates, new RegisteredPipeline(descriptor, Objects.requireNonNull(pipeline, "pipeline")));
        return new PipelineRegistration() {
            @Override public Identifier id() { return registration.id(); }
            @Override public Identifier owner() { return registration.owner(); }
            @Override public RenderPipelineDescriptor descriptor() { return descriptor; }
            @Override public boolean isActive() { return registration.isActive(); }
            @Override public void close() { registration.close(); }
        };
    }

    public static boolean isRegistered(Identifier id) { return PIPELINES.isRegistered(id); }

    public static int size() { return PIPELINES.size(); }

    @SubscribeEvent
    public static void afterSky(RenderLevelStageEvent.AfterSky event) {
        dispatch(RenderPhase.AFTER_SKY, event, null, null);
    }

    @SubscribeEvent
    public static void afterOpaqueBlocks(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        dispatch(RenderPhase.AFTER_OPAQUE_BLOCKS, event, event.getRenderPass(), null);
    }

    @SubscribeEvent
    public static void afterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        dispatch(RenderPhase.AFTER_OPAQUE_FEATURES, event, event.getRenderPass(), null);
    }

    @SubscribeEvent
    public static void afterTranslucentFeatures(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        dispatch(RenderPhase.AFTER_TRANSLUCENT_FEATURES, event, event.getRenderPass(), null);
    }

    @SubscribeEvent
    public static void afterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        dispatch(RenderPhase.AFTER_TRANSLUCENT_BLOCKS, event, event.getRenderPass(), null);
    }

    @SubscribeEvent
    public static void afterParticles(RenderLevelStageEvent.AfterTranslucentParticles event) {
        dispatch(RenderPhase.AFTER_PARTICLES, event, event.getRenderPass(), null);
    }

    @SubscribeEvent
    public static void afterWeather(RenderLevelStageEvent.AfterWeather event) {
        dispatch(RenderPhase.AFTER_WEATHER, event, event.getRenderPass(), null);
    }

    @SubscribeEvent
    public static void oitTranslucent(RenderLevelStageEvent.OitTranslucent event) {
        dispatch(RenderPhase.OIT_TRANSLUCENT, event, event.getRenderPass(), event.getOitStage());
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void afterLevel(RenderLevelStageEvent.AfterLevel event) {
        dispatch(RenderPhase.POST_PROCESS, event, null, null);
        PostProcessGraphRegistry.render(event);
        dispatch(RenderPhase.AFTER_LEVEL, event, null, null);
    }

    private static void dispatch(RenderPhase phase, RenderLevelStageEvent event, @Nullable RenderPass pass, @Nullable OitStage oitStage) {
        long version = PIPELINES.version();
        Snapshot snapshot = SNAPSHOTS.get(phase);
        if (snapshot == null || snapshot.version() != version) {
            snapshot = new Snapshot(version, PIPELINES.entries(phase));
            SNAPSHOTS.put(phase, snapshot);
        }
        for (PipelineRegistryCore.Entry<RenderPhase, RegisteredPipeline> entry : snapshot.pipelines()) {
            if (!PIPELINES.isCurrent(entry)) continue;
            RegisteredPipeline pipeline = entry.pipeline();
            PoseStack poses = event.getPoseStack();
            poses.pushPose();
            try {
                pipeline.callback().render(new WorldRenderPipelineContext(pipeline.descriptor(), event, pass, oitStage));
            } catch (RuntimeException exception) {
                LOGGER.error("World render pipeline {} owned by {} failed at {}", entry.id(), entry.owner(), phase, exception);
            } finally {
                poses.popPose();
            }
        }
    }

    private record RegisteredPipeline(RenderPipelineDescriptor descriptor, WorldRenderPipeline callback) {}
    private record Snapshot(long version, List<PipelineRegistryCore.Entry<RenderPhase, RegisteredPipeline>> pipelines) {}
}

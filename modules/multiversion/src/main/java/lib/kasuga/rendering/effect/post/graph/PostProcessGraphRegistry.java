package lib.kasuga.rendering.effect.post.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.effect.post.PostProcessTargetPool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Schedules owned post-processing graphs after level rendering and before world UI compositing. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class PostProcessGraphRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Identifier, Entry> GRAPHS = new HashMap<>();
    private static final List<PostProcessTargetPool> RETIRED = new ArrayList<>();
    private static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::priority).thenComparing(Entry::id);
    private static List<Entry> ordered = List.of();
    private static boolean executing;

    private PostProcessGraphRegistry() {}

    public static PostProcessGraphRegistration register(Identifier owner, int priority, DuplicatePolicy duplicates,
                                                        PostProcessGraph graph) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(duplicates, "duplicates");
        Entry previous = GRAPHS.get(graph.id());
        if (previous != null && duplicates == DuplicatePolicy.FAIL) {
            throw new IllegalStateException("Post-process graph " + graph.id() + " is already owned by " + previous.owner());
        }
        Entry entry = new Entry(owner, priority, graph, new PostProcessTargetPool());
        GRAPHS.put(graph.id(), entry);
        rebuildOrder();
        if (previous != null) retire(previous.pool());
        return entry;
    }

    public static boolean isRegistered(Identifier id) {
        RenderSystem.assertOnRenderThread();
        return GRAPHS.containsKey(id);
    }

    public static List<Identifier> registeredIds() {
        RenderSystem.assertOnRenderThread();
        return GRAPHS.keySet().stream().sorted().toList();
    }

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        PostProcessContext.registerPipelines(event);
    }

    @ApiStatus.Internal
    public static void render(RenderLevelStageEvent.AfterLevel event) {
        if (ordered.isEmpty()) return;
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        LevelRenderState level = event.getLevelRenderState();
        executing = true;
        try {
            for (Entry entry : ordered) {
                if (!entry.isActive()) continue;
                try {
                    entry.graph().execute(new PostProcessContext(target, entry.pool(), level.cameraRenderState, level.worldPartialTicks));
                } catch (RuntimeException exception) {
                    LOGGER.error("Post-process graph {} owned by {} failed", entry.id(), entry.owner(), exception);
                }
            }
        } finally {
            executing = false;
            for (PostProcessTargetPool pool : RETIRED) pool.close();
            RETIRED.clear();
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel)) return;
        for (Entry entry : ordered) retire(entry.pool());
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        for (Entry entry : ordered) retire(entry.pool());
        GRAPHS.clear();
        ordered = List.of();
    }

    private static void rebuildOrder() {
        List<Entry> entries = new ArrayList<>(GRAPHS.values());
        entries.sort(ORDER);
        ordered = List.copyOf(entries);
    }

    private static void retire(PostProcessTargetPool pool) {
        if (executing) RETIRED.add(pool);
        else pool.close();
    }

    private record Entry(Identifier owner, int priority, PostProcessGraph graph,
                         PostProcessTargetPool pool) implements PostProcessGraphRegistration {
        @Override public Identifier id() { return graph.id(); }
        @Override public boolean isActive() { return GRAPHS.get(id()) == this; }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (!isActive()) return;
            GRAPHS.remove(id());
            rebuildOrder();
            retire(pool);
        }
    }
}

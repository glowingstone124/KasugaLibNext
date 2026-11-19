package lib.kasuga.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.builtin.light.WorldLights;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Owns display subtrees and publishes their geometry and lights to the current client level. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SceneModels implements AutoCloseable {
    private static final Set<SceneModels> ACTIVE = new LinkedHashSet<>();

    private final ClientLevel level;
    private final List<Entry> entries = new ArrayList<>();
    private long displayTick;
    private boolean closed;

    private SceneModels(ClientLevel level) {
        this.level = level;
    }

    public static SceneModels open(ClientLevel level) {
        requireClientThread();
        if (level == null || Minecraft.getInstance().level != level) {
            throw new IllegalArgumentException("Scene models require the current client level");
        }
        SceneModels models = new SceneModels(level);
        ACTIVE.add(models);
        return models;
    }

    /** Takes ownership of the source. */
    public Entry add(MinecraftModelSource source, Vec3 position) {
        requireClientThread();
        if (closed) throw new IllegalStateException("Model scene is closed");
        Objects.requireNonNull(source, "source");
        for (Entry entry : entries) {
            if (entry.source == source) throw new IllegalArgumentException("Source is already owned by this scene");
        }
        Entry entry = new Entry(this, source, position);
        if (source instanceof WorldDisplayTarget display) display.claim(this);
        entries.add(entry);
        return entry;
    }

    public Entry add(MinecraftModelSource source, SceneHost host) {
        Objects.requireNonNull(host, "host");
        return add(source, Vec3.ZERO).follow(host);
    }

    public void remove(Entry entry) {
        requireClientThread();
        if (entries.contains(entry)) entry.close();
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().position();
        for (SceneModels scene : List.copyOf(ACTIVE)) {
            if (scene.level != event.getLevel()) continue;
            for (Entry entry : List.copyOf(scene.entries)) entry.extract(event, partialTick, camera);
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        for (SceneModels scene : List.copyOf(ACTIVE)) {
            if (client.level != scene.level) {
                scene.close();
                continue;
            }
            if (client.isPaused()) continue;
            long tick = ++scene.displayTick;
            for (Entry entry : List.copyOf(scene.entries)) {
                if (!entry.placement.isAlive()) entry.close();
                else if (entry.source instanceof WorldDisplayTarget display) display.tick(tick, .05);
            }
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        for (SceneModels scene : List.copyOf(ACTIVE)) {
            if (scene.level == event.getLevel()) scene.close();
        }
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        for (SceneModels scene : List.copyOf(ACTIVE)) scene.close();
    }

    public Optional<MinecraftModelSource.BlockHit> pick(Vec3 from, Vec3 to, float partialTick) {
        requireClientThread();
        MinecraftModelSource.BlockHit nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Entry entry : List.copyOf(entries)) {
            if (entry.closed || !entry.visible) continue;
            if (!entry.placement.isAlive()) {
                entry.close();
                continue;
            }
            ScenePose pose = entry.lastPose();
            MinecraftModelSource.Snapshot snapshot = entry.lastSnapshot;
            if (!entry.placement.followsHost() || pose == null) {
                pose = entry.placement.sample(partialTick);
                snapshot = pose == null ? null : entry.source.snapshot(entry.layout, partialTick);
            }
            if (pose == null || snapshot == null) continue;
            MinecraftModelSource.BlockHit hit = snapshot.pick(pose.toLocal(from), pose.toLocal(to)).orElse(null);
            if (hit == null) continue;
            Vec3 world = pose.toWorld(hit.location());
            double next = world.distanceToSqr(from);
            if (next < distance) {
                distance = next;
                nearest = new MinecraftModelSource.BlockHit(hit.position(), hit.state(), world, hit.face());
            }
        }
        return Optional.ofNullable(nearest);
    }

    @Override public void close() {
        requireClientThread();
        if (closed) return;
        closed = true;
        ACTIVE.remove(this);
        RuntimeException failure = null;
        for (Entry entry : List.copyOf(entries)) {
            try {
                entry.close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Model scenes are owned by the Minecraft client thread");
        }
    }

    public static final class Entry implements AutoCloseable {
        private final SceneModels owner;
        private final MinecraftModelSource source;
        private final ScenePlacement placement;
        private LayerLayout layout = LayerLayout.ASSEMBLED;
        private MinecraftModelSource.Snapshot lastSnapshot;
        private boolean visible = true;
        private boolean closed;

        private Entry(SceneModels owner, MinecraftModelSource source, Vec3 position) {
            this.owner = owner;
            this.source = source;
            this.placement = new ScenePlacement(position);
        }

        public Entry position(Vec3 value) {
            requireOpen();
            placement.position(value);
            return this;
        }

        public Entry transform(Matrix4fc value) {
            requireOpen();
            placement.transform(value);
            return this;
        }

        public Entry follow(SceneHost host) {
            requireOpen();
            placement.follow(host);
            lastSnapshot = null;
            return this;
        }

        public Entry layers(LayerLayout value) {
            requireOpen();
            layout = Objects.requireNonNull(value, "layout");
            return this;
        }

        public Entry visible(boolean value) {
            requireOpen();
            visible = value;
            return this;
        }

        public ScenePose lastPose() { return placement.lastFrame(); }
        public boolean isClosed() { return closed; }

        private void extract(ExtractLevelRenderStateEvent event, float partialTick, Vec3 camera) {
            if (closed) return;
            ScenePose pose = placement.capture(partialTick);
            if (pose == null) {
                close();
                return;
            }
            if (!visible) {
                lastSnapshot = null;
                return;
            }
            if (source instanceof WorldDisplayTarget display) {
                Matrix4d world = pose.worldTransform();
                for (WorldDisplayTarget.Light light : display.lights()) {
                    Matrix4d transform = new Matrix4d(world).mul(new Matrix4d(light.transform()));
                    WorldLights.addToFrame(event, new LightFrame(light.source(), transform));
                }
            }
            lastSnapshot = source.snapshot(layout, partialTick);
            if (lastSnapshot == null || closed) return;
            PoseStack stack = new PoseStack();
            stack.mulPose(pose.relativeTo(camera));
            int light = LightCoordsUtil.getLightCoords(owner.level, BlockPos.containing(pose.origin()));
            lastSnapshot.render(new MinecraftModelSource.Context(stack, event, partialTick, light));
        }

        private void requireOpen() {
            requireClientThread();
            if (closed) throw new IllegalStateException("Scene entry is closed");
        }

        @Override public void close() {
            requireClientThread();
            if (closed) return;
            closed = true;
            placement.expire();
            lastSnapshot = null;
            owner.entries.remove(this);
            source.close();
        }
    }
}

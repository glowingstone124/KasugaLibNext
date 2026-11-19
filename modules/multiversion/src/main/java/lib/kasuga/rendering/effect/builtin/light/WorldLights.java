package lib.kasuga.rendering.effect.builtin.light;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.lighting.LightSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Screen-space lighting in the current client level, through Minecraft's active graphics backend. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldLights {
    private static final ContextKey<List<LightFrame>> LIGHTS =
            new ContextKey<>(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "world_lights"));
    private static final Set<Mount> MOUNTED = new LinkedHashSet<>();
    private static WorldLightRenderer renderer;

    private WorldLights() {}

    public static Mount mount(ClientLevel level, LightSource source) {
        return mount(level, LightFrame.world(source));
    }

    public static Mount mount(ClientLevel level, LightFrame frame) {
        requireClientThread();
        if (level == null || Minecraft.getInstance().level != level) {
            throw new IllegalArgumentException("Lights must be mounted in the current client level");
        }
        Mount mount = new Mount(level, Objects.requireNonNull(frame, "frame"));
        MOUNTED.add(mount);
        return mount;
    }

    @SubscribeEvent
    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(WorldLightRenderer.PIPELINE);
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        CameraRenderState camera = event.getRenderState().cameraRenderState;
        List<LightFrame> visible = new ArrayList<>();
        for (Mount mount : MOUNTED) {
            if (mount.level != event.getLevel()) continue;
            LightFrame light = mount.frame;
            if (light.source().intensity() == 0) continue;
            Vector3d position = light.position();
            double radius = light.source().boundsRadius() * light.rangeScale();
            AABB bounds = new AABB(position.x - radius, position.y - radius, position.z - radius,
                    position.x + radius, position.y + radius, position.z + radius);
            if (camera.cullFrustum.isVisible(bounds)) visible.add(light);
        }
        event.getRenderState().setRenderData(LIGHTS, List.copyOf(visible));
    }

    @SubscribeEvent
    public static void draw(RenderLevelStageEvent.AfterLevel event) {
        List<LightFrame> lights = event.getLevelRenderState().getRenderData(LIGHTS);
        if (lights == null || lights.isEmpty()) return;
        if (renderer == null) renderer = new WorldLightRenderer();
        renderer.draw(event.getLevelRenderState().cameraRenderState, lights);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        MOUNTED.removeIf(mount -> {
            if (mount.level != level) return false;
            mount.closed = true;
            return true;
        });
        if (MOUNTED.isEmpty()) releaseRenderer();
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        for (Mount mount : MOUNTED) mount.closed = true;
        MOUNTED.clear();
        releaseRenderer();
    }

    private static void releaseRenderer() {
        WorldLightRenderer retired = renderer;
        renderer = null;
        if (retired != null) retired.close();
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("World lights are owned by the Minecraft client thread");
        }
    }

    public static final class Mount implements AutoCloseable {
        private final ClientLevel level;
        private LightFrame frame;
        private boolean closed;

        private Mount(ClientLevel level, LightFrame frame) {
            this.level = level;
            this.frame = frame;
        }

        public void update(LightSource source) {
            update(LightFrame.world(source));
        }

        public void update(LightFrame frame) {
            requireClientThread();
            if (closed) throw new IllegalStateException("World light mount is closed");
            this.frame = Objects.requireNonNull(frame, "frame");
        }

        @Override public void close() {
            requireClientThread();
            if (closed) return;
            closed = true;
            MOUNTED.remove(this);
        }
    }
}

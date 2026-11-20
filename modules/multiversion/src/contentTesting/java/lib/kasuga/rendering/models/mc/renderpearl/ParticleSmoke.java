package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.ParticleRenderPipeline;
import lib.kasuga.rendering.effect.particle.instance.ParticleInstanceMesh;
import lib.kasuga.rendering.effect.particle.instance.renderpearl.RenderPearlParticleBatchRenderer;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

final class ParticleSmoke {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "smoke/particles");
    private static final ParticleInstanceMesh MESH = new ParticleInstanceMesh(ParticleInstanceMesh.Topology.TRIANGLES,
            -.45f, -.4f, 0, .45f, -.4f, 0, 0, .45f, 0);
    private static ParticleRenderPipeline pipeline;
    private static boolean originalTransparency;
    private static Stage stage = Stage.CLASSIC;
    private static int frames;
    private static boolean started;
    private static boolean pending;

    private ParticleSmoke() {}

    static boolean needsFrames() { return stage != Stage.DONE; }

    static void afterFrame(Minecraft client) {
        if (!started) {
            originalTransparency = client.options.improvedTransparency().get();
            start(client, false);
        }
        if (pending || ++frames < 12) return;
        pending = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int pixels = 0;
            try (image) {
                for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                    for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                        int color = image.getPixel(x, y);
                        int red = color >>> 16 & 255;
                        int green = color >>> 8 & 255;
                        int blue = color & 255;
                        if (red > 180 && green < 75 && blue > 110) pixels++;
                    }
                }
            }
            int count = pixels;
            client.execute(() -> advance(client, count));
        });
    }

    private static void start(Minecraft client, boolean oit) {
        client.options.improvedTransparency().set(oit);
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 center = camera.position().add(new Vec3(camera.forwardVector()).scale(3));
        RenderPhase phase = oit ? RenderPhase.OIT_TRANSLUCENT : RenderPhase.AFTER_PARTICLES;
        pipeline = ParticleRenderPipeline.register(ID, RenderPipelineDescriptor.builder(ID, phase).build(),
                DuplicatePolicy.FAIL, new RenderPearlParticleBatchRenderer(MESH));
        pipeline.sortBackToFront(!oit);
        for (float x : new float[]{-.55f, .55f}) {
            Transform transform = new Transform().translate((float) center.x + x, (float) center.y, (float) center.z);
            pipeline.add(ParticleInstance.builder(transform).color(new Vector4f(1, .03f, .65f, .95f)).build());
        }
        started = true;
    }

    private static void advance(Minecraft client, int pixels) {
        switch (stage) {
            case CLASSIC, OIT -> {
                if (pixels < 200 || pipeline.lastVisibleCount() != 2 || pipeline.lastRenderNanos() <= 0) {
                    throw new IllegalStateException("Particle instancing failed in " + stage + ": " + pixels + " pixels");
                }
                pipeline.close();
                if (stage == Stage.CLASSIC) {
                    stage = Stage.OIT;
                    start(client, true);
                } else {
                    stage = Stage.REMOVED;
                    client.options.improvedTransparency().set(originalTransparency);
                }
            }
            case REMOVED -> {
                if (pixels != 0) throw new IllegalStateException("Closed particles persisted: " + pixels);
                stage = Stage.DONE;
                LogUtils.getLogger().info("KASUGA_PARTICLES_PASS instanced-mesh,classic,oit,close");
            }
            case DONE -> throw new IllegalStateException("Particle smoke already completed");
        }
        frames = 0;
        pending = false;
    }

    private enum Stage { CLASSIC, OIT, REMOVED, DONE }
}

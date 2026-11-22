package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime.ParticleView;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayScene;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.io.IOException;

/** Checks the authored particles through direct mounts, nested scenes and native transparency. */
final class ModelEmitterSmoke {
    private static final Identifier MODEL = Identifier.fromNamespaceAndPath(KasugaLib.MODID,
            "models/components/emitter_smoke.bbmodel");
    private static final double SHIFT = .7;
    private static Stage stage = Stage.MOUNTED;
    private static RenderPearlModelSource source;
    private static RenderPearlWorldModels.Mount mount;
    private static ModelParticleRuntime.EmitterModule emitter;
    private static SceneModels scene;
    private static WorldDisplayScene child;
    private static Vec3 origin;
    private static Quaternionf orientation;
    private static Vector3d right;
    private static Vector3d oldLocal;
    private static Vector3d oldWorld;
    private static boolean previousTransparency;
    private static boolean capturing;
    private static int frames;

    private ModelEmitterSmoke() {}

    static boolean needsFrames() { return stage != Stage.DONE; }

    static void afterFrame(Minecraft client) throws IOException {
        if (source == null) open(client);
        if (capturing) return;
        if (++frames > 240) throw new IllegalStateException("Timed out waiting for model emitters in " + stage);
        if (stage == Stage.MOVED) {
            Vector3d local = firstPosition(true);
            Vector3d world = firstPosition(false);
            if (local == null || world == null) throw new IllegalStateException("Moving the scene lost its particles");
            double localShift = local.sub(oldLocal).dot(right);
            double worldShift = world.sub(oldWorld).dot(right);
            if (Math.abs(localShift - SHIFT) > .02 || Math.abs(worldShift) > .15) {
                throw new IllegalStateException("Scene movement shifted local and world particles by " + localShift + " and " + worldShift);
            }
            capture(client);
        } else if (stage == Stage.NESTED) {
            if (emitter.particleCount() < 12) return;
            oldLocal = firstPosition(true);
            oldWorld = firstPosition(false);
            if (oldLocal == null || oldWorld == null) throw new IllegalStateException("Both emitter spaces must be present");
            child.transform(new Matrix4f().translation((float) SHIFT, 0, 0));
            stage = Stage.MOVED;
            frames = 0;
        } else if (stage == Stage.MOUNTED && emitter.particleCount() >= 12 || stage == Stage.CLOSED && frames >= 3) {
            capture(client);
        }
    }

    private static void open(Minecraft client) throws IOException {
        previousTransparency = client.options.improvedTransparency().get();
        client.options.improvedTransparency().set(false);
        Camera camera = client.gameRenderer.mainCamera();
        origin = camera.position().add(new Vec3(camera.forwardVector()).scale(3)).add(new Vec3(camera.upVector()).scale(-.65));
        orientation = new Quaternionf(camera.rotation());
        right = new Vector3d(camera.leftVector()).negate();
        source = RenderPearlModelSource.open(MODEL);
        emitter = ModelParticleRuntime.module(source.instance());
        if (emitter == null) throw new IllegalStateException("The Blockbench model did not install its emitters");
        mount = source.mount(client.level, origin.x, origin.y, origin.z);
        mount.setPlacement(origin, new Matrix4f().rotate(orientation));
    }

    private static Vector3d firstPosition(boolean local) {
        for (ParticleView particle : emitter.particles()) {
            if ((particle.color().y > .9f) == local) return particle.position();
        }
        return null;
    }

    private static void capture(Minecraft client) {
        capturing = true;
        Stage captured = stage;
        int visible = ModelParticleRuntime.lastVisibleCount();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int magenta = 0;
            int lime = 0;
            try (image) {
                for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                    for (int x = image.getWidth() / 5; x < image.getWidth() * 4 / 5; x++) {
                        int color = image.getPixel(x, y);
                        int red = color >>> 16 & 255;
                        int green = color >>> 8 & 255;
                        int blue = color & 255;
                        if (red > 170 && green < 85 && blue > 150) magenta++;
                        if (green > 185 && red < 130 && blue < 65) lime++;
                    }
                }
                image.writeToFile(client.gameDirectory.toPath().resolve("kasuga-model-emitters-" + captured.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save the model emitter framebuffer", failure);
            }
            int worldPixels = magenta;
            int localPixels = lime;
            client.execute(() -> accept(client, captured, visible, worldPixels, localPixels));
        });
    }

    private static void accept(Minecraft client, Stage captured, int visible, int magenta, int lime) {
        if (captured == Stage.CLOSED) {
            if (visible != 0 || magenta != 0 || lime != 0) {
                throw new IllegalStateException("Closed emitters remained in the framebuffer " + magenta + "/" + lime);
            }
            client.options.improvedTransparency().set(previousTransparency);
            stage = Stage.DONE;
            LogUtils.getLogger().info("KASUGA_MODEL_EMITTERS_PASS bbmodel,mount,scene,world-space,local-space,classic,oit,fallback,close");
            return;
        }
        if (visible < 12 || magenta < 20 || lime < 20) {
            throw new IllegalStateException("Expected both model emitters in " + captured + " with " + visible
                    + " billboards and " + magenta + "/" + lime + " pixels");
        }
        if (captured == Stage.MOUNTED) {
            mount.close();
            if (emitter.enabled() || emitter.particleCount() != 0) throw new IllegalStateException("The mount retained particles after close");
            scene = SceneModels.open(client.level);
            WorldDisplayScene root = new WorldDisplayScene();
            child = root.add(new WorldDisplayScene());
            child.add(source);
            scene.add(root, origin).transform(new Matrix4f().rotate(orientation));
            client.options.improvedTransparency().set(true);
            stage = Stage.NESTED;
        } else {
            scene.close();
            if (!source.isClosed() || !emitter.closed() || emitter.particleCount() != 0) {
                throw new IllegalStateException("The nested scene retained model emitter state");
            }
            stage = Stage.CLOSED;
        }
        capturing = false;
        frames = 0;
    }

    private enum Stage { MOUNTED, NESTED, MOVED, CLOSED, DONE }
}

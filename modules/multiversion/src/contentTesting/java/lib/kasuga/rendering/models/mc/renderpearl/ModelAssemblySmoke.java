package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.io.IOException;

final class ModelAssemblySmoke {
    private static final Identifier BODY = Identifier.parse("kasuga_lib:smoke/body");
    private static final Identifier COAT = Identifier.parse("kasuga_lib:smoke/coat");
    private static final Vector3d ORIGIN = new Vector3d(1_000_000, 0, -1_000_000);
    private static SceneModels scene;
    private static McModelAssemblies.Outfit outfit;
    private static ModelInstance beforeReload;
    private static int frames;
    private static boolean capturing;
    private static Phase phase = Phase.BODY;
    private static RuntimeException failure;

    private enum Phase { BODY, COAT, RELOADING, RELOADED, DONE }

    private ModelAssemblySmoke() {}

    static boolean needsFrames() { return phase != Phase.DONE; }

    static void afterFrame(Minecraft client) throws IOException {
        if (failure != null) throw failure;
        if (phase == Phase.RELOADING || capturing || client.gui.overlay() != null) return;
        if (outfit == null) {
            outfit = McModelAssemblies.open(BODY);
            try (McModelAssemblies.Outfit other = McModelAssemblies.open(BODY)) {
                require(outfit.instance() != other.instance() && outfit.instance().getModel() == other.instance().getModel(),
                        "Outfits did not share geometry with independent model instances");
            }
            scene = SceneModels.open(client.level);
            Camera camera = client.gameRenderer.mainCamera();
            Vec3 center = camera.position().add(new Vec3(camera.forwardVector()).scale(1.8));
            outfit.transform(new Matrix4f().rotate(camera.rotation()));
            scene.add(outfit, center);
        }
        if (++frames < 12) return;
        frames = 0;
        capturing = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Pixels pixels = sample(image);
                client.execute(() -> {
                    try { evaluate(client, pixels); }
                    catch (IOException | RuntimeException error) { failure = new IllegalStateException(error); }
                });
            }
        });
    }

    private static void evaluate(Minecraft client, Pixels pixels) throws IOException {
        LogUtils.getLogger().info("KASUGA_MODEL_ASSEMBLY_CAPTURE phase={} cyan={} magenta={}",
                phase, pixels.cyan(), pixels.magenta());
        switch (phase) {
            case BODY -> {
                require(pixels.cyan() > 5_000, "The assembly body was not visible");
                ModelInstance old = outfit.instance();
                require(!outfit.switchAssembly(Identifier.parse("kasuga_lib:smoke/missing")) && outfit.instance() == old,
                        "A missing outfit definition discarded the current instance");
                RuntimeException rejected = new IllegalArgumentException("rejected outfit");
                outfit.onInstanceChanged((previous, fresh) -> { throw rejected; });
                try {
                    outfit.switchAssembly(COAT);
                    throw new IllegalStateException("The failed instance callback did not abort the switch");
                } catch (IllegalArgumentException error) {
                    if (error != rejected) throw error;
                }
                require(outfit.instance() == old && !old.isClosed(), "Failed outfit switch retired the visible instance");
                outfit.onInstanceChanged(null);
                old.getSkeletonInstance().enableFloatingOrigin(ORIGIN);
                require(outfit.switchAssembly(COAT) && old.isClosed(), "Outfit switching did not retire the former body");
                checkOrigin();
                // Scene ownership supplies the world position. The floating origin probe just verifies state migration.
                outfit.instance().getSkeletonInstance().rebaseFloatingOrigin(new Vector3d());
                outfit.instance().getSkeletonInstance().transformRoot(new Transform());
                phase = Phase.COAT;
            }
            case COAT -> {
                require(pixels.cyan() < 1_000 && pixels.magenta() > 5_000, "The outfit did not replace the body with the coat");
                beforeReload = outfit.instance();
                phase = Phase.RELOADING;
                client.reloadResourcePacks().whenComplete((ignored, error) -> client.execute(() -> {
                    if (error != null) failure = new IllegalStateException("Could not reload the outfit resources", error);
                    else phase = Phase.RELOADED;
                }));
            }
            case RELOADED -> {
                require(beforeReload.isClosed() && outfit.instance() != beforeReload && pixels.magenta() > 5_000
                                && pixels.cyan() < 1_000,
                        "Outfit resource reload left stale geometry or textures");
                ModelInstance current = outfit.instance();
                scene.close();
                require(outfit.isClosed() && current.isClosed(), "Scene teardown did not close the outfit");
                phase = Phase.DONE;
                LogUtils.getLogger().info("KASUGA_MODEL_ASSEMBLY_PASS cache,hidden-regions,named-zip,switch,rollback,reload,close");
            }
            default -> throw new IllegalStateException("Unexpected assembly phase " + phase);
        }
        capturing = false;
    }

    private static void checkOrigin() {
        require(outfit.instance().getSkeletonInstance().isFloatingOriginEnabled()
                        && outfit.instance().getSkeletonInstance().getWorldOrigin().equals(ORIGIN),
                "Outfit switching did not migrate the floating origin");
    }

    private static Pixels sample(NativeImage image) {
        int cyan = 0;
        int magenta = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                int color = image.getPixel(x, y);
                int red = color >>> 16 & 255;
                int green = color >>> 8 & 255;
                int blue = color & 255;
                if (red < 20 && green > 100 && blue > 100) cyan++;
                if (red > 100 && green * 2 < red && blue > 60) magenta++;
            }
        }
        return new Pixels(cyan, magenta);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Pixels(int cyan, int magenta) {}
}

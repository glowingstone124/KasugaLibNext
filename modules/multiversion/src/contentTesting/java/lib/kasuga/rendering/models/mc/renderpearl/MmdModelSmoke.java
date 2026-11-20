package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.uml.dynamic.ModelPosing;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd.VpdPose;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd.VpdSampler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.Map;

/** Synthetic one-bone PMX and PMD quads with a half-transparent resource-pack texture. */
final class MmdModelSmoke {
    private static RenderPearlModelSource source;
    private static RenderPearlWorldModels.Mount mount;
    private static int stage;
    private static int frames;
    private static boolean capturing;
    private static boolean finished;
    private static double restCenter;
    private static RuntimeException failure;

    private MmdModelSmoke() {}

    static boolean needsFrames() { return !finished; }

    static void afterFrame(Minecraft client) throws IOException {
        if (failure != null) throw failure;
        if (capturing) return;
        if (source == null) {
            source = switch (stage) {
                case 2 -> RenderPearlModelSource.open(fixture("sample.pmd"));
                case 3 -> RenderPearlModelSource.open(fixture("bundle.zip"));
                case 4 -> KsgPmxLoader.openArchive(fixture("bundle.zip"), "Nested/SAMPLE.PMX");
                default -> RenderPearlModelSource.open(fixture("sample.pmx"));
            };
            if (stage >= 3 && Math.abs(source.bounds().maxY() - source.bounds().minY() - .75) > 1e-5) {
                throw new IllegalStateException("Archive model scale was not applied");
            }
            Camera camera = client.gameRenderer.mainCamera();
            Vec3 center = camera.position().add(new Vec3(camera.forwardVector()).scale(1.3));
            mount = source.mount(client.level, new Matrix4f().translation((float) center.x, (float) center.y, (float) center.z)
                    .rotate(camera.rotation()));
        }
        if (++frames < 8) return;
        frames = 0;
        capturing = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                double center = sample(image);
                client.execute(() -> {
                    try {
                        evaluate(center);
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                });
            } catch (RuntimeException error) {
                failure = error;
            }
        });
    }

    private static Identifier fixture(String name) {
        return Identifier.fromNamespaceAndPath("kasuga_lib", "models/smoke/mmd/" + name);
    }

    private static double sample(NativeImage image) {
        int count = 0;
        long sumX = 0;
        int height = image.getHeight();
        int width = image.getWidth();
        for (int y = height / 4; y < height * 3 / 4; y++) {
            for (int x = width / 8; x < width * 7 / 8; x++) {
                int color = image.getPixel(x, y);
                int red = color >>> 16 & 255;
                int green = color >>> 8 & 255;
                int blue = color & 255;
                if (red < 100 || green * 2 >= red || blue < 60) continue;
                count++;
                sumX += x;
            }
        }
        if (count < 1000 || count > width * height / 8) {
            throw new IllegalStateException("MMD diffuse or cutout geometry is missing, matching pixels " + count);
        }
        return (double) sumX / count;
    }

    private static void evaluate(double center) {
        if (stage == 0) {
            restCenter = center;
            VpdPose poses = new VpdPose("smoke", Map.of("root", new Transform().translate(.32f, 0, 0)), Map.of());
            new ModelPosing(source.instance()).pose(VpdSampler.INSTANCE.sample(poses, 0));
        } else {
            double delta = Math.abs(center - restCenter);
            if (stage == 1 && delta < 30) throw new IllegalStateException("MMD bone did not move the rendered mesh");
            if (stage >= 2 && delta > 3) throw new IllegalStateException("MMD model geometry differs from standalone PMX at stage " + stage);
            mount.close();
            source.close();
            source = null;
        }
        if (++stage == 5) {
            finished = true;
            LogUtils.getLogger().info("KASUGA_MMD_PASS pmx,pmd,zip,entry-selection,tga,diffuse,cutout,vpd-skinning");
        }
        capturing = false;
    }
}

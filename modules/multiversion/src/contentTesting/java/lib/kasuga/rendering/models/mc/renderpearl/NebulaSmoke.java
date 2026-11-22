package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import test.kasuga.modelling.demo.nebula.NebulaDemo;

import java.io.IOException;

final class NebulaSmoke {
    private enum Step { ENTER, SILHOUETTE, MATERIAL, VOLUME, RETURN, DONE }

    private static Step step = Step.ENTER;
    private static ResourceKey<Level> original;
    private static Vec3 position;
    private static boolean originalHud;
    private static boolean pending;
    private static int afterDraw;
    private static long started;

    private NebulaSmoke() {}

    static boolean needsFrames() { return step != Step.DONE; }

    static void afterFrame(Minecraft client) {
        if (original == null) {
            original = client.level.dimension();
            position = client.player.position();
            originalHud = client.gui.hud.isHidden();
            started = System.nanoTime();
            if (!NebulaDemo.start()) throw new IllegalStateException("Nebula command did not start");
        }
        if (System.nanoTime() - started > 45_000_000_000L) {
            throw new IllegalStateException("Nebula smoke timed out at " + step + ", position "
                    + (client.player == null ? null : client.player.position()) + ", expected return " + position);
        }
        if (step == Step.ENTER && NebulaDemo.active()) {
            NebulaDemo.capture(true);
            moveTo(Step.SILHOUETTE, 1);
        }
        if (step == Step.RETURN) {
            if (!NebulaDemo.idle() || client.level == null || client.player == null
                    || !client.level.dimension().equals(original) || client.gui.screen() != null
                    || client.gui.overlay() != null) return;
            // The dimension-change packet may arrive a frame ahead of the position update.
            if (client.player.position().distanceToSqr(position) > 4) return;
            if (client.gui.hud.isHidden() != originalHud) throw new IllegalStateException("Nebula did not restore the viewer HUD");
            step = Step.DONE;
            LogUtils.getLogger().info("KASUGA_NEBULA_PASS obj-pbr,mrt,reverse-z,volume,warp,return");
            return;
        }
        if (step == Step.ENTER || pending || NebulaDemo.drawCount() < afterDraw) return;
        pending = true;
        Step sampled = step;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), pixels -> {
            int nonBlack;
            int bright;
            int colored;
            try (pixels) {
                nonBlack = count(pixels, 3, false);
                bright = count(pixels, 245, false);
                colored = count(pixels, 10, true);
                if (sampled == Step.VOLUME) {
                    pixels.writeToFile(client.gameDirectory.toPath().resolve("kasuga-nebula-flight.png"));
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save Nebula capture", failure);
            }
            client.execute(() -> validate(sampled, nonBlack, bright, colored));
        });
    }

    private static void validate(Step sampled, int nonBlack, int bright, int colored) {
        LogUtils.getLogger().info("KASUGA_NEBULA_CAPTURE phase={} lit={} bright={} color={}",
                sampled, nonBlack, bright, colored);
        switch (sampled) {
            case SILHOUETTE -> {
                if (NebulaDemo.triangleCount() < 6000 || bright < 500 || bright != nonBlack) {
                    throw new IllegalStateException("Nebula G-buffer depth did not produce a starship silhouette");
                }
                moveTo(Step.MATERIAL, 2);
            }
            case MATERIAL -> {
                if (nonBlack < 500 || colored < 300) throw new IllegalStateException("Nebula hull PBR shading is missing");
                if (!NebulaDemo.warp()) throw new IllegalStateException("Nebula warp FSM did not accept a trigger");
                moveTo(Step.VOLUME, 0);
            }
            case VOLUME -> {
                if (nonBlack < 5000 || colored < 2000) throw new IllegalStateException("Nebula volume produced no visible output");
                NebulaDemo.stop();
                step = Step.RETURN;
            }
            default -> throw new IllegalStateException("Unexpected Nebula sample " + sampled);
        }
    }

    private static void moveTo(Step next, int debug) {
        pending = false;
        step = next;
        NebulaDemo.probe(debug);
        afterDraw = NebulaDemo.drawCount() + 3;
    }

    private static int count(NativeImage image, int threshold, boolean color) {
        int count = 0;
        for (int y = image.getHeight() / 12; y < image.getHeight() * 11 / 12; y++) {
            for (int x = image.getWidth() / 12; x < image.getWidth() * 11 / 12; x++) {
                int pixel = image.getPixel(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                int max = Math.max(red, Math.max(green, blue));
                int min = Math.min(red, Math.min(green, blue));
                if (max >= threshold && (!color || max - min >= threshold)) count++;
            }
        }
        return count;
    }
}

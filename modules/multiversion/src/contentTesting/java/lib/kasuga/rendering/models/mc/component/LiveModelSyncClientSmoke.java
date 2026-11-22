package lib.kasuga.rendering.models.mc.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsWriteTransaction;
import lib.kasuga.core.vfs.backend.DirectoryVfs;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.ClientCommandHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Drives the current Blockbench preview files through the client commands and native frame output. */
public final class LiveModelSyncClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testLiveModels");
    private static final Identifier FIXTURE = Identifier.parse("kasuga_lib:models/components/emitter_smoke.bbmodel");
    private static final Identifier MODEL = Identifier.parse("kasuga_live:models/editor_smoke.bbmodel");
    private static int step;
    private static long started;
    private static long beforeReload;
    private static boolean readback;
    private static JsonObject model;
    private static RenderPearlModelSource previous;
    private static ModelParticleRuntime.EmitterModule retiredEmitter;

    private LiveModelSyncClientSmoke() {}

    public static boolean needsFrames() { return ENABLED && step < 4; }

    public static void afterFrame(Minecraft client) throws IOException {
        if (step == 0) {
            if (Files.exists(LiveModelSync.directory())) {
                throw new IllegalStateException("Run the live model smoke in a fresh client directory");
            }
            try (InputStreamReader source = new InputStreamReader(client.getResourceManager().open(FIXTURE), StandardCharsets.UTF_8)) {
                model = JsonParser.parseReader(source).getAsJsonObject();
            }
            publish("initial");
            beforeReload = LiveModelSync.completedReloads();
            command("start");
            if (!LiveModelSync.enabled()) throw new IllegalStateException("The live command did not enable editor reloads");
            started = System.nanoTime();
            step = 1;
        }
        if (System.nanoTime() - started > 45_000_000_000L) {
            throw new IllegalStateException("Timed out waiting for live model stage " + step);
        }
        if (client.gui.overlay() != null) return;
        if (step == 1 && LiveModelSync.completedReloads() > beforeReload) {
            JsonObject metadata = JsonParser.parseString(Files.readString(LiveModelSync.directory().resolve("pack.mcmeta")))
                    .getAsJsonObject().getAsJsonObject("pack");
            if (!metadata.has("min_format") || !metadata.has("max_format") || metadata.get("pack_format").getAsInt() != 34
                    || metadata.get("fixture").getAsInt() != 17) {
                throw new IllegalStateException("Legacy editor pack metadata was lost during the update");
            }
            command("preview " + MODEL);
            previous = LiveModelPreview.currentModel();
            if (previous == null) throw new IllegalStateException("The preview command did not mount a resource model");
            retiredEmitter = ModelParticleRuntime.module(previous.instance());
            step = 2;
        }
        if (readback) return;
        if (step == 2 && retiredEmitter != null && retiredEmitter.particleCount() >= 10) capture(client, false);
        if (step == 3 && LiveModelSync.completedReloads() > beforeReload) {
            RenderPearlModelSource current = LiveModelPreview.currentModel();
            if (current == null || current == previous || !previous.isClosed() || !previous.instance().isClosed()
                    || !retiredEmitter.closed() || retiredEmitter.particleCount() != 0) {
                throw new IllegalStateException("Resource reload did not replace and retire the mounted preview");
            }
            ModelParticleRuntime.EmitterModule particles = ModelParticleRuntime.module(current.instance());
            if (particles != null && particles.particleCount() >= 10) capture(client, true);
        }
    }

    private static void capture(Minecraft client, boolean revised) {
        readback = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int matched;
            try (image) {
                matched = pixels(image, revised);
                Path directory = client.gameDirectory.toPath().resolve("debug/live-model");
                Files.createDirectories(directory);
                image.writeToFile(directory.resolve(revised ? "reloaded.png" : "initial.png"));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save the live model verification frame", failure);
            }
            client.execute(() -> validate(revised, matched));
        });
    }

    private static void validate(boolean revised, int pixels) {
        if (pixels < 20) throw new IllegalStateException("Rendered preview did not contain the expected particle color");
        if (revised) {
            RenderPearlModelSource replacement = LiveModelPreview.currentModel();
            command("preview close");
            command("stop");
            if (LiveModelPreview.currentModel() != null || LiveModelSync.enabled() || !replacement.isClosed()) {
                throw new IllegalStateException("Closing the preview and sync left client resources attached");
            }
            // Later framebuffer probes sample the same region covered by the command messages.
            Minecraft.getInstance().gui.hud.getChat().clearMessages(false);
            step = 4;
            LogUtils.getLogger().info("KASUGA_LIVE_MODEL_PASS legacy-pack,marker,command,resource-reload,new-pixels,retired-model,close");
        } else {
            model.getAsJsonObject("kasuga_lib").getAsJsonArray("emitters").get(0).getAsJsonObject()
                    .getAsJsonObject("particle").addProperty("color", "#00ffff");
            beforeReload = LiveModelSync.completedReloads();
            try { publish("changed"); }
            catch (IOException failure) { throw new IllegalStateException("Could not publish the revised editor model", failure); }
            step = 3;
        }
        readback = false;
    }

    private static int pixels(NativeImage frame, boolean cyan) {
        int count = 0;
        for (int y = frame.getHeight() / 4; y < frame.getHeight() * 3 / 4; y++) {
            for (int x = frame.getWidth() / 4; x < frame.getWidth() * 3 / 4; x++) {
                int color = frame.getPixel(x, y);
                int red = color >>> 16 & 255;
                int green = color >>> 8 & 255;
                int blue = color & 255;
                if (blue > 155 && (cyan ? red < 65 && green > 170 : red > 170 && green < 95)) count++;
            }
        }
        return count;
    }

    private static void publish(String generation) throws IOException {
        Path directory = LiveModelSync.directory();
        Files.createDirectories(directory);
        try (DirectoryVfs pack = new DirectoryVfs("test-editor", directory);
             VfsWriteTransaction write = pack.beginTransaction()) {
            if (generation.equals("initial")) {
                write.putString(VfsPath.of("pack.mcmeta"),
                        "{\"pack\":{\"pack_format\":34,\"description\":\"Kasuga Blockbench live preview\",\"fixture\":17}}");
                write.putString(VfsPath.of("assets/kasuga_live/models/model_proxy.json"),
                        "{\"kasuga_live\":[\"models/editor_smoke.bbmodel\"]}");
            }
            write.putString(VfsPath.of("assets/kasuga_live/models/editor_smoke.bbmodel"), model.toString());
            write.putString(VfsPath.of(".kasuga-sync.json"), "{\"version\":1,\"resource\":\"" + MODEL
                    + "\",\"generation\":\"" + generation + "\"}");
            write.commit();
        }
    }

    private static void command(String arguments) {
        if (!ClientCommandHandler.runCommand("kasuga_models live " + arguments)) {
            throw new IllegalStateException("Could not dispatch the live model command " + arguments);
        }
    }
}

package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import lib.kasuga.scene.SceneControls;
import lib.kasuga.scene.SceneOverlay;
import lib.kasuga.scene.SceneWarmup;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class SceneClientSmoke {
    private static SceneWarmup warmup;
    private static SceneOverlay overlay;
    private static SceneOverlay.Fade fade;
    private static SceneOverlay.Prompt prompt;
    private static SceneControls controls;
    private static ServerLevel serverLevel;
    private static BlockPos marker;
    private static boolean previousHud;
    private static boolean blackFrame;
    private static boolean terrainReady;
    private static boolean capturePending;
    private static boolean done;
    private static int frames;
    private static long started;

    private SceneClientSmoke() {}

    static boolean needsFrames() { return !done; }

    static void afterFrame(Minecraft client) {
        if (warmup == null) {
            start(client);
            return;
        }
        if (System.nanoTime() - started > 30_000_000_000L) {
            throw new IllegalStateException("Scene presentation timed out at " + warmup.waitReason());
        }
        if (!terrainReady) terrainReady = warmup.ready();
        if (capturePending || ++frames < 4) return;
        if (!blackFrame) capture(client, true);
        else if (terrainReady && !fade.visible()) capture(client, false);
        else if (terrainReady && fade.paused()) fade.resume();
    }

    private static void start(Minecraft client) {
        started = System.nanoTime();
        previousHud = client.gui.hud.isHidden();
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 forward = new Vec3(camera.forwardVector());
        marker = BlockPos.containing(camera.position().add(forward.scale(7)));
        serverLevel = client.getSingleplayerServer().getLevel(client.level.dimension());
        warmup = SceneWarmup.forArea(client.level, marker, marker).requireBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState());
        if (!client.level.getBlockState(marker).isAir() || warmup.ready() || !warmup.waitReason().startsWith("authored block ")) {
            throw new IllegalStateException("Scene warmup did not wait for its authored block");
        }
        client.getSingleplayerServer().execute(() -> serverLevel.setBlockAndUpdate(marker, Blocks.GOLD_BLOCK.defaultBlockState()));
        overlay = SceneOverlay.openGlobal().attachToCurrentLevel();
        prompt = overlay.prompt(camera.position().add(forward.scale(2)), "F", "Scene smoke").visible(true);
        overlay.track("Scene smoke", "Kasuga", "Regression", 8).seconds(4).visible(true);
        fade = overlay.fadeFromBlack(.2f).pause();
        verifyControls(client);
    }

    private static void verifyControls(Minecraft client) {
        AtomicInteger presses = new AtomicInteger();
        List<Integer> clicks = new ArrayList<>();
        List<Double> wheel = new ArrayList<>();
        controls = SceneControls.open().key(InputConstants.KEY_F, presses::incrementAndGet)
                .mouse(clicks::add).scroll(wheel::add);
        long window = client.getWindow().handle();
        KeyEvent sceneKey = new KeyEvent(InputConstants.KEY_F, 'f', 0);
        KeyEvent forwardKey = new KeyEvent(InputConstants.KEY_W, 'w', 0);
        client.keyboardHandler.keyPress(window, InputConstants.PRESS, sceneKey);
        client.keyboardHandler.keyPress(window, InputConstants.REPEAT, sceneKey);
        client.keyboardHandler.keyPress(window, InputConstants.RELEASE, sceneKey);
        client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(InputConstants.KEY_F1, 0, 0));
        client.keyboardHandler.keyPress(window, InputConstants.PRESS, forwardKey);
        boolean moved = client.options.keyUp.isDown();
        client.keyboardHandler.keyPress(window, InputConstants.RELEASE, forwardKey);
        MouseButtonInfo right = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0);
        client.mouseHandler.onButton(window, right, InputConstants.PRESS);
        client.mouseHandler.onButton(window, right, InputConstants.RELEASE);
        client.mouseHandler.onScroll(window, 0, 1);
        if (presses.get() != 1 || !moved || !client.gui.hud.isHidden()
                || !clicks.equals(List.of(InputConstants.PRESS, InputConstants.RELEASE))
                || wheel.size() != 1 || wheel.getFirst() <= 0) {
            throw new IllegalStateException("Scene input did not isolate actions and preserve movement");
        }
    }

    private static void capture(Minecraft client, boolean black) {
        capturePending = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int matching = 0;
            try (image) {
                int width = image.getWidth();
                int height = image.getHeight();
                for (int y = height / 3; y < height * 2 / 3; y++) {
                    for (int x = width / 3; x < width * 2 / 3; x++) {
                        int rgb = image.getPixel(x, y) & 0xffffff;
                        if (black ? rgb != 0 : rgb == 0xf6faff) matching++;
                    }
                }
            }
            int pixels = matching;
            client.execute(() -> finishCapture(client, black, pixels));
        });
    }

    private static void finishCapture(Minecraft client, boolean black, int pixels) {
        capturePending = false;
        if (black) {
            if (pixels != 0 || !fade.paused()) throw new IllegalStateException("Scene fade did not hold black");
            blackFrame = true;
            return;
        }
        if (pixels < 10 || !UIBackend.get().containsRenderable(prompt)) {
            throw new IllegalStateException("Scene world prompt was not drawn, matching pixels " + pixels);
        }
        controls.close();
        overlay.close();
        client.getSingleplayerServer().execute(() -> serverLevel.setBlockAndUpdate(marker, Blocks.AIR.defaultBlockState()));
        if (SceneControls.active() || SceneOverlay.activeCount() != 0 || UIBackend.get().containsRenderable(prompt)
                || client.gui.hud.isHidden() != previousHud) {
            throw new IllegalStateException("Scene controls and overlay did not release their state");
        }
        done = true;
        LogUtils.getLogger().info("KASUGA_SCENE_CLIENT_PASS native-keys,mouse,scroll,warmup,black-frame,prompt,cleanup");
    }
}

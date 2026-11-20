package lib.kasuga.rendering.output.mc.stream;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.mc.CameraRenderScope;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;

/** Exercises the real integrated-server channel and samples the detached framebuffer. */
@EventBusSubscriber(Dist.CLIENT)
public final class RemoteCameraSmoke {
    private enum Step { START, WHITE, BLACK, SPAWN, MOVE, REMOVE, PAUSE, RESUME, CLOSE, DONE }

    private static Step step = Step.START;
    private static CameraHandle camera;
    private static WorldCameraView pose;
    private static ChunkPos chunk;
    private static long deadline;
    private static long nextProbe;
    private static volatile Step placed;
    private static boolean probing;
    private static boolean readPending;
    private static int pixel;
    private static int white;
    private static int black;
    private static ArmorStand fixture;
    private static volatile int entityId;
    private static ArmorStandRenderState extracted;

    private RemoteCameraSmoke() {}

    public static boolean verify(Minecraft client) throws Exception {
        if (step == Step.DONE) return true;
        if (step == Step.START) {
            IntegratedServer server = client.getSingleplayerServer();
            require(server != null, "Remote camera smoke requires the integrated server");
            nextProbe = 0;
            deadline = System.nanoTime() + 65_000_000_000L;
            ChunkPos player = client.player.chunkPosition();
            chunk = new ChunkPos(player.x() + 40, player.z());
            pose = new WorldCameraView(chunk.x() * 16 + 8.5, 251, chunk.z() * 16 + 8.5, 0, 90, 0, 60, 96, 72);
            CameraRenderSettings settings = new CameraRenderSettings(2, CameraRenderSettings.Quality.FAST,
                    CameraRenderSettings.Shader.disabled());
            camera = MinecraftCameras.createFree("kasuga:remote-camera", pose, settings, RemoteCameraSmoke::sample);
            advance(Step.WHITE);
        }
        require(System.nanoTime() < deadline, "Remote camera timed out in " + step + " at " + chunk
                + ", pixel=" + Integer.toHexString(pixel) + ", placed=" + placed + ", watches=" + CameraChunkServer.watchedChunks());
        require(camera.state() != CameraState.FAILED, "Remote camera failed: " + camera.failure().orElse(null));
        require(client.level.getChunkSource().getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, false) == null,
                "Remote chunk entered the main player cache");
        require(entityId == 0 || client.level.getEntity(entityId) == null, "Remote entity entered the main player cache");
        switch (step) {
            case WHITE -> {
                place(client, Blocks.CONCRETE.pick(DyeColor.WHITE));
                if (placed == step && pixel != 0 && gray(pixel) && brightness(pixel) > 120) {
                    white = pixel;
                    advance(Step.BLACK);
                }
            }
            case BLACK -> {
                place(client, Blocks.CONCRETE.pick(DyeColor.BLACK));
                if (placed == step && matchesBlack()) {
                    black = pixel;
                    advance(Step.SPAWN);
                }
            }
            case SPAWN -> {
                mutateEntity(client);
                if (extracted != null && Math.abs(extracted.x - pose.x()) < .15 && pixel != 0 && pixel != black && !matchesBlack()) {
                    advance(Step.MOVE);
                }
            }
            case MOVE -> {
                mutateEntity(client);
                if (extracted != null && Math.abs(extracted.x - (pose.x() + 2)) < .15
                        && extracted.headPose.equals(new Rotations(10, 20, 30))) advance(Step.REMOVE);
            }
            case REMOVE -> {
                mutateEntity(client);
                if (placed == step && extracted == null && matchesBlack()) {
                    camera.pause();
                    advance(Step.PAUSE);
                }
            }
            case PAUSE -> {
                if (CameraChunkServer.watchedChunks() == 0) {
                    camera.resume();
                    advance(Step.RESUME);
                }
            }
            case RESUME -> {
                if (matchesBlack() && CameraChunkServer.watchedChunks() > 0) {
                    camera.close();
                    advance(Step.CLOSE);
                }
            }
            case CLOSE -> {
                if (CameraChunkServer.watchedChunks() == 0) {
                    LogUtils.getLogger().info("KASUGA_REMOTE_CAMERA_PASS terrain-outside-player-cache,block-resend,entity-spawn,entity-move,entity-metadata,entity-remove,pause,resume,close {} {}",
                            Integer.toHexString(white), Integer.toHexString(black));
                    advance(Step.DONE);
                }
            }
            default -> { }
        }
        return step == Step.DONE;
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        if (!CameraRenderScope.currentViewId().equals("kasuga:remote-camera")) return;
        extracted = null;
        for (EntityRenderState state : event.getRenderState().entityRenderStates) {
            if (state instanceof ArmorStandRenderState stand) {
                extracted = stand;
                return;
            }
        }
    }

    private static void mutateEntity(Minecraft client) {
        if (placed == step || probing) return;
        Step phase = step;
        IntegratedServer server = client.getSingleplayerServer();
        probing = true;
        server.execute(() -> {
            switch (phase) {
                case SPAWN -> {
                    ServerLevel level = server.overworld();
                    fixture = new ArmorStand(level, pose.x(), 246, pose.z());
                    fixture.setNoGravity(true);
                    fixture.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                    require(level.addFreshEntity(fixture), "Could not spawn remote camera fixture");
                    entityId = fixture.getId();
                }
                case MOVE -> {
                    fixture.setPos(pose.x() + 2, 246, pose.z());
                    fixture.setHeadPose(new Rotations(10, 20, 30));
                }
                case REMOVE -> fixture.remove(Entity.RemovalReason.DISCARDED);
                default -> throw new IllegalStateException("Unexpected remote entity test phase " + phase);
            }
            placed = phase;
            client.execute(() -> probing = false);
        });
    }

    private static boolean matchesBlack() {
        return pixel != 0 && gray(pixel) && brightness(white) - brightness(pixel) > 80;
    }

    private static int brightness(int color) {
        return ((color & 255) + (color >>> 8 & 255) + (color >>> 16 & 255)) / 3;
    }

    private static boolean gray(int color) {
        int red = color & 255;
        int green = color >>> 8 & 255;
        int blue = color >>> 16 & 255;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) < 15;
    }

    private static void place(Minecraft client, Block block) {
        long now = System.nanoTime();
        if (placed == step || probing || now < nextProbe) return;
        IntegratedServer server = client.getSingleplayerServer();
        Step phase = step;
        probing = true;
        nextProbe = now + 100_000_000L;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        level.setBlock(new BlockPos(chunk.x() * 16 + 8 + x, 245, chunk.z() * 16 + 8 + z), block.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                placed = phase;
            }
            client.execute(() -> probing = false);
        });
    }

    private static void advance(Step next) {
        step = next;
        pixel = 0;
    }

    private static void sample(OutputFrame<GpuTextureView> frame) {
        if (readPending || frame.frameNumber() % 5 != 0) return;
        Step phase = step;
        TextureTarget target = new TextureTarget("Remote camera smoke", frame.width(), frame.height(), GpuFormat.RGBA8_UNORM, null);
        try {
            PostProcessContext.copyColor(frame.resource(), target.getColorTextureView(), false);
            readPending = true;
            Screenshot.takeScreenshot(target, image -> {
                try (image) {
                    if (step == phase) pixel = image.getPixel(frame.width() / 2, frame.height() / 2);
                } finally {
                    readPending = false;
                }
            });
        } finally {
            target.destroyBuffers();
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}

package lib.kasuga.host;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmBlock;
import lib.kasuga.rendering.models.mc.dynamic.fsm.MachineQuery;
import lib.kasuga.rendering.models.mc.dynamic.fsm.cap.AnimationCapabilities;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public final class FsmClientSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockPos POSITION = new BlockPos(0, 241, 3);
    private static final Identifier PANEL = Identifier.parse("kasuga_lib:fsm_smoke_panel");
    private static final Identifier MODEL = Identifier.parse("kasuga_lib:models/smoke/animated_triangle.gltf");
    private static final Id MACHINE = Id.parse("kasuga_lib:fsm_smoke");
    private static final Id CLIP = Id.parse("kasuga_lib:fsm_smoke_move");
    private static final StateVar<Boolean> ADVANCE = StateVar.trigger(Id.parse("kasuga_lib:fsm_smoke/advance"));
    private static Phase phase = Phase.REST;
    private static boolean capturing;
    private static boolean assetsReloaded;
    private static RuntimeException failure;
    private static int frames;
    private static double restX;
    private static double movedX;

    private FsmClientSmoke() {}

    static void initializeWorld(ServerLevel world) {
        Block block = BuiltInRegistries.BLOCK.getValue(PANEL);
        if (!(block instanceof FsmBlock)) {
            throw new IllegalStateException("Data-driven FSM block did not register");
        }
        BlockState state = block.defaultBlockState();
        world.setBlockAndUpdate(POSITION, state);
        if (!(world.getBlockEntity(POSITION) instanceof AnimationBlockEntity host)
                || !MACHINE.equals(host.stateMachineId())) {
            throw new IllegalStateException("Data-driven FSM block entity did not initialize");
        }
        CompoundTag stored = host.saveWithFullMetadata(world.registryAccess());
        if (!MODEL.toString().equals(stored.getStringOr("modelLoc", ""))
                || !(BlockEntity.loadStatic(POSITION, state, stored, world.registryAccess()) instanceof AnimationBlockEntity restored)
                || !MACHINE.equals(restored.stateMachineId())) {
            throw new IllegalStateException("FSM block entity configuration did not round-trip through world storage");
        }
    }

    public static boolean ready() {
        if (failure != null) throw failure;
        return phase == Phase.COMPLETE;
    }

    public static void afterFrame(Minecraft client) {
        if (phase == Phase.COMPLETE || capturing || client.level == null || client.gui.overlay() != null
                || client.player == null || !client.player.getAbilities().flying) return;
        BlockEntity blockEntity = client.level.getBlockEntity(POSITION);
        if (phase == Phase.REMOVED) {
            if (blockEntity != null) return;
        } else {
            if (!(blockEntity instanceof AnimationBlockEntity host) || !host.isModelBound()) return;
            if (client.level.getCapability(AnimationCapabilities.MACHINE_BLOCK, POSITION, null) != host) {
                throw new IllegalStateException("FSM block capability did not expose its host");
            }
            StateMachine<?> machine = MachineQuery.machineAt(client.level, POSITION);
            if (machine == null || machine.logicEnabled()) return;
            if (phase == Phase.REST && !"rest".equals(machine.activeStateId("panel"))) return;
            if (phase != Phase.REST && (!"moved".equals(machine.activeStateId("panel"))
                    || machine.layerElapsedTicks("panel") < 10)) return;
            if (phase == Phase.RELOADED) {
                if (!assetsReloaded) return;
                if (FsmRegistries.GLOBAL.definitions().get(MACHINE) == null || FsmRegistries.GLOBAL.clips().get(CLIP) == null) {
                    throw new IllegalStateException("Client resource reload discarded the active server FSM data");
                }
            }
        }
        if (++frames < 12) return;
        frames = 0;
        capturing = true;
        Phase captured = phase;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path file = client.gameDirectory.toPath()
                        .resolve("kasuga-fsm-" + captured.name().toLowerCase(Locale.ROOT) + ".png");
                image.writeToFile(file);
                PixelSample sample = cyanModelPixels(image);
                client.execute(() -> {
                    try {
                        evaluate(client, captured, sample, file);
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                });
            } catch (IOException error) {
                throw new IllegalStateException("Could not save the FSM smoke capture", error);
            }
        });
    }

    private static void evaluate(Minecraft client, Phase captured, PixelSample sample, Path file) {
        LOGGER.info("KASUGA_FSM_CAPTURE phase={} pixels={} centroidX={} file={}", captured, sample.count(), sample.centerX(), file);
        if (captured != Phase.REMOVED && sample.count() < 80) {
            throw new IllegalStateException("Animated model did not render in " + file);
        }
        IntegratedServer server = client.getSingleplayerServer();
        switch (captured) {
            case REST -> {
                restX = sample.centerX();
                server.execute(() -> {
                    StateMachine<?> machine = MachineQuery.machineAt(server.overworld(), POSITION);
                    if (machine == null || !machine.logicEnabled()) {
                        throw new IllegalStateException("Missing authoritative FSM on the integrated server");
                    }
                    machine.trigger(ADVANCE);
                });
                phase = Phase.MOVED;
            }
            case MOVED -> {
                movedX = sample.centerX();
                if (Math.abs(movedX - restX) < 30) {
                    throw new IllegalStateException("Animated bone did not move the model pixels");
                }
                phase = Phase.RELOADED;
                client.reloadResourcePacks().thenRun(() -> client.execute(() -> assetsReloaded = true));
            }
            case RELOADED -> {
                if (Math.abs(sample.centerX() - movedX) > 5) {
                    throw new IllegalStateException("Resource reload lost the FSM pose");
                }
                server.execute(() -> server.overworld().removeBlock(POSITION, false));
                phase = Phase.REMOVED;
            }
            case REMOVED -> {
                if (sample.count() != 0) throw new IllegalStateException("Removed FSM host still rendered its model");
                phase = Phase.COMPLETE;
                LOGGER.info("KASUGA_FSM_PASS data-pack,external-gltf-buffer,persistence,capability,server-transition,pose,client-reload,removed");
            }
            case COMPLETE -> throw new IllegalStateException("FSM smoke captured after completion");
        }
        capturing = false;
    }

    private static PixelSample cyanModelPixels(NativeImage image) {
        int count = 0;
        long xTotal = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixel(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                if (green > 70 && blue > 70 && red * 4 < green && red * 4 < blue) {
                    count++;
                    xTotal += x;
                }
            }
        }
        return new PixelSample(count, count == 0 ? 0 : (double) xTotal / count);
    }

    private enum Phase { REST, MOVED, RELOADED, REMOVED, COMPLETE }

    private record PixelSample(int count, double centerX) {}
}

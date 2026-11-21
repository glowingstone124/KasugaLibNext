package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.scene.MinecraftStage;
import lib.kasuga.scene.SceneGround;
import lib.kasuga.scene.SceneProps;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class StageSmoke {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(KasugaLib.MODID, "smoke_stage"));
    private static final BlockPos CHEST = new BlockPos(0, 80, 0);
    private static final BlockPos FLOOR = CHEST.east();
    private static CompletableFuture<Void> stageCheck;
    private static SceneProps props;
    private static SceneProps.Prop falling;
    private static double startY;
    private static int frames;
    private static boolean done;

    private StageSmoke() {}

    static boolean needsFrames() { return !done; }

    static void afterFrame(Minecraft client) {
        if (stageCheck == null) {
            IntegratedServer server = client.getSingleplayerServer();
            UUID playerId = client.player.getUUID();
            stageCheck = server.submit(() -> checkStage(server.getPlayerList().getPlayer(playerId), server.getLevel(DIMENSION)));
            return;
        }
        if (!stageCheck.isDone()) return;
        stageCheck.join();
        if (client.level == null || client.level.dimension().equals(DIMENSION)) return;
        if (props == null) {
            Camera camera = client.gameRenderer.mainCamera();
            Vec3 center = camera.position().add(new Vec3(camera.forwardVector()).scale(2));
            props = SceneProps.open(client.level);
            props.block(Blocks.DIAMOND_BLOCK.defaultBlockState(), center.add(-.4, 0, 0)).scale(.35f, .35f, .35f);
            falling = props.physicalItem(new ItemStack(Items.APPLE), center.add(.4, 0, 0), new Vector3f(.15f), 1);
            startY = falling.position().y;
            return;
        }
        if (++frames < 12) return;
        if (startY - falling.position().y < .03 && frames < 120) return;
        if (startY - falling.position().y < .03) throw new IllegalStateException("Scene prop did not simulate");
        props.detachPhysics(falling);
        double detachedY = falling.position().y;
        if (!Double.isFinite(detachedY)) throw new IllegalStateException("Scene prop did not preserve its detached pose");
        props.close();
        if (SceneProps.activeScenes() != 0) throw new IllegalStateException("Scene props were not released");
        done = true;
        LogUtils.getLogger().info("KASUGA_STAGE_PASS saved-return,block-entity-restore,block-prop,physical-item,close");
    }

    private static void checkStage(ServerPlayer player, ServerLevel stageLevel) {
        if (stageLevel == null) throw new IllegalStateException("Stage smoke dimension is missing");
        Level original = player.level();
        Vec3 origin = player.position();
        GameType originalMode = player.gameMode();
        stageLevel.setBlock(CHEST, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        ChestBlockEntity chest = (ChestBlockEntity) stageLevel.getBlockEntity(CHEST);
        if (chest == null) throw new IllegalStateException("Stage chest was not created");
        chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
        try (MinecraftStage stage = MinecraftStage.open(player, DIMENSION, Vec3.atBottomCenterOf(FLOOR.above()),
                0, 0, session -> {
                    session.block(CHEST, Blocks.STONE.defaultBlockState());
                    session.block(FLOOR, SceneGround.stateForLayers(7));
                })) {
            if (!stage.contains(player) || player.gameMode() != GameType.ADVENTURE
                    || !stage.level().getBlockState(CHEST).is(Blocks.STONE)
                    || stage.level().getBlockState(FLOOR).getValue(SceneGround.HEIGHT) != 7) {
                throw new IllegalStateException("Stage did not apply its ground and temporary blocks");
            }
        }
        if (player.level() != original || player.position().distanceToSqr(origin) > 1e-6
                || player.gameMode() != originalMode || player.getPersistentData().contains("kasuga_lib:stage_return")
                || !(stageLevel.getBlockEntity(CHEST) instanceof ChestBlockEntity restored)
                || !restored.getItem(0).is(Items.GOLD_INGOT) || restored.getItem(0).getCount() != 3
                || !stageLevel.getBlockState(FLOOR).isAir()) {
            throw new IllegalStateException("Stage close did not restore the player and block entity");
        }
        stageLevel.setBlock(CHEST, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }
}

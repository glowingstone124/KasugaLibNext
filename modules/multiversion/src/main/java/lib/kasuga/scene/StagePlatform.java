package lib.kasuga.scene;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

final class StagePlatform {
    private StagePlatform() {}

    static boolean teleport(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch) {
        return player.teleportTo(level, position.x, position.y, position.z, Set.of(), yaw, pitch, true);
    }

    static void restoreBlockEntity(ServerLevel level, BlockEntity block, CompoundTag data) {
        block.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
    }
}

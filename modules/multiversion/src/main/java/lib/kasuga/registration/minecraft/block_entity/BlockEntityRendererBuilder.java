package lib.kasuga.registration.minecraft.block_entity;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;

@FunctionalInterface
public interface BlockEntityRendererBuilder<T extends BlockEntity> {
    BlockEntityRenderer<T, ? extends BlockEntityRenderState> build(BlockEntityRendererProvider.Context context);
}

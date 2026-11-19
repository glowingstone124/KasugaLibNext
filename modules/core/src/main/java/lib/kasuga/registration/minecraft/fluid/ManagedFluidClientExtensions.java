package lib.kasuga.registration.minecraft.fluid;

import net.minecraft.resources.ResourceLocation;

final class ManagedFluidClientExtensions implements net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions {
    private final FluidTypeReg<?> reg;

    ManagedFluidClientExtensions(FluidTypeReg<?> reg) {
        this.reg = reg;
    }

    @Override
    public ResourceLocation getStillTexture() {
        ResourceLocation still = reg.getStillTexturePath();
        return still;
    }

    @Override
    public ResourceLocation getFlowingTexture() {
        ResourceLocation flowing = reg.getFlowingTexturePath();
        return flowing;
    }

    @Override
    public ResourceLocation getOverlayTexture() {
        ResourceLocation overlay = reg.getOverlayTexturePath();
        return overlay;
    }

    @Override
    public int getTintColor() {
        return reg.getTintColor();
    }

    @Override
    public org.joml.Vector3f modifyFogColor(net.minecraft.client.Camera camera, float partialTick, net.minecraft.client.multiplayer.ClientLevel level, int renderDistance, float darkenWorldAmount, org.joml.Vector3f fluidFogColor) {
        float[] color = reg.getFogColor();
        return color == null ? fluidFogColor : new org.joml.Vector3f(color[0], color[1], color[2]);
    }

    @Override
    public void modifyFogRender(net.minecraft.client.Camera camera, net.minecraft.client.renderer.FogRenderer.FogMode mode, float renderDistance, float partialTick, float nearDistance, float farDistance, com.mojang.blaze3d.shaders.FogShape shape) {
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(reg.getFogStart());
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(reg.getFogEnd());
    }
}

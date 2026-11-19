package lib.kasuga.registration.minecraft.fluid;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

final class ManagedFluidClientExtensions implements IClientFluidTypeExtensions {
    private final FluidTypeReg<?> registration;

    ManagedFluidClientExtensions(FluidTypeReg<?> registration) {
        this.registration = registration;
    }

    @Override public void modifyFogColor(Camera camera, float partialTick, ClientLevel level, int renderDistance,
                                         float darkenWorldAmount, Vector4f fluidFogColor) {
        float[] color = registration.getFogColor();
        if (color != null) fluidFogColor.set(color[0], color[1], color[2], fluidFogColor.w());
    }

    @Override public void modifyFogRender(Camera camera, @Nullable FogEnvironment environment, float renderDistance,
                                          float partialTick, FogData fogData) {
        fogData.environmentalStart = registration.getFogStart();
        fogData.environmentalEnd = registration.getFogEnd();
    }
}

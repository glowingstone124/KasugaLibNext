package lib.kasuga.registration.minecraft.fluid;

import lib.kasuga.registration.stages.FluidModelStage;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.fluid.FluidTintSources;

public record FluidModelRegistration(RegisterFluidModelsEvent event) implements FluidModelStage {
    @Override public void register(FluidReg<?, ?> fluid) {
        FluidTypeReg<?> type = fluid.getFluidTypeReg();
        Identifier still = type.getStillTexturePath();
        Identifier flowing = type.getFlowingTexturePath();
        if (still == null || flowing == null) return;
        Identifier overlay = type.getOverlayTexturePath();
        FluidModel.Unbaked model = new FluidModel.Unbaked(
                new Material(still), new Material(flowing), overlay == null ? null : new Material(overlay),
                FluidTintSources.constant(type.getTintColor()));
        event.register(model, fluid.getSource(), fluid.getFlowing());
    }
}

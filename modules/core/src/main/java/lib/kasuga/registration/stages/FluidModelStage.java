package lib.kasuga.registration.stages;

import lib.kasuga.registration.minecraft.fluid.FluidReg;

@FunctionalInterface
public interface FluidModelStage {
    void register(FluidReg<?, ?> fluid);
}

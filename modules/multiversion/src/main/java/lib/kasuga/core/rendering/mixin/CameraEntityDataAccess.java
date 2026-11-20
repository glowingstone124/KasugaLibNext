package lib.kasuga.core.rendering.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SynchedEntityData.class)
public interface CameraEntityDataAccess {
    @Accessor("itemsById") SynchedEntityData.DataItem<?>[] kasuga$itemsById();
}

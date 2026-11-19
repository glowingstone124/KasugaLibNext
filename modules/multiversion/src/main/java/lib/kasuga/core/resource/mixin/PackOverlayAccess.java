package lib.kasuga.core.resource.mixin;

import net.minecraft.server.packs.OverlayedPackResources;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(OverlayedPackResources.class)
public interface PackOverlayAccess {
    @Accessor("packResourcesStack")
    List<PackResources> kasuga$layers();
}

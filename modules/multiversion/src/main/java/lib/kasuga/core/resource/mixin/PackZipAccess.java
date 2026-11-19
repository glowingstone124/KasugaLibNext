package lib.kasuga.core.resource.mixin;

import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FilePackResources.class)
public interface PackZipAccess {
    @Accessor("zipFileAccess")
    FilePackResources.SharedZipFileAccess kasuga$archive();

    @Accessor("prefix")
    String kasuga$prefix();
}

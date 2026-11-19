package lib.kasuga.core.resource.mixin;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforge.resource.JarContentsPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(JarContentsPackResources.class)
public interface PackJarAccess {
    @Accessor("contents")
    JarContents kasuga$contents();

    @Accessor("prefix")
    String kasuga$prefix();
}

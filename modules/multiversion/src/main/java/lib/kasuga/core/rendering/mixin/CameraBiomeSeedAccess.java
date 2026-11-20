package lib.kasuga.core.rendering.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BiomeManager.class)
public interface CameraBiomeSeedAccess {
    @Accessor("biomeZoomSeed") long kasuga$biomeZoomSeed();
}

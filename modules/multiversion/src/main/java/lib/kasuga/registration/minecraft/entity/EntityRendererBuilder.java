package lib.kasuga.registration.minecraft.entity;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;

@FunctionalInterface
public interface EntityRendererBuilder<T extends Entity> {
    EntityRenderer<T, ?> build(EntityRendererProvider.Context context);
}

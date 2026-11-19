package template.lib.kasuga.registration.minecraft.entity;

import lib.kasuga.KasugaLib;
import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.internal.generator.facades.RegFacade;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.beans.rendering.RenderingRegistry;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.minecraft.entity.EntityRendererBuilder;
import lib.kasuga.registration.stages.RegistrationStage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

@CodeTemplate(generator = "Reg")
@RegGenerator(
        modifiers = {
                @RegGenerator.Modifier(
                        type = "EntityTypes",
                        target = Collection.class,
                        extendedType = "Collection<EntityType<?>>"
                )
        }
)
public class EntityRendererReg<E extends Entity> extends Reg<EntityRendererReg<E>, Void> {

    private final Supplier<EntityRendererBuilder<E>> provider;

    public EntityRendererReg(Supplier<EntityRendererBuilder<E>> provider) {
        super();
        this.provider = provider;
    }

    @Override
    public void register(RegisterContext<?> context) {
        super.register(context);
        context.onStage(RegistrationStage.REGISTER_RENDERERS, (ctx)->{
            Collection<EntityType<?>> validEntities = RegFacade.transformObject("EntityTypes", new HashSet<>());
            for (EntityType<?> validEntity : validEntities) {
                //noinspection unchecked
                KasugaLib.getBean(RenderingRegistry.class)
                        .registerEntityRenderer((EntityType<E>) validEntity, provider);
            }
        });
    }

    @Override
    public Void getEntry() {
        throw new IllegalStateException("EntityRendererReg does not have an entry");
    }

    public void block() {}

    @RegGenerator.ModifyFunction(type = "EntityTypes")
    public Collection<EntityType<?>> withEntity(Collection<EntityType<?>> originalValue, Supplier<EntityType<?>> entitySupplier) {
        originalValue.add(entitySupplier.get());
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "EntityTypes")
    public Collection<EntityType<?>> withEntities(Collection<EntityType<?>> originalValue, BiPredicate<ResourceLocation, EntityType<?>> predicate) {
        LinkedList<EntityType<?>> matchedList = new LinkedList<>();
        for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
            if (predicate.test(entry.getKey().location(), entry.getValue())) {
                matchedList.add(entry.getValue());
            }
        }
        originalValue.addAll(matchedList);
        return originalValue;
    }
}

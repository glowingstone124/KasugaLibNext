package template.lib.kasuga.registration.minecraft.entity;

import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.internal.generator.facades.RegFacade;
import lib.kasuga.registration.core.Modifier;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import lib.kasuga.registration.minecraft.common.RegistrationPlatform;
import lib.kasuga.registration.minecraft.entity.EntityRendererBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Entity is a basic element of minecraft. We use it to create mobs, minecarts and other movable creatures.
 * See {@link Entity} for more info.
 * @param <T> the class of your entity.
 */
@CodeTemplate(generator = "Reg")
@RegGenerator(
        modifiers = {
                @RegGenerator.Modifier(
                        type = "EntityBuilder",
                        target = EntityType.Builder.class,
                        extendedType = "EntityType.Builder<?>"
                ),
                @RegGenerator.Modifier(
                        type = "Width",
                        target = Float.class
                ),
                @RegGenerator.Modifier(
                        type = "Height",
                        target = Float.class
                ),
                @RegGenerator.Modifier(
                        type = "Category",
                        target = MobCategory.class
                ),
                @RegGenerator.Modifier(
                        type = "Attributes",
                        target = AttributeSupplier.Builder.class
                )
        }
)
public final class EntityReg<T extends Entity> extends MinecraftDeferRegistryReg<EntityReg<T>, EntityType<?>, EntityType<T>> {

    private final BiFunction<EntityType<?>, Level, T> entityFactory;

    public static <T extends Entity> EntityReg<T> of(String name, BiFunction<EntityType<?>, Level, T> factory) {
        return new EntityReg<>(name, factory);
    }

    public EntityReg(String name, BiFunction<EntityType<?>, Level, T> entityFactory) {
        super(name, Registries.ENTITY_TYPE);
        this.entityFactory = entityFactory;
    }

    @Override
    protected EntityType<T> createObject(ResourceLocation id) {
        MobCategory mobCategory = RegFacade.transformObject("Category", MobCategory.MISC);

        EntityType.Builder<T> builder = EntityType.Builder.of(entityFactory::apply, mobCategory);
        
        // Apply size modifier if present
        Float width = RegFacade.transformObject("Width", null);
        Float height = RegFacade.transformObject("Height", null);
        if (width != null && height != null) {
            builder = builder.sized(width, height);
        }

        //noinspection unchecked
        return (EntityType<T>) RegistrationPlatform.entityType(id, RegFacade.transformObject("EntityBuilder", builder));
    }

    @RegGenerator.ModifyFunction(type = "Width")
    public Float width(Float originalValue, float width) {
        return width;
    }

    @RegGenerator.ModifyFunction(type = "Height")
    public Float height(Float originalValue, float height) {
        return height;
    }

    @RegGenerator.ConfigureMember()
    public Object size(float width, float height) {
        RegFacade.callConfigure(this, "width", width);
        return RegFacade.callConfigure(this, "height", height);
    }

    @RegGenerator.ModifyFunction(type = "Category")
    public MobCategory category(MobCategory originalValue, MobCategory category) {
        return category;
    }

    @RegGenerator.ModifyFunction(type = "Category")
    public MobCategory category(MobCategory originalValue, Supplier<MobCategory> category) {
        return category.get();
    }

    @RegGenerator.ModifyFunction(type = "EntityBuilder")
    public EntityType.Builder<?> fireImmune(EntityType.Builder<?> originalValue) {
        originalValue.fireImmune();
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "EntityBuilder")
    public EntityType.Builder<?> noSave(EntityType.Builder<?> originalValue) {
        originalValue.noSave();
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "EntityBuilder")
    public EntityType.Builder<?> noSummon(EntityType.Builder<?> originalValue) {
        originalValue.noSummon();
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "EntityBuilder")
    public EntityType.Builder<?> clientTrackingRange(EntityType.Builder<?> originalValue, int range) {
        originalValue.clientTrackingRange(range);
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "EntityBuilder")
    public EntityType.Builder<?> updateInterval(EntityType.Builder<?> originalValue, int interval) {
        originalValue.updateInterval(interval);
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "EntityBuilder")
    public EntityType.Builder<?> canSpawnFarFromPlayer(EntityType.Builder<?> originalValue) {
        originalValue.canSpawnFarFromPlayer();
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "EntityBuilder")
    public EntityType.Builder<?> shouldReceiveVelocityUpdates(EntityType.Builder<?> originalValue, boolean receive) {
        originalValue.setShouldReceiveVelocityUpdates(receive);
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "Attributes")
    public AttributeSupplier.Builder withAttributeSupplier(AttributeSupplier.Builder originalValue, Supplier<Collection<Holder<Attribute>>> attributes) {
        for (Holder<Attribute> attr : attributes.get()) {
            originalValue = originalValue.add(attr);
        }
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "Attributes")
    public AttributeSupplier.Builder withAttributeBuilder(AttributeSupplier.Builder originalValue, Supplier<AttributeSupplier.Builder> attributeBuilder) {
        originalValue.combine(attributeBuilder.get());
        return originalValue;
    }
}

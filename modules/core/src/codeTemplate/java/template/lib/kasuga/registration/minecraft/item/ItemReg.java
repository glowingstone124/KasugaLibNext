package template.lib.kasuga.registration.minecraft.item;

import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.Prototype;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.internal.generator.facades.RegFacade;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import lib.kasuga.registration.minecraft.common.RegistrationPlatform;
import lib.kasuga.registration.stages.RegistrationStage;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.flag.FeatureFlag;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ItemLike;
import lib.kasuga.registration.core.CreativeTabModifiers;
import template.lib.kasuga.registration.minecraft.creative_tab.CreativeTabReg;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

@CodeTemplate(generator = "Reg")
@RegGenerator(
        modifiers = {
                @RegGenerator.Modifier(
                        type = "IsFoodModifier",
                        target = Boolean.class
                ),
                @RegGenerator.Modifier(
                        type = "ItemPropertyModifiers",
                        target = Item.Properties.class,
                        enumeration = {
                                "stacksTo",
                                "durability",
                                "craftRemainder",
                                "rarity",
                                "fireResistant",
                                "jukeboxPlayable",
                                "setNoRepair",
                                "requiredFeatures",
                                "attributes"
                        }
                ),
                @RegGenerator.Modifier(
                        type = "FoodPropertyModifiers",
                        target = FoodProperties.Builder.class,
                        enumeration = {
                                "nutrition",
                                "saturationModifier",
                                "alwaysEdible",
                                "fast",
                                "effect",
                                "usingConvertsTo"
                        },
                        prefix = "food"
                ),
                @RegGenerator.Modifier(
                        type = "TabsToModifiers",
                        target = ResourceLocation.class
                )
        }
)
public final class ItemReg<T extends Item> extends MinecraftDeferRegistryReg<ItemReg<T>, Item, T> {

    private final Function<ItemReg<T>, Function<Item.Properties, T>> supplier;

    public static <T extends Item> ItemReg<T> of(String name, Function<Item.Properties, T> supplier) {
        return new ItemReg<T>(name, i -> supplier);
    }

    public ItemReg(String name, Function<ItemReg<T>, Function<Item.Properties, T>> supplier) {
        super(name, Registries.ITEM);
        this.supplier = supplier;
    }

    @Override
    protected T createObject(ResourceLocation id) {
        Item.Properties properties = new Item.Properties();
        if(RegFacade.transformObject("IsFoodModifier", false)) {
            FoodProperties.Builder foodProperties = RegFacade.transformObject("FoodPropertyModifiers", new FoodProperties.Builder());
            properties.food(foodProperties.build());

        }
        properties = RegFacade.transformObject("ItemPropertyModifiers", properties);
        properties = this.applyProperties(Item.Properties.class, properties);
        return supplier.apply(this).apply(RegistrationPlatform.itemProperties(id, properties));
    }

    @RegGenerator.ModifyFunction(type = "IsFoodModifier")
    public Boolean isFood(Boolean originalIsFood, Boolean isFood) {
        return isFood;
    }

    @RegGenerator.ModifyFunction(
            type = "ItemPropertyModifiers",
            parameterTypes = {
                    "Supplier<? extends DataComponentType<?>>",
                    "? super Object"
            }
    )
    public <U> Item.Properties component(Item.Properties original, Supplier<DataComponentType<U>> added, U value) {
        return original.component((DataComponentType<? super Object>) added, value);
    }

    @RegGenerator.ConfigureMember()
    public <U> Object component(DataComponentType<U> component, U value) {
        return RegFacade.callConfigure(this, "component", (Supplier<DataComponentType<U>>)()->component, value);
    }


    @RegGenerator.ModifyFunction(type = "TabsToModifiers")
    public ResourceLocation tabTo(ResourceLocation original, Supplier<CreativeModeTab> newer) {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getKey(newer.get());
    }

    @RegGenerator.ModifyFunction(type = "TabsToModifiers")
    public ResourceLocation tabToByKey(ResourceLocation original, Supplier<ResourceLocation> newer) {
        return newer.get();
    }

    @Prototype public Object tabToByKey(Supplier<ResourceLocation> name) {return null;}

    @RegGenerator.ConfigureMember()
    public Object tabTo(CreativeTabReg reg) {
        return this.tabToByKey(reg::getResourceLocation);
    }

    private boolean tabIdCached = false;
    private ResourceLocation cachedTab;

    @Override
    public void register(RegisterContext<?> context) {
        super.register(context);
        context.onStage(RegistrationStage.CREATIVE_TAB_CONTENT_REGISTRATION, ctx->{
            if(!this.tabIdCached) {
                this.tabIdCached = true;
                this.cachedTab = RegFacade.transformObject("TabsToModifiers", null);
                if (this.cachedTab == null) {
                    this.cachedTab = this.transform(CreativeTabModifiers.TYPE, null);
                }
            }
            if (this.cachedTab != null && ctx.getTabKey().location().equals(cachedTab)) {
                ctx.getEvent().accept(this.getEntry());
            }
        });
    }
}

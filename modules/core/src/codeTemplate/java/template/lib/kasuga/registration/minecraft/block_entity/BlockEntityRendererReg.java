package template.lib.kasuga.registration.minecraft.block_entity;

import lib.kasuga.KasugaLib;
import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.internal.generator.facades.RegFacade;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.beans.rendering.RenderingRegistry;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityRendererBuilder;
import lib.kasuga.registration.stages.RegistrationStage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

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
                        type = "BlockEntities",
                        target = Collection.class,
                        extendedType = "Collection<BlockEntityType<?>>"
                )
        }
)
public class BlockEntityRendererReg<B extends BlockEntity> extends Reg<BlockEntityRendererReg<B>, Void> {

    private final Supplier<BlockEntityRendererBuilder<B>> provider;

    public BlockEntityRendererReg(Supplier<BlockEntityRendererBuilder<B>> provider) {
        super();
        this.provider = provider;
    }

    @Override
    public void register(RegisterContext<?> context) {
        super.register(context);
        context.onStage(RegistrationStage.REGISTER_RENDERERS, (ctx)->{
            Collection<BlockEntityType<?>> validBlockEntities = RegFacade.transformObject("BlockEntities", new HashSet<>());
            for (BlockEntityType<?> validBlockEntity : validBlockEntities) {
                //noinspection unchecked
                KasugaLib.getBean(RenderingRegistry.class)
                        .registerBlockEntityRenderer((BlockEntityType<B>) validBlockEntity, provider);
            }
        });
    }

    @Override
    public Void getEntry() {
        throw new IllegalStateException("BlockEntityRendererReg does not have an entry");
    }

    public void block() {}

    @RegGenerator.ModifyFunction(type = "BlockEntities")
    public Collection<BlockEntityType<?>> withBlockEntity(Collection<BlockEntityType<?>> originalValue, Supplier<BlockEntityType<?>> blockEntitySupplier) {
        originalValue.add(blockEntitySupplier.get());
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "BlockEntities")
    public Collection<BlockEntityType<?>> withBlockEntities(Collection<BlockEntityType<?>> originalValue, BiPredicate<ResourceLocation, BlockEntityType<?>> predicate) {
        LinkedList<BlockEntityType<?>> matchedList = new LinkedList<>();
        for (Map.Entry<ResourceKey<BlockEntityType<?>>, BlockEntityType<?>> entry : BuiltInRegistries.BLOCK_ENTITY_TYPE.entrySet()) {
            if (predicate.test(entry.getKey().location(), entry.getValue())) {
                matchedList.add(entry.getValue());
            }
        }
        originalValue.addAll(matchedList);
        return originalValue;
    }
}

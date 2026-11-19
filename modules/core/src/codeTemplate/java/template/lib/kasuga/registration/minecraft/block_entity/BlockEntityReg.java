package template.lib.kasuga.registration.minecraft.block_entity;

import com.mojang.datafixers.types.Type;
import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.internal.generator.facades.RegFacade;
import lib.kasuga.registration.core.ModifierType;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ScopeHelper;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import lib.kasuga.registration.minecraft.common.RegistrationPlatform;
import lib.kasuga.registration.minecraft.block.BlockSupplier;
import lib.kasuga.registration.stages.RegistrationStage;
import lib.kasuga.structure.Pair;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

@CodeTemplate(generator = "Reg")
@RegGenerator(modifiers = {
    @RegGenerator.Modifier(
            type = "ValidBlocksType",
            target = Collection.class,
            extendedType = "Collection<Block>"
    ),
    @RegGenerator.Modifier(
            type = "DataType",
            target = Type.class,
            extendedType = "com.mojang.datafixers.types.Type<?>"
    ),
     @RegGenerator.Modifier(
             type = "Capability",
             target = Collection.class,
             extendedType = "Collection<Pair<BlockCapability<?, ?>, ICapabilityProvider<?, ?, ?>>>"
     )
})
public class BlockEntityReg<T extends BlockEntity> extends MinecraftDeferRegistryReg<BlockEntityReg<T>, BlockEntityType<?>, BlockEntityType<T>>{

    public static final ModifierType<BlockEntityType<? extends BlockEntity>> SCOPE = new ModifierType<>(true);

    private final Function<BlockEntityReg<T>, BlockEntityType.BlockEntitySupplier<T>> supplier;
    private BlockEntityType<T> value;

    public static <T extends BlockEntity> BlockEntityReg<T> of(String name, BlockEntityType.BlockEntitySupplier<T> supplier) {
        return new BlockEntityReg<T>(name, i -> supplier);
    }

    public BlockEntityReg(String name, Function<BlockEntityReg<T>, BlockEntityType.BlockEntitySupplier<T>> supplier) {
        super(name, Registries.BLOCK_ENTITY_TYPE);
        this.supplier = supplier;
        configure(ScopeHelper.effect(SCOPE, this::getEntry));
    }

    @RegGenerator.ModifyFunction(type = "ValidBlocksType")
    public Collection<Block> validBlocks(Collection<Block> originalValue, BiPredicate<ResourceLocation, Block> blockPredicate) {
        LinkedList<Block> castBlockList = new LinkedList<>();
        for (Map.Entry<ResourceKey<Block>, Block> entries : BuiltInRegistries.BLOCK.entrySet()) {
            if (blockPredicate.test(entries.getKey().location(), entries.getValue()))
                castBlockList.add(entries.getValue());
        }
        originalValue.addAll(castBlockList);
        return originalValue;
    }

    @RegGenerator.ModifyFunction(type = "ValidBlocksType")
    public Collection<Block> validBlocks(Collection<Block> originalValue, Collection<Supplier<Block>> blockSuppliers) {
        return blockSuppliers.stream().map(Supplier::get).toList();
    }

    @RegGenerator.ConfigureMember()
    public Object validBlocks(BlockSupplier... blockSuppliers) {
        return RegFacade.callConfigure(this, "validBlocks", Arrays.asList(blockSuppliers));
    }

    @RegGenerator.ModifyFunction(type = "Capability")
    public <V, C> Collection<Pair<BlockCapability<?, ?>, ICapabilityProvider<?, ?, ?>>> addCapability(
            Collection<Pair<BlockCapability<?, ?>, ICapabilityProvider<?, ?, ?>>> original,
            BlockCapability<V, C> capability,
            ICapabilityProvider<? super BlockEntity, C, V> provider
    ) {
        original.add(Pair.of(capability, provider));
        return original;
    }


    @Override
    protected BlockEntityType<T> createObject(ResourceLocation id) {
        Collection<Block> validBlocks = RegFacade.transformObject("ValidBlocksType", new ArrayList<>());
        validBlocks = this.applyProperties(Collection.class, validBlocks);
        Type<?> dataType = RegFacade.transformObject("DataType", null);
        Block[] blocks = validBlocks.toArray(new Block[0]);
        return RegistrationPlatform.blockEntityType(supplier.apply(this), blocks, dataType);
    }


    @SuppressWarnings("unchecked")
    @Override
    public void register(RegisterContext<?> context) {
        super.register(context);
        context.onStage(RegistrationStage.REGISTER_CAPABILITIES, (ctx)->{
            Collection<Pair<BlockCapability<?, ?>, ICapabilityProvider<?, ?, ?>>> capabilities = RegFacade.transformObject("Capability", new ArrayList<>());
            for (Pair<BlockCapability<?, ?>, ICapabilityProvider<?, ?, ?>> capability : capabilities) {
                ctx.getEvent().registerBlockEntity(
                        (BlockCapability<Object, Object>)capability.getFirst(),
                        getEntry(),
                        (ICapabilityProvider<BlockEntity, Object, Object>)capability.getSecond());
            }
        });
    }
}

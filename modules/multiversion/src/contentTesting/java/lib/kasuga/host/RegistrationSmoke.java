package lib.kasuga.host;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.core.resource.ServerResourceReloadFinishEvent;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.reload.Decoded;
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry;
import lib.kasuga.registration.data_driven.state.StringStateProperty;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityRendererReg;
import lib.kasuga.registration.minecraft.entity.EntityReg;
import lib.kasuga.registration.minecraft.entity.EntityRendererReg;
import lib.kasuga.registration.minecraft.fluid.FluidBlockReg;
import lib.kasuga.registration.minecraft.fluid.FluidReg;
import lib.kasuga.registration.minecraft.item.ItemReg;
import lib.kasuga.registration.minecraft.payload.PayloadReg;
import lib.kasuga.registration.minecraft.payload.PayloadStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;

@Context
@Requires(property = "kasuga.graphicsSmoke")
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RegistrationSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier ITEM_ID = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "registration_smoke_item");
    private static final Identifier BLOCK_ID = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "registration_smoke_fixture");
    private static final BlockPos PLACEMENT = new BlockPos(0, 224, 0);
    private static final BlockPos ENTITY_PLACEMENT = PLACEMENT.east();
    private static final int FLUID_TINT = 0xff3fa7d6;
    private static final StreamCodec<FriendlyByteBuf, Ping> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, Ping::value, Ping::new);
    private final ItemReg<Item> item = ItemReg.of(ITEM_ID.getPath(), Item::new).stacksTo(13);
    private final Holder<Item> deferredItem = item.getHolder();
    private final PayloadReg<Ping> ping = new PayloadReg<>("registration_smoke_ping", CODEC);
    private final BlockReg<BlockWithEntity> entityBlock = BlockReg.of("registration_smoke_entity_block", BlockWithEntity::new);
    private final BlockEntityReg<FixtureBlockEntity> blockEntity =
            BlockEntityReg.of("registration_smoke_block_entity", FixtureBlockEntity::new).validBlocks(entityBlock::getEntry);
    private final EntityReg<Marker> entity = EntityReg.of("registration_smoke_entity", Marker::new).size(.25f, .25f);
    private final FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing> fluid =
            new FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing>("registration_smoke_fluid")
                    .instances(BaseFlowingFluid.Source::new, BaseFlowingFluid.Flowing::new)
                    .type(type -> type.still("block/test_fluid_still").flowing("block/test_fluid_flow").tint(FLUID_TINT))
                    .block(FluidBlockReg.of("registration_smoke_fluid_block", source -> props -> new LiquidBlock(source, props)))
                    .bucket("registration_smoke_bucket");
    private volatile boolean blockRendererBuilt;
    private volatile boolean entityRendererBuilt;
    private volatile String reloadValue;
    private volatile boolean reloaded;
    private int reply;
    private boolean sent;
    private boolean verified;

    public RegistrationSmoke() {
        KasugaLibApplication.REGISTRY.addChild(item);
        KasugaLibApplication.REGISTRY.addChild(entityBlock).addChild(blockEntity).addChild(entity).addChild(fluid);
        KasugaLibApplication.REGISTRY.addChild(new BlockEntityRendererReg<FixtureBlockEntity>(() -> context -> {
            blockRendererBuilt = true;
            return new FixtureBlockEntityRenderer();
        }).withBlockEntity(blockEntity::getEntry));
        KasugaLibApplication.REGISTRY.addChild(new EntityRendererReg<Marker>(() -> context -> {
            entityRendererBuilt = true;
            return new NoopRenderer<>(context);
        }).withEntity(entity::getEntry));
        ping.stage(PayloadStage.PLAY);
        ping.server(() -> (packet, context) -> PacketDistributor.sendToPlayer(
                (net.minecraft.server.level.ServerPlayer) context.player(), new Ping(packet.value() + 1)));
        ping.client(() -> (packet, context) -> reply = packet.value());
        KasugaLibApplication.REGISTRY.addChild(ping);
        ReloadHandlerRegistry.register(new ReloadHandler<String>() {
            @Override public String typeName() { return "registration_smoke"; }
            @Override public void clearBucket() { reloadValue = null; }
            @Override public void register(String payload) { reloadValue = payload; }

            @Override public Decoded<String> decode(JsonObject root) {
                return new Decoded<>(List.of(new Decoded.Entry<>("registration_smoke",
                        root.get(typeName()).getAsString())), List.of());
            }
        });
    }

    @SubscribeEvent
    public static void initializeWorld(ServerStartedEvent event) {
        if (!enabled()) return;
        RegistrationSmoke smoke = KasugaLib.getBean(RegistrationSmoke.class);
        if (BuiltInRegistries.ITEM.getValue(ITEM_ID) != smoke.item.getEntry()
                || smoke.deferredItem.value() != smoke.item.getEntry()
                || smoke.item.getEntry().getDefaultMaxStackSize() != 13) {
            throw new IllegalStateException("Generated item registration or the retained deferred holder failed");
        }
        Block block = BuiltInRegistries.BLOCK.getValue(BLOCK_ID);
        if (!(BuiltInRegistries.ITEM.getValue(BLOCK_ID) instanceof BlockItem item) || item.getBlock() != block
                || !JsonTreeBuilder.getLoadingErrors(KasugaLib.MODID).isEmpty()) {
            throw new IllegalStateException("Data-driven block or default item failed to register");
        }
        BlockState state = block.defaultBlockState();
        StringStateProperty mode = (StringStateProperty) block.getStateDefinition().getProperty("mode");
        IntegerProperty level = (IntegerProperty) block.getStateDefinition().getProperty("level");
        if (state.getValue(BlockStateProperties.OPEN) || !state.getValue(mode).equals("working") || state.getValue(level) != 2) {
            throw new IllegalStateException("Data-driven state defaults were not applied");
        }
        ServerLevel world = event.getServer().overworld();
        if (!state.getCollisionShape(world, PLACEMENT).isEmpty()) {
            throw new IllegalStateException("Data-driven no_collision was not applied");
        }
        world.setBlockAndUpdate(PLACEMENT, state.setValue(BlockStateProperties.OPEN, true).setValue(mode, "paused").setValue(level, 3));
        world.setBlockAndUpdate(ENTITY_PLACEMENT, smoke.entityBlock.getEntry().defaultBlockState());
        if (!(world.getBlockEntity(ENTITY_PLACEMENT) instanceof FixtureBlockEntity)
                || BuiltInRegistries.ENTITY_TYPE.getValue(id("registration_smoke_entity")) != smoke.entity.getEntry()
                || BuiltInRegistries.FLUID.getValue(id("registration_smoke_fluid")) != smoke.fluid.getSource()
                || BuiltInRegistries.FLUID.getValue(id("registration_smoke_fluid_flowing")) != smoke.fluid.getFlowing()
                || !(smoke.fluid.getBucketReg().getEntry() instanceof BucketItem)) {
            throw new IllegalStateException("Block entity, entity, or fluid registration failed");
        }
    }

    @SubscribeEvent
    public static void reloadFinished(ServerResourceReloadFinishEvent event) {
        if (!enabled()) return;
        RegistrationSmoke smoke = KasugaLib.getBean(RegistrationSmoke.class);
        if (!"from_manifest".equals(smoke.reloadValue)) {
            throw new IllegalStateException("Data-driven reload handler did not consume its manifest entry");
        }
        smoke.reloaded = true;
    }

    public static boolean clientReady(Minecraft client) {
        RegistrationSmoke smoke = KasugaLib.getBean(RegistrationSmoke.class);
        if (smoke.verified) return true;
        if (!smoke.sent) {
            smoke.sent = true;
            ClientPacketDistributor.sendToServer(new Ping(62));
        }
        BlockState state = client.level.getBlockState(PLACEMENT);
        if (state.getBlock() != BuiltInRegistries.BLOCK.getValue(BLOCK_ID)) return false;
        StringStateProperty mode = (StringStateProperty) state.getBlock().getStateDefinition().getProperty("mode");
        if (!state.getValue(BlockStateProperties.OPEN) || !state.getValue(mode).equals("paused")
                || smoke.reply != 63 || !smoke.reloaded) return false;
        if (!smoke.blockRendererBuilt || !smoke.entityRendererBuilt) {
            throw new IllegalStateException("Registry renderer stages did not create the registered renderers");
        }
        FluidModel fluidModel = client.getModelManager().getFluidStateModelSet()
                .get(smoke.fluid.getSource().defaultFluidState());
        if (!fluidModel.stillMaterial().sprite().contents().name().equals(id("block/test_fluid_still"))
                || fluidModel.fluidTintSource().color(smoke.fluid.getSource().defaultFluidState()) != FLUID_TINT) {
            throw new IllegalStateException("Fluid model lost its texture or tint");
        }
        smoke.verified = true;
        LOGGER.info("KASUGA_REGISTRATION_PASS item,holder,json-block,state-sync,reload,payload,entity-renderers,fluid-model");
        return true;
    }

    private static boolean enabled() {
        return !System.getProperty("kasuga.graphicsSmoke", "").isEmpty();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path);
    }

    private static final class BlockWithEntity extends Block implements EntityBlock {
        private BlockWithEntity(BlockBehaviour.Properties properties) { super(properties); }

        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new FixtureBlockEntity(pos, state);
        }
    }

    private static final class FixtureBlockEntity extends BlockEntity {
        private FixtureBlockEntity(BlockPos pos, BlockState state) {
            super(KasugaLib.getBean(RegistrationSmoke.class).blockEntity.getEntry(), pos, state);
        }
    }

    private static final class FixtureBlockEntityRenderer implements BlockEntityRenderer<FixtureBlockEntity, BlockEntityRenderState> {
        @Override public BlockEntityRenderState createRenderState() { return new BlockEntityRenderState(); }

        @Override public void submit(BlockEntityRenderState state, PoseStack pose, SubmitNodeCollector nodes,
                                     CameraRenderState camera) {}
    }

    private record Ping(int value) implements CustomPacketPayload {
        @Override public Type<? extends CustomPacketPayload> type() {
            return KasugaLib.getBean(RegistrationSmoke.class).ping.getEntry();
        }
    }
}

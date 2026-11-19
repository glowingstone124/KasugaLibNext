package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.FsmSyncKey;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncServer;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Base block entity that hosts a data-driven {@link StateMachine}. A thin delegate: all per-host animation
 * orchestration — lazy machine build, client model bind / self-heal, FSM pose-driver plug-in, per-tick drive +
 * GPU flush, and server↔client sync — lives in {@link FsmAnimatedModel}. This class keeps only the block-entity
 * concerns (persistence, ticking entry point, capability surface) and forwards.
 *
 * <p>The BE itself is the {@link StateMachine}'s {@code owner}, so action/condition lambdas recover it via
 * {@code ctx.owner()} (e.g. to read redstone power). Sync contract: the server pushes authoritative state every
 * tick via {@link FsmSyncServer}; the client applies it via {@code FsmSyncClient} on the main thread. Hosts
 * never register into {@code FsmMachines}; definitions are read from the shared definition bucket.
 */
public class AnimationBlockEntity extends BlockEntity implements AnimationHost {

    /** Both sides use the same ticker: {@link FsmAnimatedModel#tick} drives everything. */
    public static final BlockEntityTicker<AnimationBlockEntity> TICKER =
            (level, pos, state, blockEntity) -> blockEntity.model.tick(level, pos);

    /**
     * Shared server-side FSM sync instance (see {@link FsmSyncServer#GLOBAL} — dedup/heartbeat tables must be
     * global or logout cleanup misses entries).
     */
    public static final FsmSyncServer SYNC_SERVER = FsmSyncServer.GLOBAL;

    private final FsmAnimatedModel model;

    public AnimationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.model = new FsmAnimatedModel(this, worldPosition.asLong(), this::rootTransform,
                null, null, null, this::onMachineBuilt, this::onClientModelBound);
    }

    public AnimationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                @Nullable Id stateMachineId,
                                @Nullable Identifier modelLoc, @Nullable String modelName) {
        this(type, pos, state);
        // level is null at construction — the wrapper's config methods tolerate that (no live sync key to unbind)
        this.model.stateMachine(stateMachineId, null);
        this.model.model(modelLoc, modelName, null);
    }

    /**
     * Root transform placing the model at this block's position. Centered on x/z (+0.5) to match the
     * framework's block-anchoring convention (models authored centered at the origin, Blockbench default,
     * then sit at the block center rather than the corner).
     */
    private Transform rootTransform() {
        return new Transform()
                .translate(worldPosition.getX() + 0.5f, worldPosition.getY(), worldPosition.getZ() + 0.5f);
    }

    //region AnimationHost

    @Override
    public synchronized StateMachine<?> machine() {
        return model.machine(level);
    }

    //endregion

    //region config + accessors (delegate to the wrapper)

    /** Programmatic machine assignment; takes effect on the next tick (machine rebuilt lazily). */
    public void stateMachine(@Nullable Id id) {
        model.stateMachine(id, level);
    }

    /** Programmatic model assignment; unbinds the previous binding, if any. */
    public void model(@Nullable Identifier loc, @Nullable String name) {
        model.model(loc, name, level);
    }

    /** Attach the per-entity rendering variable provider — forwarded to the {@link FsmAnimatedModel}. */
    public void setVarProvider(@Nullable VarProvider provider) {
        model.setVarProvider(provider);
    }

    public @Nullable Id stateMachineId() {
        return model.stateMachineId();
    }

    public boolean isModelBound() {
        return model.isModelBound();
    }

    /** {@code -1} until the machine is built (definitions not ready / model not bound yet). */
    public int machineVersion() {
        return model.machineVersion();
    }

    public Map<String, String> activeStates() {
        return model.activeStates();
    }

    /** Unique per-owner discriminator for sync keys: block position packed as a long. */
    public long ownerDiscriminator() {
        return worldPosition.asLong();
    }

    public FsmSyncKey syncKey() {
        return model.syncKey(level);
    }

    //endregion

    //region persistence — only the programmatic ids; the machine itself rebuilds lazily from the definition

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        writePersistedIds(output, model.stateMachineId(), model.modelLoc(), model.modelName());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        PersistedIds ids = PersistedIds.read(input);
        model.stateMachine(ids.stateMachineId(), level);
        model.model(ids.modelLoc(), ids.modelName(), level);
    }

    /**
     * The three programmatic ids persisted to NBT. Runtime state is NOT persisted — the machine rebuilds
     * lazily from the restored definition id.
     */
    public record PersistedIds(
            @Nullable Id stateMachineId,
            @Nullable Identifier modelLoc,
            @Nullable String modelName
    ) {
        /** Read the persisted ids from a tag (fields absent when null at save time). */
        public static PersistedIds read(ValueInput input) {
            return new PersistedIds(
                    input.getString("stateMachine").map(Id::tryParse).orElse(null),
                    input.getString("modelLoc").map(Identifier::tryParse).orElse(null),
                    input.getStringOr("modelName", null));
        }
    }

    /** Write the persisted ids to a tag (omits nulls). Static + pure so it can be unit-tested without a BE. */
    public static void writePersistedIds(ValueOutput output,
                                         @Nullable Id stateMachineId,
                                         @Nullable Identifier modelLoc,
                                         @Nullable String modelName) {
        if (stateMachineId != null) {
            output.putString("stateMachine", stateMachineId.toString());
        }
        if (modelLoc != null) {
            output.putString("modelLoc", modelLoc.toString());
        }
        if (modelName != null) {
            output.putString("modelName", modelName);
        }
    }

    //endregion

    //region lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        model.onLoad(level);
    }

    @Override
    public void onChunkUnloaded() {
        model.onChunkUnloaded(level);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        model.onRemoved(level);
        super.setRemoved();
    }

    //endregion

    //region extension points

    /** Called once when the machine is first built (both sides). */
    protected void onMachineBuilt(StateMachine<?> machine) {
    }

    /** Called whenever a model instance is (re)bound on the client. */
    protected void onClientModelBound(ModelInstance modelInstance) {
    }

    //endregion
}

package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Collection;
import java.util.List;

/** Standard MMD geometry and animation data with platform-specific texture resolution. */
public abstract class MmdMeshLoader<I, O, T, C extends ContextData<C>> extends PMXLoader<I, O, T, C> {
    public static final float DEFAULT_MODEL_SCALE = 1.0f / 12.0f;

    protected MmdMeshLoader(String name) {
        super(name);
    }

    protected abstract Vector3fc modelScale();

    @Override
    public Vertex getVertex(PmxVertex first, Collection<PmxVertex> equivalentVertices) {
        if (equivalentVertices.isEmpty() || first == null) {
            throw new IllegalArgumentException("Vertex collection cannot be empty");
        }
        Vector3fc scale = modelScale();
        if (first.binding.type == PmxBoneBinding.BindingType.SDEF && first.binding.data != null) {
            first.binding.data.c().mul(scale);
            first.binding.data.r0().mul(scale);
            first.binding.data.r1().mul(scale);
        }
        Vector3f position = new Vector3f(first.position).mul(scale);
        return new Vertex(position, first);
    }

    @Override
    public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) {
        return new Mesh(new Vertex[]{v1, v2, v3}, new Vector3f(), new Transform(), new Material[1], mesh);
    }

    @Override
    public Bone getBone(List<PmxBone> bones, PmxBone bone) {
        return new Bone(bone.localBoneName, super.calculateBoneTransform(bones, bone), bone);
    }

    @Override
    public void scaleBone(PmxBone bone) {
        Vector3fc scale = modelScale();
        bone.position.mul(scale);
        if (bone.tailObject instanceof Vector3f offset) {
            offset.mul(scale);
        }
    }

    @Override
    protected Vector3f scaleMmdTranslation(Vector3f value) {
        return value.mul(modelScale());
    }

    @Override
    public @Nullable ModelData getModelData(PmxHeader header) {
        return header;
    }

    @Override
    public @Nullable ModelData getModelData(PmxHeader header, PmxTail tail) {
        return new MmdModelData(header, tail, new Vector3f(modelScale()), getBones().size());
    }
}

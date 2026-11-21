package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.DualQuaternion;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.structure.Pair;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;

final class RenderPearlModelSkin {
    static final String PALETTE_UNIFORM = "KasugaBonePalette";
    static final VertexFormat BINDINGS = VertexFormat.builder(0)
            .addAttribute("BoneBindingType", GpuFormat.R32_SINT)
            .addAttribute("BoneIndices", GpuFormat.RGBA32_SINT)
            .addAttribute("BoneWeights", GpuFormat.RGBA32_FLOAT)
            .addAttribute("SdefCenter", GpuFormat.RGB32_FLOAT)
            .addAttribute("SdefRadius0", GpuFormat.RGB32_FLOAT)
            .addAttribute("SdefRadius1", GpuFormat.RGB32_FLOAT)
            .build();
    // A skin matrix, its normal matrix and the equivalent dual quaternion.
    static final int TEXELS_PER_BONE = 9;
    static final int BONE_BYTES = TEXELS_PER_BONE * 4 * Float.BYTES;
    // Stay within the minimum supported texel buffer size on Vulkan and OpenGL.
    static final int MAX_BONES = 65536 / TEXELS_PER_BONE;
    private static final int BDEF = 1;
    private static final int SDEF = 2;
    private static final int QDEF = 3;

    private RenderPearlModelSkin() {}

    static boolean canRepresent(BoneBinding binding) {
        return binding.getWeights().length <= 4 && (binding.getFunc() == BoneBindingFunc.BDEF
                || binding.getFunc() == BoneBindingFunc.SDEF || binding.getFunc() == BoneBindingFunc.QDEF);
    }

    static void writeBinding(@Nullable BoneBinding binding, ByteBuffer data, int offset) {
        int kind = 0;
        Pair<Bone, Float>[] weights = binding == null ? null : binding.getWeights();
        SDEFData spherical = null;
        if (binding != null && weights.length != 0) {
            kind = binding.getFunc() == BoneBindingFunc.QDEF ? QDEF : BDEF;
            if (binding.getFunc() == BoneBindingFunc.SDEF && weights.length >= 2
                    && binding.getData() instanceof SDEFBoneBindingData parameters
                    && weights[0].getSecond() + weights[1].getSecond() >= 1e-6f) {
                spherical = parameters.getSDEFData();
                if (spherical != null) kind = SDEF;
            }
        }
        data.putInt(offset, kind);
        for (int i = 0; i < 4; i++) {
            boolean present = weights != null && i < weights.length;
            data.putInt(offset + 4 + i * 4, present ? weights[i].getFirst().getIndex() : 0);
            data.putFloat(offset + 20 + i * 4, present ? weights[i].getSecond() : 0);
        }
        if (spherical != null) {
            spherical.c().get(offset + 36, data);
            spherical.r0().get(offset + 48, data);
            spherical.r1().get(offset + 60, data);
        } else {
            for (int i = 36; i < 72; i += 4) data.putFloat(offset + i, 0);
        }
    }

    static final class Palette {
        private final Joint[] joints;

        Palette(SkeletonInstance skeleton) {
            Bone[] bones = skeleton.getSkeleton().getBones();
            joints = new Joint[bones.length];
            for (Bone bone : bones) {
                Pair<Transform, Transform> reference = skeleton.getSkeleton().getBoneTransforms().get(bone);
                Transform absolute = skeleton.getAbsoluteTransforms().get(bone);
                if (reference == null || absolute == null) continue;
                Joint joint = new Joint(absolute.copy(), reference.getSecond());
                joints[bone.getIndex()] = joint;
            }
        }

        int size() { return joints.length; }

        void contexts(BoneBinding binding, List<BoneContext> contexts) {
            contexts.clear();
            for (Pair<Bone, Float> weight : binding.getWeights()) {
                Bone bone = weight.getFirst();
                Joint joint = joints[bone.getIndex()];
                // The standard skinning functions use the absolute and inverse-bind transforms.
                contexts.add(new BoneContext<>(bone, weight.getSecond(), bone.getBoneData(), null, null,
                        joint.absolute(), joint.inverseBind()));
            }
        }

        void write(ByteBuffer data, int offset) {
            Transform skin = new Transform();
            for (Joint joint : joints) {
                if (joint == null) skin.setIdentity();
                else skin.set(joint.absolute()).mul(joint.inverseBind());
                skin.transform().get(offset, data);
                Matrix3f normal = skin.normal();
                writeColumn(data, offset + 64, normal.m00(), normal.m01(), normal.m02(), 0);
                writeColumn(data, offset + 80, normal.m10(), normal.m11(), normal.m12(), 0);
                writeColumn(data, offset + 96, normal.m20(), normal.m21(), normal.m22(), 0);
                DualQuaternion rotation = skin.toDualQuaternion();
                writeQuaternion(data, offset + 112, rotation.getReal());
                writeQuaternion(data, offset + 128, rotation.getDual());
                offset += BONE_BYTES;
            }
        }
    }

    private static void writeQuaternion(ByteBuffer data, int offset, Quaternionf value) {
        writeColumn(data, offset, value.x(), value.y(), value.z(), value.w());
    }

    private static void writeColumn(ByteBuffer data, int offset, float x, float y, float z, float w) {
        data.putFloat(offset, x);
        data.putFloat(offset + 4, y);
        data.putFloat(offset + 8, z);
        data.putFloat(offset + 12, w);
    }

    private record Joint(Transform absolute, Transform inverseBind) {}
}

package lib.kasuga.shader.backend;

import lib.kasuga.shader.ShaderGlobal;
import lib.kasuga.shader.ShaderStorage;
import lib.kasuga.shader.ShaderType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Byte layout for the scalar, vector, matrix and float-array uniforms supported by the DSL. */
public final class Std140UniformBlock {
    private final Map<String, Uniform> uniforms = new LinkedHashMap<>();
    private final int size;

    public Std140UniformBlock(List<ShaderGlobal> globals) {
        int end = 0;
        for (ShaderGlobal global : globals) {
            if (global.storage() != ShaderStorage.UNIFORM || !(global.type() instanceof ShaderType type)
                    || (global.isArray() && type != ShaderType.FLOAT)) {
                throw new IllegalArgumentException("Unsupported uniform " + global.name());
            }
            int width = switch (type) {
                case FLOAT, INT, BOOL -> 1;
                case VEC2, MAT2 -> 2;
                case VEC3, MAT3 -> 3;
                case VEC4, MAT4 -> 4;
                case SAMPLER_2D -> throw new IllegalArgumentException("Sampler in uniform block: " + global.name());
            };
            boolean matrix = type == ShaderType.MAT2 || type == ShaderType.MAT3 || type == ShaderType.MAT4;
            int columns = matrix ? width : Math.max(1, global.arrayLength());
            int alignment = columns > 1 || width >= 3 ? 16 : width * Float.BYTES;
            int offset = align(end, alignment);
            Uniform uniform = new Uniform(type, offset, width, columns, global.defaultValues());
            if (uniforms.putIfAbsent(global.name(), uniform) != null) {
                throw new IllegalArgumentException("Duplicate uniform " + global.name());
            }
            end = Math.addExact(offset, columns > 1 ? Math.multiplyExact(columns, 16) : width * Float.BYTES);
        }
        size = align(end, 16);
    }

    public int size() { return size; }

    public void encode(ByteBuffer buffer, Map<String, double[]> overrides) {
        if (buffer.remaining() < size) throw new IllegalArgumentException("Uniform block buffer is too small");
        ByteBuffer destination = buffer.slice().order(ByteOrder.nativeOrder());
        for (String name : overrides.keySet()) {
            if (!uniforms.containsKey(name)) throw new IllegalArgumentException("Unknown uniform " + name);
        }
        for (int offset = 0; offset < size; offset += Integer.BYTES) destination.putInt(offset, 0);
        for (Map.Entry<String, Uniform> entry : uniforms.entrySet()) {
            String name = entry.getKey();
            Uniform uniform = entry.getValue();
            double[] values = overrides.get(name);
            int count = uniform.width() * uniform.columns();
            if (values != null && values.length != count) {
                throw new IllegalArgumentException(name + " expects " + count + " components");
            }
            for (int index = 0; index < count; index++) {
                double value = values != null ? values[index] : defaultValue(uniform.defaults(), index);
                int offset = uniform.offset() + (index / uniform.width()) * 16 + (index % uniform.width()) * Float.BYTES;
                switch (uniform.type()) {
                    case INT, BOOL -> {
                        if (value != (int) value || (uniform.type() == ShaderType.BOOL && value != 0 && value != 1)) {
                            throw new IllegalArgumentException("Invalid integral uniform " + name);
                        }
                        destination.putInt(offset, (int) value);
                    }
                    default -> {
                        if (!Float.isFinite((float) value)) throw new IllegalArgumentException("Invalid float uniform " + name);
                        destination.putFloat(offset, (float) value);
                    }
                }
            }
        }
    }

    private static double defaultValue(List<Number> defaults, int index) {
        if (defaults.size() == 1) return defaults.getFirst().doubleValue();
        return index < defaults.size() ? defaults.get(index).doubleValue() : 0;
    }

    private static int align(int offset, int alignment) {
        return Math.addExact(offset, alignment - 1) & -alignment;
    }

    private record Uniform(ShaderType type, int offset, int width, int columns, List<Number> defaults) {}
}

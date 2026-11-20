package lib.kasuga.shader.backend;

import lib.kasuga.shader.ShaderGlobal;
import lib.kasuga.shader.ShaderModule;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderStorage;
import lib.kasuga.shader.ShaderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Emits linked GLSL stages for RenderPearl. */
public final class RenderPearlGlslBackend {
    public static final String PARAMETERS = "KasugaParameters";
    private static final String HEADER = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n";

    private RenderPearlGlslBackend() {}

    public static Fullscreen emitFullscreen(ShaderProgram program) {
        if (program.kind() != ShaderProgram.Kind.FULLSCREEN) {
            throw new IllegalArgumentException("Expected a fullscreen shader: " + program.id());
        }
        ShaderModule module = program.fragmentModule();
        List<ShaderGlobal> uniforms = new ArrayList<>();
        List<String> samplers = new ArrayList<>();
        for (ShaderGlobal global : module.globals()) {
            switch (global.storage()) {
                case UNIFORM -> uniforms.add(global);
                case SAMPLER -> samplers.add(global.name());
                case INPUT -> {
                    if (!global.name().equals("texCoord") || global.type() != ShaderType.VEC2) {
                        throw new IllegalArgumentException("Unsupported fullscreen input: " + global.name());
                    }
                }
                case OUTPUT -> {
                    if (!global.name().equals("fragColor") || global.type() != ShaderType.VEC4) {
                        throw new IllegalArgumentException("Unsupported fullscreen output: " + global.name());
                    }
                }
            }
        }
        String source = emitStage(module, uniforms, Map.of("texCoord", 0), Map.of("fragColor", 0));
        return new Fullscreen(source, List.copyOf(uniforms), List.copyOf(samplers));
    }

    public static Graphics emitGraphics(ShaderProgram program) {
        if (program.kind() != ShaderProgram.Kind.GRAPHICS) {
            throw new IllegalArgumentException("Expected a graphics shader: " + program.id());
        }
        ShaderModule vertex = program.vertexModule();
        ShaderModule fragment = program.fragmentModule();
        Map<String, ShaderGlobal> resources = new LinkedHashMap<>();
        collectResources(vertex, resources);
        collectResources(fragment, resources);
        List<ShaderGlobal> uniforms = new ArrayList<>();
        List<String> samplers = new ArrayList<>();
        for (ShaderGlobal resource : resources.values()) {
            if (resource.storage() == ShaderStorage.UNIFORM) uniforms.add(resource);
            else samplers.add(resource.name());
        }
        Map<String, Integer> attributes = locations(vertex, ShaderStorage.INPUT);
        Map<String, Integer> varyings = locations(vertex, ShaderStorage.OUTPUT);
        String vertexSource = emitStage(vertex, uniforms, attributes, varyings);
        String fragmentSource = emitStage(fragment, uniforms, varyings, Map.of("fragColor", 0));
        return new Graphics(vertexSource, fragmentSource, List.copyOf(uniforms), List.copyOf(samplers));
    }

    private static void collectResources(ShaderModule module, Map<String, ShaderGlobal> resources) {
        for (ShaderGlobal global : module.globals()) {
            if (global.storage() == ShaderStorage.UNIFORM || global.storage() == ShaderStorage.SAMPLER) {
                resources.putIfAbsent(global.name(), global);
            }
        }
    }

    private static Map<String, Integer> locations(ShaderModule module, ShaderStorage storage) {
        Map<String, Integer> locations = new LinkedHashMap<>();
        int location = 0;
        for (ShaderGlobal global : module.globals()) {
            if (global.storage() != storage) continue;
            locations.put(global.name(), location);
            int columns = 1;
            if (global.type() == ShaderType.MAT2) columns = 2;
            else if (global.type() == ShaderType.MAT3) columns = 3;
            else if (global.type() == ShaderType.MAT4) columns = 4;
            location += columns * Math.max(1, global.arrayLength());
        }
        return locations;
    }

    private static String emitStage(ShaderModule module, List<ShaderGlobal> uniforms,
                                    Map<String, Integer> inputs, Map<String, Integer> outputs) {
        return Glsl150Backend.emitModule(module, HEADER,
                output -> emitGlobals(output, module.globals(), uniforms, inputs, outputs));
    }

    private static void emitGlobals(StringBuilder output, List<ShaderGlobal> globals, List<ShaderGlobal> uniforms,
                                    Map<String, Integer> inputs, Map<String, Integer> outputs) {
        for (ShaderGlobal global : globals) {
            switch (global.storage()) {
                case UNIFORM -> { continue; }
                case SAMPLER -> output.append("uniform ");
                case INPUT -> output.append("layout(location = ").append(inputs.get(global.name())).append(") in ");
                case OUTPUT -> output.append("layout(location = ").append(outputs.get(global.name())).append(") out ");
            }
            Glsl150Backend.appendGlobalDeclaration(output, global);
        }
        if (uniforms.isEmpty()) return;
        // RenderPearl links one binding by name. Both stages need the same layout, including stage-private fields.
        output.append("layout(std140) uniform ").append(PARAMETERS).append(" {\n");
        for (ShaderGlobal uniform : uniforms) {
            output.append("    ");
            Glsl150Backend.appendGlobalDeclaration(output, uniform);
        }
        output.append("};\n");
    }

    public record Graphics(String vertexSource, String fragmentSource, List<ShaderGlobal> uniforms, List<String> samplers) {}

    public record Fullscreen(String fragmentSource, List<ShaderGlobal> uniforms, List<String> samplers) {}
}

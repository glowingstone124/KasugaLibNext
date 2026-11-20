package lib.kasuga.shader.backend;

import lib.kasuga.shader.ShaderGlobal;
import lib.kasuga.shader.ShaderModule;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderStorage;
import lib.kasuga.shader.ShaderType;

import java.util.ArrayList;
import java.util.List;

/** Emits the fragment stage for Kasuga's RenderPearl fullscreen triangle. */
public final class RenderPearlGlslBackend {
    public static final String PARAMETERS = "KasugaParameters";

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
        String source = Glsl150Backend.emitModule(module, "#version 330\n#extension GL_ARB_separate_shader_objects : require\n",
                output -> emitGlobals(output, module.globals(), uniforms));
        return new Fullscreen(source, List.copyOf(uniforms), List.copyOf(samplers));
    }

    private static void emitGlobals(StringBuilder output, List<ShaderGlobal> globals, List<ShaderGlobal> uniforms) {
        for (ShaderGlobal global : globals) {
            switch (global.storage()) {
                case UNIFORM -> { continue; }
                case SAMPLER -> output.append("uniform ");
                case INPUT -> output.append("layout(location = 0) in ");
                case OUTPUT -> output.append("layout(location = 0) out ");
            }
            Glsl150Backend.appendGlobalDeclaration(output, global);
        }
        if (uniforms.isEmpty()) return;
        output.append("layout(std140) uniform ").append(PARAMETERS).append(" {\n");
        for (ShaderGlobal uniform : uniforms) {
            output.append("    ");
            Glsl150Backend.appendGlobalDeclaration(output, uniform);
        }
        output.append("};\n");
    }

    public record Fullscreen(String fragmentSource, List<ShaderGlobal> uniforms, List<String> samplers) {}
}

package lib.kasuga.shader;

import lib.kasuga.shader.backend.RenderPearlGlslBackend;
import lib.kasuga.shader.backend.Std140UniformBlock;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Std140UniformBlockTest {
    @Test
    void writesPaddedArraysAndMatricesAndPreservesIntegerPrecision() {
        ShaderProgram program = ShaderProgram.fullscreen("test:layout", shader -> {
            shader.uniformVec3("Color", .25f, .5f, .75f);
            shader.uniformBool("Enabled", true);
            shader.uniformInt("Index", 16777217);
            shader.uniformMat2("Transform", 2, 3, 4, 5);
            shader.uniformFloatArray("Weights", 3, .5f);
            shader.fragmentColor(shader.vec4(1, 1, 1, 1));
        });
        Std140UniformBlock layout = new Std140UniformBlock(RenderPearlGlslBackend.emitFullscreen(program).uniforms());
        ByteBuffer bytes = ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());
        layout.encode(bytes, Map.of());
        assertEquals(112, layout.size());
        assertEquals(.75f, bytes.getFloat(8));
        assertEquals(1, bytes.getInt(12));
        assertEquals(16777217, bytes.getInt(16));
        assertEquals(2f, bytes.getFloat(32));
        assertEquals(3f, bytes.getFloat(36));
        assertEquals(4f, bytes.getFloat(48));
        assertEquals(5f, bytes.getFloat(52));
        assertEquals(.5f, bytes.getFloat(96));
        assertThrows(IllegalArgumentException.class, () -> layout.encode(bytes, Map.of("Enabled", new double[]{2})));
        assertThrows(IllegalArgumentException.class, () -> layout.encode(bytes, Map.of("Weights", new double[]{1, 2})));
    }
}

package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lombok.Getter;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Getter
public class JEElement {
    
    private final Vector3f from;
    private final Vector3f to;
    private final Map<Direction, JEFace> faces;

    @Nullable
    private final JERotation rotation;
    
    public JEElement(Vector3f from, Vector3f to, Map<Direction, JEFace> faces, JERotation rotation) {
        this.from = from;
        this.to = to;
        this.faces = faces;
        this.rotation = rotation;
    }

    public Vector3f getSize() {
        return new Vector3f(to).sub(from);
    }
    
    public boolean hasRotation() {
        return rotation != null;
    }

    public static JEElement fromJson(JsonObject input) {
        return ModelJson.GSON.fromJson(Objects.requireNonNull(input), Input.class).build();
    }

    @NonNullFields
    static final class Input {
        Vector3f from, to;
        Map<String, JEFace.Input> faces = Map.of();
        JERotation.Input rotation;
        JEElement build() {
            if (from == null || to == null) throw new IllegalArgumentException("Element must have both 'from' and 'to' fields");
            Map<Direction, JEFace> built = new HashMap<>();
            for (Map.Entry<String, JEFace.Input> entry : faces.entrySet()) {
                Direction direction = Direction.fromString(entry.getKey());
                JEFace.Input face = entry.getValue();
                built.put(direction, face.uv == null ? face.build(defaultUv(direction)) : face.build());
            }
            return new JEElement(from.div(16f), to.div(16f), built, rotation == null ? null : rotation.build());
        }

        private float[] defaultUv(Direction direction) {
            return switch (direction) {
                case DOWN -> new float[]{from.x, 16f - to.z, to.x, 16f - from.z};
                case UP -> new float[]{from.x, from.z, to.x, to.z};
                case NORTH -> new float[]{16f - to.x, 16f - to.y, 16f - from.x, 16f - from.y};
                case SOUTH -> new float[]{from.x, 16f - to.y, to.x, 16f - from.y};
                case WEST -> new float[]{from.z, 16f - to.y, to.z, 16f - from.y};
                case EAST -> new float[]{16f - to.z, 16f - to.y, 16f - from.z, 16f - from.y};
            };
        }
    }
}

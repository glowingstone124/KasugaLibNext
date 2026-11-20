package lib.kasuga.rendering.models.mc.java_and_bedrock;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEElement;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEFace;
import lib.kasuga.rendering.models.mc.util.Direction;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaModelUvTest {
    @Test
    void absentUvsUseElementBoundsWhileExplicitUvsWin() {
        JEElement element = JEElement.fromJson(JsonParser.parseString("""
                {
                  "from":[2,3,4],
                  "to":[10,14,12],
                  "faces":{
                    "north":{"texture":"#all"},
                    "down":{"texture":"#all"},
                    "south":{"texture":"#all","uv":[1,2,3,4]}
                  }
                }
                """).getAsJsonObject());
        check(element, Direction.NORTH, new Vector2f(6, 2), new Vector2f(14, 13));
        check(element, Direction.DOWN, new Vector2f(2, 4), new Vector2f(10, 12));
        check(element, Direction.SOUTH, new Vector2f(1, 2), new Vector2f(3, 4));
    }

    private static void check(JEElement element, Direction side, Vector2f from, Vector2f to) {
        JEFace face = element.getFaces().get(side);
        assertEquals(from, face.getUvFrom());
        assertEquals(to, face.getUvTo());
    }
}

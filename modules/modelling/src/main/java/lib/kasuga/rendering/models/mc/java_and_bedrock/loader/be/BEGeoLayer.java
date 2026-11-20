package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.be.BEModelData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.TextureLayer;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.utils.json.JsonFieldPolicy.DefaultOnMismatch;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.structure.Pair;
import org.joml.Vector3f;

import java.util.HashMap;

public class BEGeoLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        var components = lib.kasuga.rendering.models.mc.component.ModelComponents.parse(input.get("kasuga_lib"));
        context.setData("model_components", components);
        context.setData("component_root", components.isEmpty() ? null : components.rootName());
        if (!components.isEmpty()) context.getLoader().getBones().addBone(components.rootName(), new Transform(), null, null);
        Input definition = ModelJson.GSON.fromJson(input, Input.class);
        Description data = definition.description;
        String identifier = data.identifier;
        float textureWidth = data.textureWidth;
        float textureHeight = data.textureHeight;
        float visibleBoundsWidth = data.visibleBoundsWidth;
        float visibleBoundsHeight = data.visibleBoundsHeight;
        Vector3f visibleBoundsOffset = new Vector3f(data.visibleBoundsOffset);
        String formatVersion = (String) context.getData("format_version");
        boolean legacy = (Boolean) context.getData("legacy");

        context.setData("material", TextureLayer.bindRoot(context, textureWidth, textureHeight));

        BEModelData modelData = new BEModelData(
                identifier, formatVersion,
                textureWidth, textureHeight,
                visibleBoundsWidth, visibleBoundsHeight,
                visibleBoundsOffset, legacy, components.isEmpty() ? null : components);

        context.setData("transform_map", new HashMap<String, Pair<Transform, Transform>>());

        java.util.List<JsonObject> bonesJson = definition.bones;
        if (!components.isEmpty()) {
            java.util.Set<String> nativeNames = new java.util.HashSet<>();
            for (JsonObject bone : bonesJson) nativeNames.add(bone.get("name").getAsString());
            components.addMissingBones(context.getLoader().getBones(), nativeNames);
        }
        for (JsonObject bone : bonesJson) {
            addChildProcess(bone, "bone_layer");
        }
        context.getLoader().setData(modelData);
    }

    @Override
    public void postProcess(JsonObject input, Context context) {
        context.getLoader().build(null);
        HashMap<String, Vector3f> map = (HashMap<String, Vector3f>) context.getData("pivot_map");
        map.clear();
        HashMap<String, Pair<Transform, Transform>> transformMap = (HashMap<String, Pair<Transform, Transform>>) context.getData("transform_map");
        transformMap.clear();
    }
    private static final class Input {
        Description description;
        java.util.List<JsonObject> bones;
    }
    private static final class Description {
        @DefaultOnMismatch String identifier = "";
        @DefaultOnMismatch Float textureWidth = 16f, textureHeight = 16f, visibleBoundsWidth = 3f, visibleBoundsHeight = 3f;
        @DefaultOnMismatch Vector3f visibleBoundsOffset = new Vector3f();
    }
}

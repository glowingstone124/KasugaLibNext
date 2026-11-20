package lib.kasuga.rendering.models.mc.typo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.NativeModelTextures;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.TextureLayer;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be.BEModelLoader;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je.JEModelLoader;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je.JETextureLayer;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.Optional;

/** Opens Java Edition block geometry and Bedrock geometry from client resource packs. */
public final class KsgMinecraftJsonLoader {
    private KsgMinecraftJsonLoader() {}

    public static RenderPearlModelSource openJava(Identifier identifier) throws IOException {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        NativeModelTextures textures = new NativeModelTextures(resources);
        JEModelLoader loader = new JEModelLoader("resource_java");
        loader.registerProcessor("texture_layer", new JETextureLayer(textures));
        loader.setModelSourceManager(new SourceManager<>(new SourceType("java_json"), "resource_java") {
            @Override public Optional<JsonObject> load(Object source) throws IOException {
                Identifier path = javaModelPath((Identifier) source);
                if (resources.getResource(path).isEmpty()) return Optional.empty();
                return Optional.of(read(resources, path));
            }
        });
        Identifier location = javaModelPath(identifier);
        Model model = loader.load(location, read(resources, location)).get(location);
        return open(model, identifier);
    }

    public static RenderPearlModelSource openBedrock(Identifier identifier) throws IOException {
        return openBedrock(identifier, null, null);
    }

    /** When supplied, the geometry name selects one entry and the texture is a complete resource path. */
    public static RenderPearlModelSource openBedrock(Identifier identifier, @Nullable String geometryName,
                                                   @Nullable Identifier texture) throws IOException {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        NativeModelTextures textures = new NativeModelTextures(resources);
        BEModelLoader loader = new BEModelLoader("resource_bedrock", identifier.getNamespace());
        loader.registerProcessor("texture_layer", new TextureLayer(textures, texture));
        JsonObject input = read(resources, identifier);
        selectGeometry(input, geometryName);
        return open(loader.load(identifier, input).get(identifier), identifier);
    }

    private static RenderPearlModelSource open(@Nullable Model model, Identifier location) throws IOException {
        if (model == null || !model.isRenderable()) throw new IOException("No polygons in model " + location);
        return RenderPearlModelSource.open(model, NativeModelTextures::location, texture -> null);
    }

    private static JsonObject read(ResourceManager resources, Identifier identifier) throws IOException {
        try (Reader reader = resources.openAsReader(identifier)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Identifier javaModelPath(Identifier name) {
        String path = name.getPath();
        if (!path.startsWith("models/")) path = "models/" + path;
        if (!path.endsWith(".json")) path += ".json";
        return name.withPath(path);
    }

    private static void selectGeometry(JsonObject input, @Nullable String name) throws IOException {
        JsonElement geometry = input.get("minecraft:geometry");
        if (geometry instanceof JsonArray entries) {
            for (JsonElement entry : entries) {
                if (!(entry instanceof JsonObject model)) continue;
                if (name != null && !name.equals(geometryName(model))) continue;
                JsonArray selected = new JsonArray();
                selected.add(model);
                input.add("minecraft:geometry", selected);
                return;
            }
            throw new IOException("Bedrock geometry not found: " + name);
        }

        for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
            if (!entry.getKey().startsWith("geometry.") || !(entry.getValue() instanceof JsonObject legacy)) continue;
            if (name != null && !name.equals(entry.getKey()) && !name.equals(geometryName(legacy))) continue;
            if (!legacy.has("description")) {
                JsonObject description = new JsonObject();
                description.addProperty("identifier", entry.getKey());
                description.add("texture_width", legacy.get("texturewidth"));
                description.add("texture_height", legacy.get("textureheight"));
                legacy.add("description", description);
            }
            input.add("geometry.model", legacy);
            return;
        }
        throw new IOException("Bedrock geometry not found: " + name);
    }

    private static @Nullable String geometryName(JsonObject model) {
        if (!(model.get("description") instanceof JsonObject description)) return null;
        JsonElement id = description.get("identifier");
        return id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString() ? id.getAsString() : null;
    }
}

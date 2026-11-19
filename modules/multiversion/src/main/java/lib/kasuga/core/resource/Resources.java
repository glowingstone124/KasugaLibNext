package lib.kasuga.core.resource;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;

import java.io.IOException;
import java.util.*;

public class Resources {


    /**
     * Get resources with their path.
     * @param location The location of file or folder.
     * @param isFile is the location pointing at a file or a folder?
     * @return the map of resource path and the resource.
     * @throws IOException if the file/folder don't exist, throw this.
     */
    public static Map<String, Resource> getResources(Identifier location, boolean isFile) throws IOException {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        return innerGetResources(rm, location, isFile, false);
    }

    public static Resource getResource(Identifier location) throws IOException {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        return rm.getResourceOrThrow(location);
    }

    private static Map<String, net.minecraft.server.packs.resources.Resource> innerGetResources(ResourceManager rm, Identifier location, boolean isFile, boolean fullyPath) throws IOException {
        if(isFile) {
            return Map.of(location.getPath(), rm.getResourceOrThrow(location));
        }
        Map<Identifier, net.minecraft.server.packs.resources.Resource> resources = rm.listResources(location.getPath(),
                l -> l.getNamespace().equals(location.getNamespace()));
        HashMap<String, net.minecraft.server.packs.resources.Resource> result = new HashMap<>();
        for(Identifier location1 : resources.keySet()) {
            if(fullyPath) {
                if (location1.getPath().contains("."))
                    result.put(location1.getPath(), resources.get(location1));
            } else {
                if (location1.getPath().contains("."))
                    result.put(location1.getPath().replaceAll(location.getPath() + "/", ""),
                            resources.get(location1));
            }
        }
        return result;
    }

}

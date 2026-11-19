package lib.kasuga.inject.class_loader;

import net.neoforged.fml.ModContainer;

import java.nio.file.Path;

public class ForgePathFinder implements IPathFinder {

    private final ModContainer modContainer;

    public ForgePathFinder(ModContainer modContainer) {
        this.modContainer = modContainer;
    }
    @Override
    public Path find(String path) {
        return modContainer.getModInfo().getOwningFile().getFile().findResource(path);
    }
}

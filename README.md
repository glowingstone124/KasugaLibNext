
Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources:
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/

Custom rendering:
==========
The custom rendering surface includes owner-scoped world pipelines, managed effects,
post-processing graphs, reload-safe resource/generated shaders, the imperative Java Shader DSL,
background shader preparation and schema-driven runtime parameters.

See [doc/reference/effect-rendering.md](doc/reference/effect-rendering.md) for the API behavior, examples and the GLSL-to-Java
Shader DSL guide.

The optional `modules:shader-graal` module adds a GraalJS frontend that constructs the same typed
shader programs. See [GraalJS scripting](doc/reference/shader-dsl.en.md#graaljs-scripting) for dependencies and examples.

Camera movement, rotation, zoom and keyframe playback are documented in
[doc/reference/camera-animation.md](doc/reference/camera-animation.md). Player / Free / Fixed creation and entity following are documented in
[doc/reference/cameras.md](doc/reference/cameras.md).

World-space UI panels and transparent free-cursor input are documented in
[doc/reference/world-ui-input.md](doc/reference/world-ui-input.md).

Optional world-wide volumetric skies, placeable cumulonimbus volumes and standalone previews are documented in
[doc/reference/volumetric-clouds.md](doc/reference/volumetric-clouds.md).

The [Nebula demo](doc/guides/nebula-demo.md) combines graph-managed MRT, OBJ/PBR assets,
volumetric warp effects and shared camera animation, with deterministic 4K60 recording.

## Minecraft build targets

The default target builds the Minecraft 1.21.1 library with Java 21. Minecraft 26.3 uses
Java 25 and NeoForge 26.3.0.109.

```bash
./gradlew :modules:library:assemble
./gradlew -PkasugaMinecraft=26.3 :modules:multiversion:assemble
```

The 26.3 distributable is the `-all.jar` in `modules/multiversion/build/26.3/libs`.
It includes the Micronaut startup and injection APIs, VFS, portable resource lifecycle,
and the RenderPearl world model adapter. Mount a `ModelInstance` in the current client
level, supply its world position and a texture-to-`Identifier` mapping, and update
the mount's placement as needed. Close the mount when it should leave the world.
The model and textures remain caller-owned.

A 26.3 class injected through `Saved<T>` declares a public static
`SavedDataType<T> TYPE`. Its codec reads the payload of the corresponding 1.21.1
save when the namespaced 26.3 save is absent. Existing manual registrations that used
a custom filename can pass that name to `Saved.Impl`. The `configureCommon` and
`configureServer` callbacks register NeoForge local and synced configs with their
existing instance-level filenames. NeoForge 26.3 reads per-world synced overrides
from `syncedconfig`.

Model rendering uses Minecraft entity pipelines on the active graphics backend
and supports CPU skinning. The remaining Minecraft registry, resource pack, UI
and custom rendering APIs are being ported. Integration modules whose upstream
mods still target earlier Minecraft releases stay in the 1.21.1 build.

The 26.3 development client can check both graphics backends using an isolated test
world and framebuffer readback. The test selects and checks the requested backend:

```bash
./gradlew -PkasugaMinecraft=26.3 -PkasugaGraphicsSmoke=vulkan :modules:multiversion:runClient
```

Set `-PkasugaGraphicsSmoke=opengl` to test OpenGL. A passing
run logs `KASUGA_RENDERPEARL_PASS` and saves `kasuga-renderpearl-smoke.png` in
the test game directory.

Run the focused release gate with:

```bash
./gradlew renderingReleaseCheck
```

The manual and artifact checks for a release candidate are recorded in
[API validation guide](doc/guides/verification.md).


Documentation:
==========

- [API reference index](doc/reference/README.md): interfaces, builders, configuration schemas, examples and commands.
- [Architecture and principles](doc/explanation/README.md): data flow, ownership, algorithms and implementation boundaries.
- [Proxy kernel and content integration](doc/explanation/proxy/README.en.md): sessions, typed ports, lifecycle, and a practical integration guide.
- [Skeleton physics, IK and diamond constraints](doc/reference/skeleton-dynamics.md).
- [Model loading and rendering](doc/reference/modelling/README.en.md) / [中文](doc/reference/modelling/README.md).
- [Formula API](doc/reference/formula/README.en.md) / [中文](doc/reference/formula/README.md).

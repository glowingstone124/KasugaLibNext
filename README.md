
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
It includes the Micronaut startup and injection APIs, VFS, resource pack scopes and
reload events, and the RenderPearl world model adapter. Mount a `ModelInstance` in the current client
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

Approved VFS resource layers load in their configured priority order under the
`kasuga/approved` pack. Kasuga's client and server reload events run after the
host publishes the reloaded resource manager.

The 26.3 target includes the generated Minecraft registration API and the
data-driven registration and reload pipeline. Use `KasugaLibRegistry.getRegistryOf(modId)`
to create a registry on the mod event bus. Minecraft-facing methods use
`Identifier`, and block and item factories receive properties with the resolved
registry key. The same data-driven manifests serve both Minecraft versions.
Registration reads `on_register` from the owning mod, and resource reload reads
`on_reload` from the active pack stack.
The generated `Rpc.wrap` API uses the corresponding NeoForge client and server
packet distributors on both Minecraft targets. One-way calls and calls with a
response share the existing RPC codecs and session handling.

Model rendering uses Minecraft entity pipelines on the active graphics backend
and supports CPU skinning. Point, area, and volumetric lights use RenderPearl
with the same lighting shader calculations as 1.21.1. Nested world scenes can
own model and light nodes. Native Blockbench models use their authored Locator
lights and the same evaluated bone pose as their geometry. See the
[world-space light guide](doc/guides/world-lights.md).

The `fsm_block` and `fsm_be` data-driven factories can bind `.bbmodel`, `.gltf`,
`.glb`, `.obj`, Java block model JSON and Bedrock `.geo.json` to a block entity.
The server runs the state machine and
synchronizes transitions to the clients, which sample the bone pose through
RenderPearl. Standalone glTF models can also be opened with `KsgGltfLoader.open`.
Relative glTF buffer and texture references resolve from the model's resource
pack namespace. While an integrated server is running, its data pack stack
provides the shared FSM definitions and clips to both sides.
Open a standalone OBJ scene node with `KsgObjLoader.open`. It resolves sibling
MTL files and diffuse textures from resource packs, including texture cutouts
and MTL dissolve.
Use `RenderPearlModelSource.open` for a standalone scene node backed by any of
these resource formats. Java block models resolve parent files and per-face
texture aliases. Bedrock uses its first geometry by default.
`KsgMinecraftJsonLoader.openBedrock` also accepts a geometry name and a texture
override for files shared by multiple entities.

Document items register typed components with `DocumentComponentReg`. Their
first-person renderers submit page geometry and text through
`SubmitNodeCollector` in 26.3, using the same one- and two-handed page layouts
as 1.21.1.

`GlobalDeviceReg` and `DeviceAttributeReg` register global device types and
attributes. On the first 26.3 load, the global device manager imports
per-device files under the world's `devices/` directory into the overworld
saved data `kasuga_lib:devices`. Records with no type ID are preserved, and
the original files remain available.

Vanilla 26.3 screens can display the same resource models using
`GuiModelPreview` and `ModelPreviewWidget`. See the
[UI model preview reference](doc/reference/ui-model-preview.en.md#minecraft-263).
Structure previews, world UI, and other custom rendering APIs are being ported.
Integration modules whose upstream mods still target earlier Minecraft releases
stay in the 1.21.1 build.

The 26.3 development client can check both graphics backends using an isolated test
world and framebuffer readback. The test selects and checks the requested backend:

```bash
./gradlew -PkasugaMinecraft=26.3 -PkasugaGraphicsSmoke=vulkan :modules:multiversion:runClient
```

Set `-PkasugaGraphicsSmoke=opengl` to test OpenGL. A passing run logs
`KASUGA_RENDERPEARL_PASS`, `KASUGA_WORLD_LIGHTS_PASS`,
`KASUGA_SCENE_LIGHTS_PASS`, `KASUGA_BLOCKBENCH_LIGHT_PASS`,
`KASUGA_REGISTRATION_PASS`, `KASUGA_FSM_PASS`, `KASUGA_OBJ_PASS`,
`KASUGA_DEVICE_PASS`, `KASUGA_DOCUMENT_PASS`, `KASUGA_RPC_PASS`,
`KASUGA_NATIVE_JSON_PASS`, and `KASUGA_GUI_PREVIEW_PASS`.
Captures of the models, animation, lighting, held documents, and GUI previews
are saved in the test game directory.

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

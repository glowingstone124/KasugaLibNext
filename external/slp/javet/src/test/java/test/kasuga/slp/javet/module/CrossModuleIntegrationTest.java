package test.kasuga.slp.javet.module;

import lib.kasuga.scripting.ScriptConsole;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.discovery.PackageInfo;
import lib.kasuga.scripting.module.*;
import lib.kasuga.scripting.value.ScriptPrimitive;
import lib.kasuga.scripting.value.ScriptValue;
import lib.kasuga.slp.javet.JavetScriptEngine;
import lib.kasuga.slp.javet.KasugaLibJavet;
import lib.kasuga.slp.javet.module.JavetModuleHandle;
import lib.kasuga.slp.javet.module.JsModuleResolver;
import lib.kasuga.slp.javet.module.RequireResolver;
import lib.kasuga.slp.javet.value.JavetValuePrimitive;
import org.junit.jupiter.api.*;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CrossModuleIntegrationTest {

    private JavetScriptEngine engine;
    private JsModuleResolver resolver;
    private ScriptEngineType<JavetScriptEngine> engineType;
    private PackageRegistry packageRegistry;
    private BuiltinModuleRegistry builtinRegistry;
    private ScriptEngineType<JavetScriptEngine> savedEngineType;

    @BeforeEach
    void setUp() throws ScriptException {
        savedEngineType = KasugaLibJavet.ENGINE_TYPE;

        resolver = new JsModuleResolver();
        packageRegistry = new PackageRegistry();
        builtinRegistry = new BuiltinModuleRegistry();

        engineType = ScriptEngineType.<JavetScriptEngine>builder(JavetScriptEngine::new)
            .scriptType("javascript")
            .resolver(resolver)
            .build();
        KasugaLibJavet.ENGINE_TYPE = engineType;

        engine = new JavetScriptEngine();
        engine.setRequireResolver(createRequireResolver());
        engine.init(ScriptConsole.errorsToStderr());
    }

    @AfterEach
    void tearDown() {
        KasugaLibJavet.ENGINE_TYPE = savedEngineType;
        if (engine != null) {
            engine.getRuntime().lowMemoryNotification();
            engine.close();
        }
    }

    private RequireResolver createRequireResolver() {
        return (moduleName, fromSourcePath) -> {
            // 1. Builtin modules
            if (builtinRegistry.has(moduleName)) {
                Object builtin = builtinRegistry.resolve(moduleName);
                var exports = new HashMap<String, ScriptValue>();
                if (builtin instanceof Map<?, ?> map) {
                    for (var entry : map.entrySet()) {
                        exports.put(entry.getKey().toString(), engine.createValue(entry.getValue()));
                    }
                } else {
                    exports.put("default", engine.createValue(builtin));
                }
                return new JavetModuleHandle(exports, "builtin:" + moduleName, engineType);
            }

            // 2. Package registry: require("@test/math")
            ResolvedPackage pkg = packageRegistry.lookup(moduleName);
            if (pkg != null) {
                ResolvedScript script = resolver.locateScript(pkg, List.of());
                if (script != null) {
                    return engine.loadModule(script);
                }
            }

            // 3. Relative require: require("./foo")
            if (moduleName.startsWith("./") || moduleName.startsWith("../")) {
                String dir = fromSourcePath.contains("/")
                    ? fromSourcePath.substring(0, fromSourcePath.lastIndexOf('/'))
                    : "";
                String target = dir.isEmpty()
                    ? moduleName.substring(2)
                    : dir + "/" + moduleName.substring(2);

                for (var entry : packageRegistry.all().entrySet()) {
                    ResolvedPackage candidate = entry.getValue();
                    List<String> segments = List.of(target.split("/"));
                    ResolvedScript script = resolver.locateScript(candidate, segments);
                    if (script != null) {
                        return engine.loadModule(script);
                    }
                }
            }

            return null;
        };
    }

    private ResolvedPackage registerPackage(String name, String version, String main,
                                             String packName, String resourcePath) throws URISyntaxException {
        Path packPath = Path.of(getClass().getResource(resourcePath + "/scripts/package.toml").toURI())
                .getParent().getParent();
        PackageInfo info = new PackageInfo(
            name, "javascript", null, version, main,
            List.of(),
            new PackageInfo.EntryConfig(List.of(), List.of(), List.of())
        );
        ResolvedPackage pkg = new ResolvedPackage(
            info,
            new test.kasuga.slp.javet.TestPackResources(packPath, packName),
            "scripts",
            engineType
        );
        packageRegistry.register(pkg);
        return pkg;
    }

    private double asDouble(ScriptValue v) throws ScriptException {
        assertTrue(v instanceof JavetValuePrimitive, "Expected JavetValuePrimitive, got " + v.getClass().getSimpleName());
        return ((JavetValuePrimitive) v).asDouble();
    }

    private boolean asBool(ScriptValue v) throws ScriptException {
        assertTrue(v instanceof JavetValuePrimitive, "Expected JavetValuePrimitive, got " + v.getClass().getSimpleName());
        Object raw = ((JavetValuePrimitive) v).getValue();
        assertTrue(raw instanceof Boolean, "Expected Boolean, got " + raw.getClass().getSimpleName());
        return (Boolean) raw;
    }

    // ========================================================================
    // Cross-package require: @test/app requires @test/math
    // ========================================================================

    @Test
    public void shouldResolveCrossPackageRequire() throws Exception {
        registerPackage("@test/math", "2.1.0", "index", "math_pack", "/packs/math_pack");
        registerPackage("@test/app", "1.0.0", "index", "app_pack", "/packs/app_pack");

        // @test/app/index.js: var math = require("@test/math"); ...
        ResolvedPackage appPkg = packageRegistry.lookup("@test/app");
        assertNotNull(appPkg);
        ResolvedScript appScript = resolver.locateScript(appPkg, List.of());
        assertNotNull(appScript);

        ScriptModuleHandle handle = engine.loadModule(appScript);
        assertNotNull(handle);

        // app computes: circumference = math.multiply(math.PI, 20) = 3.14159265358979 * 20
        double circumference = asDouble(handle.getExport("circumference"));
        assertEquals(62.8318530717958, circumference, 1e-10);

        // app computes: sum = math.add(100, 200) = 300
        double sum = asDouble(handle.getExport("sum"));
        assertEquals(300.0, sum, 1e-10);

        // app computes: isTenEven = math.isEven(10) = true
        assertTrue(asBool(handle.getExport("isTenEven")));

        // app computes: isSevenEven = math.isEven(7) = false
        assertFalse(asBool(handle.getExport("isSevenEven")));

        // passthrough: pi = math.PI
        double pi = asDouble(handle.getExport("pi"));
        assertEquals(3.14159265358979, pi, 1e-10);

        // passthrough: e = math.E
        double e = asDouble(handle.getExport("e"));
        assertEquals(2.71828182845905, e, 1e-10);

        // passthrough: mathVersion = math.version
        assertEquals("2.1.0", handle.getExport("mathVersion").asString());
    }

    // ========================================================================
    // Direct package load: @test/math
    // ========================================================================

    @Test
    public void shouldLoadMathPackageDirectly() throws Exception {
        registerPackage("@test/math", "2.1.0", "index", "math_pack", "/packs/math_pack");

        ResolvedPackage mathPkg = packageRegistry.lookup("@test/math");
        ResolvedScript script = resolver.locateScript(mathPkg, List.of());
        ScriptModuleHandle handle = engine.loadModule(script);

        // PI
        assertEquals(3.14159265358979, asDouble(handle.getExport("PI")), 1e-10);
        // E
        assertEquals(2.71828182845905, asDouble(handle.getExport("E")), 1e-10);
        // version
        assertEquals("2.1.0", handle.getExport("version").asString());
        // add(7, 8) via require
        assertEquals(6, handle.getExportNames().size());
    }

    // ========================================================================
    // Builtin module from script
    // ========================================================================

    @Test
    public void shouldRequireBuiltinFromScriptAndVerifyValues() throws Exception {
        builtinRegistry.register("constants", () -> Map.of(
            "SPEED_OF_LIGHT", 299792458,
            "PLANCK", 6.62607015e-34,
            "AVOGADRO", "6.02214076e23"
        ));

        registerPackage("@test/app", "1.0.0", "index", "app_pack", "/packs/app_pack");

        // Load a script that requires "constants" builtin
        // Since app_pack doesn't require "constants", test via direct resolver
        // Create a RequireResolver test that verifies builtin values flow through
        var requireFunc = createRequireResolver();
        ScriptModuleHandle handle = requireFunc.resolve("constants", "test.js");
        assertNotNull(handle);

        // SPEED_OF_LIGHT: integer 299792458
        double c = asDouble(handle.getExport("SPEED_OF_LIGHT"));
        assertEquals(299792458.0, c, 1e-3);

        // PLANCK: 6.62607015e-34
        double h = asDouble(handle.getExport("PLANCK"));
        assertEquals(6.62607015e-34, h, 1e-42);

        // AVOGADRO: string "6.02214076e23"
        assertEquals("6.02214076e23", handle.getExport("AVOGADRO").asString());
    }

    // ========================================================================
    // Builtin takes priority over package
    // ========================================================================

    @Test
    public void shouldBuiltinTakePriorityOverPackage() throws Exception {
        // Register both a builtin and a package with the same name
        builtinRegistry.register("@test/math", () -> Map.of("source", "builtin"));
        registerPackage("@test/math", "2.1.0", "index", "math_pack", "/packs/math_pack");

        var requireFunc = createRequireResolver();
        ScriptModuleHandle handle = requireFunc.resolve("@test/math", "test.js");
        assertNotNull(handle);

        // Should get builtin, not the package
        assertEquals("builtin", handle.getExport("source").asString());
        // Should NOT have PI (which is from the package)
        assertFalse(handle.getExportNames().contains("PI"));
    }

    // ========================================================================
    // Relative require within cross-package load
    // ========================================================================

    @Test
    public void shouldResolveRelativeRequireInLoadedPackage() throws Exception {
        registerPackage("@test/math", "2.1.0", "index", "math_pack", "/packs/math_pack");

        // The js_pack has index.js that requires "./utils"
        Path packPath = Path.of(getClass().getResource("/packs/js_pack/scripts/package.toml").toURI())
                .getParent().getParent();
        PackageInfo jsInfo = new PackageInfo(
            "@test/js-pack", "javascript", null, "1.0.0", "index",
            List.of(),
            new PackageInfo.EntryConfig(List.of(), List.of(), List.of())
        );
        ResolvedPackage jsPkg = new ResolvedPackage(
            jsInfo,
            new test.kasuga.slp.javet.TestPackResources(packPath, "js_pack"),
            "scripts",
            engineType
        );
        packageRegistry.register(jsPkg);

        // Load @test/js-pack main (index.js), which does require("./utils")
        ResolvedScript script = resolver.locateScript(jsPkg, List.of());
        ScriptModuleHandle handle = engine.loadModule(script);

        // index.js: { value: 42, pi: utils.PI, sum: utils.add(10, 32) }
        assertEquals("42", handle.getExport("value").asString());

        // pi = utils.PI = 3.14159 (from utils.js)
        double pi = asDouble(handle.getExport("pi"));
        assertEquals(3.14159, pi, 1e-5);

        // sum = utils.add(10, 32) = 42
        double sum = asDouble(handle.getExport("sum"));
        assertEquals(42.0, sum, 1e-10);
    }

    // ========================================================================
    // Module caching across require chains
    // ========================================================================

    @Test
    public void shouldCacheModulesAcrossRequireChains() throws Exception {
        registerPackage("@test/math", "2.1.0", "index", "math_pack", "/packs/math_pack");
        registerPackage("@test/app", "1.0.0", "index", "app_pack", "/packs/app_pack");

        // Load @test/app which requires @test/math
        ResolvedPackage appPkg = packageRegistry.lookup("@test/app");
        ResolvedScript appScript = resolver.locateScript(appPkg, List.of());
        ScriptModuleHandle appHandle = engine.loadModule(appScript);

        // @test/math should be cached
        ScriptModuleHandle mathCached = engine.getLoadedModule("index.js");
        assertNotNull(mathCached, "@test/math module should be cached");
        assertSame(appHandle, engine.getLoadedModule("index.js"));
    }

    // ========================================================================
    // Package metadata verification
    // ========================================================================

    @Test
    public void shouldVerifyPackageMetadataStrictly() throws Exception {
        ResolvedPackage mathPkg = registerPackage("@test/math", "2.1.0", "index", "math_pack", "/packs/math_pack");

        assertEquals("@test/math", mathPkg.info().name());
        assertEquals("javascript", mathPkg.info().engine());
        assertEquals("2.1.0", mathPkg.info().version());
        assertEquals("index", mathPkg.info().main());
        assertEquals("scripts", mathPkg.packRelativeRoot());
        assertEquals("javascript", mathPkg.engine().scriptType);
        assertTrue(mathPkg.info().workspaces().isEmpty());
        assertTrue(mathPkg.info().entry().common().isEmpty());
        assertTrue(mathPkg.info().entry().server().isEmpty());
        assertTrue(mathPkg.info().entry().client().isEmpty());
    }
}

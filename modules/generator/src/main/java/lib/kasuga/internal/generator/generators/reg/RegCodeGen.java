package lib.kasuga.internal.generator.generators.reg;

import com.github.javaparser.JavaParser;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.utils.Pair;
import com.google.common.base.CaseFormat;
import javassist.compiler.ast.NewExpr;
import lib.kasuga.internal.generator.CodeGenerator;
import lib.kasuga.internal.generator.SourceCodeWriter;
import lib.kasuga.internal.generator.annotations.Prototype;
import lib.kasuga.internal.generator.annotations.RegGenerator;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RegCodeGen implements CodeGenerator {
    @Override
    public CompilationUnit generate(CompilationUnit source, SourceCodeWriter writer) {
        HashMap<String, CompilationUnit> units = new HashMap<>();
        source.walk(AnnotationExpr.class, annotationExpr -> {
            try{
                ResolvedAnnotationDeclaration annotationDeclaration = annotationExpr.resolve();
                onAnnotation(annotationExpr, annotationDeclaration, source, (n)->{
                    return getConf(source, n, units);
                });
            }catch (UnsolvedSymbolException e){
                System.err.println("[RegCodeGen] Failed to resolve annotation during generation phase:");
                e.printStackTrace();
            }
        });

        if(units.containsKey("Modifiers")) {
            source.walk(MethodCallExpr.class, methodCallExpr -> {
                if(
                        methodCallExpr.getScope().filter(Expression::isNameExpr).map(Expression::asNameExpr).map(NameExpr::getNameAsString).orElse("").equals("RegFacade") &&
                                methodCallExpr.getNameAsString().equals("transformObject")
                ) {
                    methodCallExpr.setScope(new ThisExpr());
                    methodCallExpr.setName("transform");
                    String name = methodCallExpr.getArgument(0).asStringLiteralExpr().getValue();
                    methodCallExpr.setArgument(0,
                            new FieldAccessExpr(
                                    new NameExpr(units.get("Modifiers").getPrimaryTypeName().orElseThrow()),
                                    "TYPE_" + CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name)
                            )
                    );
                }
            });
        }

        if(units.containsKey("Configurations")) {
            ClassOrInterfaceDeclaration declaration = units.get("Configurations").getPrimaryType().orElseThrow().asClassOrInterfaceDeclaration();
            ClassOrInterfaceDeclaration adapterType = new ClassOrInterfaceDeclaration(NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier()), false, "Adapter");

            adapterType.addExtendedType("lib.kasuga.registration.core.ConfigureAdapter<Adapter>");
            adapterType.addImplementedType(new ClassOrInterfaceType(null, new SimpleName(declaration.getNameAsString()), NodeList.nodeList(new ClassOrInterfaceType(null, "Adapter"))));

            MethodDeclaration adapterAdoptMethod = new MethodDeclaration(NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier()), new ClassOrInterfaceType(null, "Adapter"), "toAdapter");

            adapterAdoptMethod.addParameter(StaticJavaParser.parseType("IModifierConfigure<?>"), "configure");

            adapterType.addConstructor().addParameter(StaticJavaParser.parseType("IModifierConfigure<?>"), "configure").setBody(new BlockStmt(NodeList.nodeList(StaticJavaParser.parseStatement("super(configure);"))));

            adapterAdoptMethod.setBody(new BlockStmt(NodeList.nodeList(StaticJavaParser.parseStatement("return new Adapter(configure);"))));

            declaration.getMembers().addLast(adapterType);
            declaration.getMembers().addLast(adapterAdoptMethod);

            units.get("Configurations").addImport(Consumer.class);

            declaration.getMembers().addLast(StaticJavaParser.parseMethodDeclaration("public static Consumer<IModifierConfigure<?>> adopt(Consumer<Adapter> transformed) { return (u)->transformed.accept(toAdapter(u)); }"));
        }

        units.forEach((k, v)->writer.write(Path.of(v.getStorage().orElseThrow().getFileName()), v));
        return source;
    }

    private CompilationUnit getConf(CompilationUnit source, String n, HashMap<String, CompilationUnit> units) {
        if(units.containsKey(n)) {
            return units.get(n);
        }
        units.put(n, generateConf(source, n, units));
        return units.get(n);
    }

    private CompilationUnit generateConf(CompilationUnit source, String n, HashMap<String, CompilationUnit> units) {
        String name = source.getPrimaryTypeName().orElseThrow() + n;
        boolean isConfiguration = Objects.equals(n, "ChildrenConfigurations") || Objects.equals(n, "Configurations");
        ClassOrInterfaceDeclaration declaration = new ClassOrInterfaceDeclaration(
                NodeList.nodeList(Modifier.publicModifier()),
                NodeList.nodeList(),
                isConfiguration,
                new SimpleName(name),
                NodeList.nodeList(
                        new TypeParameter("S")
                ),
                NodeList.nodeList(),
                NodeList.nodeList(),
                NodeList.nodeList(),
                NodeList.nodeList()
        );
        CompilationUnit unit = new CompilationUnit(source.getPackageDeclaration().orElseThrow().clone(), cloneList(source.getImports()), NodeList.nodeList(
                declaration
        ), null);
        if(source.containsData(Node.SYMBOL_RESOLVER_KEY)) {
            unit.setData(Node.SYMBOL_RESOLVER_KEY, source.getSymbolResolver());
        }

        unit.setStorage(source.getStorage().map(CompilationUnit.Storage::getPath).map(s->s.getParent().resolve(Path.of(name + ".java"))).orElse(Path.of(name + ".java")));

        if(isConfiguration) {
            declaration.setExtendedTypes(getNodeExtend(source, n, unit, units));
            source.getPrimaryType().orElseThrow()
                    .asClassOrInterfaceDeclaration()
                    .addImplementedType(new ClassOrInterfaceType(null, new SimpleName(name), new NodeList<>(toClassOrInterfaceType(source.getPrimaryType().get()))));
        }

        return unit;
    }

    @SuppressWarnings("unchecked")
    private <T extends Node> NodeList<T> cloneList(NodeList<T> imports) {
        return new NodeList<T>((List<T>)imports.stream().map(T::clone).toList());
    }

    private NodeList<ClassOrInterfaceType> getNodeExtend(CompilationUnit source, String n, CompilationUnit unit, HashMap<String, CompilationUnit> units) {
        NodeList<ClassOrInterfaceType> nodes = new NodeList<>();
        switch (n) {
            case "ChildrenConfigurations":
                unit.addImport("lib.kasuga.registration.core.IChildrenConfiguration");
                nodes.add(new ClassOrInterfaceType(null, new SimpleName("IChildrenConfiguration"), NodeList.nodeList(new TypeParameter("S"))));
                CompilationUnit anotherUnit = getConf(source, "Configurations", units);
                nodes.add(new ClassOrInterfaceType(null, anotherUnit.getPrimaryType().orElseThrow().getName(), NodeList.nodeList(new TypeParameter("S"))));
            case "Configurations":
                unit.addImport("lib.kasuga.registration.core.IModifierConfigure");
                nodes.add(new ClassOrInterfaceType(null, new SimpleName("IModifierConfigure"), NodeList.nodeList(new TypeParameter("S"))));
        }
        return nodes;
    }


    protected static String SELF_REFERENCE = RegGenerator.SelfReference.class.getName().replace("$", ".");
    protected static String MODIFIER = RegGenerator.Modifier.class.getName().replace("$", ".");
    protected static String MODIFY_FUNCTION = RegGenerator.ModifyFunction.class.getName().replace("$", ".");
    protected static String CHILDREN_CONFIGURATION = RegGenerator.ChildrenConfiguration.class.getName().replace("$", ".");

    protected static String MODIFY_APPLIER = RegGenerator.ModifierApplier.class.getCanonicalName();
    protected static String CONFIGURE_MEMBER = RegGenerator.ConfigureMember.class.getCanonicalName();
    protected static String PROTOTYPE = Prototype.class.getCanonicalName();

    protected static DataKey<Boolean> SELF_REFERENCE_PROCESSED = new DataKey<Boolean>() {};

    private void onAnnotation(AnnotationExpr annotationExpr, ResolvedAnnotationDeclaration annotationDeclaration, CompilationUnit source, Function<String, CompilationUnit> function) {
        String annotationName = annotationDeclaration.getQualifiedName();

        if(Objects.equals(annotationName, MODIFIER)) {
            onModifierAnnotation(annotationExpr, annotationDeclaration, source, function);
            return;
        }

        if(Objects.equals(annotationName, MODIFY_FUNCTION)) {
            onModifierFunctionAnnotation(annotationExpr, annotationDeclaration, source, function);
            return;
        }

        if(Objects.equals(annotationName, SELF_REFERENCE)){
            onSelfReferenceAnnotation(annotationExpr, annotationDeclaration, source);
            return;
        }

        if(Objects.equals(annotationName, CONFIGURE_MEMBER)) {
            onConfigureMember(annotationExpr, annotationDeclaration, source, function);
            return;
        }

        if(Objects.equals(annotationName, CHILDREN_CONFIGURATION)) {
            onChildrenConfiguration(annotationExpr, annotationDeclaration, source, function);
        }

        if(Objects.equals(annotationName, PROTOTYPE)) {
            annotationExpr.getParentNode().ifPresent(Node::remove);
        }

    }

    private void onChildrenConfiguration(AnnotationExpr annotationExpr, ResolvedAnnotationDeclaration annotationDeclaration, CompilationUnit source, Function<String, CompilationUnit> function) {
        Node node = annotationExpr;
        while(!(node instanceof MethodDeclaration methodDeclaration)) {
            node = node.getParentNode().orElse(null);
            if(node == null)
                return;
        }
        annotationExpr.remove();
        methodDeclaration.remove();
        function.apply("ChildrenConfigurations").getPrimaryType().orElseThrow().addMember(methodDeclaration);
        methodDeclaration.setDefault(true);
        methodDeclaration.setFinal(false);
        methodDeclaration.setType(new TypeParameter("S"));

        methodDeclaration.getBody().orElseThrow().walk(ReturnStmt.class, r->{
            Node p = r;
            while(!(p instanceof MethodDeclaration declaration)) {
                p = p.getParentNode().orElse(null);
                if(p == null)
                    return;
                if(p instanceof LambdaExpr)
                    return;
            }
            if(declaration != methodDeclaration)
                return;
            MethodCallExpr expr = new MethodCallExpr("addChild");
            expr.setArguments(NodeList.nodeList(r.getExpression().orElseThrow()));
            r.setExpression(expr);
        });
    }

    private void onConfigureMember(AnnotationExpr annotationExpr, ResolvedAnnotationDeclaration annotationDeclaration, CompilationUnit source, Function<String, CompilationUnit> function) {
        Node node = annotationExpr;
        while(!(node instanceof MethodDeclaration methodDeclaration)) {
            node = node.getParentNode().orElse(null);
            if(node == null)
                return;
        }
        annotationExpr.remove();
        methodDeclaration.remove();
        function.apply("Configurations").getPrimaryType().orElseThrow().addMember(methodDeclaration);
        methodDeclaration.setDefault(true);
        methodDeclaration.setFinal(false);
        methodDeclaration.setType(new TypeParameter("S"));

        methodDeclaration.getBody().orElseThrow().walk(MethodCallExpr.class, (expr)->{
            if(!Objects.equals(expr.getNameAsString(), "callConfigure")) {
                return;
            }

            NodeList<Expression> args = NodeList.nodeList(expr.getArguments());

            Expression scope = args.removeFirst();
            SimpleName name = new SimpleName(args.removeFirst().asStringLiteralExpr().getValue());

            expr.replace(new MethodCallExpr(
                    scope,
                    name,
                    args
            ));
        });
    }

    private void onModifierAnnotation(AnnotationExpr annotationExpr, ResolvedAnnotationDeclaration annotationDeclaration, CompilationUnit source, Function<String, CompilationUnit> function) {
        String type = null;
        ClassExpr target = null;
        ArrayInitializerExpr enumration = null;
        String prefix = null;
        for (Node childNode : annotationExpr.getChildNodes()) {
            if(!(childNode instanceof MemberValuePair pair))
                continue;
            switch (pair.getNameAsString()) {
                case "type":
                    type = pair.getValue().asStringLiteralExpr().getValue();
                    break;
                case "target":
                    target = pair.getValue().asClassExpr();
                    break;
                case "extendedType":
                    if(!pair.getValue().isStringLiteralExpr())
                        break;
                    String extendedTypeStr = pair.getValue().asStringLiteralExpr().getValue();
                    if(extendedTypeStr.isEmpty())
                        break;
                    Type typeExpr = StaticJavaParser.parseType(extendedTypeStr);
                    if(typeExpr.isClassOrInterfaceType()) {
                        target = new ClassExpr(typeExpr.asClassOrInterfaceType());
                    }
                    break;
                case "enumeration":
                    enumration = pair.getValue().asArrayInitializerExpr();
                    break;
                case "prefix":
                    prefix = pair.getValue().asStringLiteralExpr().getValue();
                    break;
            }
        }
        if(type == null || target == null)
            return;
        CompilationUnit unit = function.apply("Modifiers");
        unit.addImport("lib.kasuga.registration.core.Modifier");
        unit.addImport("lib.kasuga.registration.core.ModifierType");
        String typeName = "TYPE_" + CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, type);
        ClassOrInterfaceDeclaration decl = unit.getPrimaryType().orElseThrow().asClassOrInterfaceDeclaration();
        FieldDeclaration declaration = new FieldDeclaration(
                NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                new VariableDeclarator(
                    new ClassOrInterfaceType(null, new SimpleName("ModifierType"), NodeList.nodeList(target.getType())),
                    new SimpleName(typeName),
                    new ObjectCreationExpr(
                            null,
                            new ClassOrInterfaceType(null, new SimpleName("ModifierType"), NodeList.nodeList(new UnknownType())),
                            NodeList.nodeList()
                    )
                )
        );
        decl.addMember(declaration);

        ClassOrInterfaceDeclaration instanceDeclaration = new ClassOrInterfaceDeclaration(
                NodeList.nodeList(Modifier.publicModifier(), Modifier.abstractModifier(), Modifier.staticModifier()),
                NodeList.nodeList(),
                false,
                new SimpleName(type),
                NodeList.nodeList(),
                NodeList.nodeList(
                        new ClassOrInterfaceType(null, new SimpleName("Modifier"), NodeList.nodeList(target.getType()))
                ),
                NodeList.nodeList(),
                NodeList.nodeList(),
                NodeList.nodeList()
        );

        createModifierType(type, target, typeName, instanceDeclaration, unit);

        unit.addImport("java.lang.Override");

        decl.addMember(instanceDeclaration);

        ResolvedType resolvedType = target.getType().resolve();

        List<ResolvedMethodDeclaration> methods = resolvedType.asReferenceType().getAllMethods();

        List<String> enumerationStr = new ArrayList<>();

        if(enumration == null)
            return;

        for (Expression value : enumration.getValues()) {
            enumerationStr.add(value.asStringLiteralExpr().getValue());
        }

        for (ResolvedMethodDeclaration method : methods) {
            if(enumerationStr.contains(method.getName())) {

                NodeList<Parameter> virtualListParameter = new NodeList<>();
                Set<String> addImports = new HashSet<>();

                NodeList<Expression> callExprParam = new NodeList<>();

                virtualListParameter.add(new Parameter(target.getType(), "original"));

                for (int i = 0; i < method.getNumberOfParams(); i++) {
                    ResolvedParameterDeclaration paramI = method.getParam(i);
                    ResolvedType paramIType = paramI.getType();
                    Type rebuildType = rebuildType(paramIType, addImports::add);
                    virtualListParameter.add(new Parameter(
                            rebuildType,
                            paramI.getName()
                    ));
                    callExprParam.add(new NameExpr(paramI.getName()));
                }

                Type originalReturnType = rebuildType(method.getReturnType(), addImports::add);

                NodeList<Statement> stmt = new NodeList<>();

                MethodCallExpr forwarded = new MethodCallExpr(
                    new NameExpr("original"),
                    method.getName(),
                    callExprParam
                );

                if(Objects.equals(originalReturnType, target.getType())) {
                    stmt.add(new ReturnStmt(forwarded));
                } else {
                    stmt.add(new ExpressionStmt(forwarded));
                    stmt.add(new ReturnStmt(new NameExpr("original")));
                }


                MethodDeclaration virtualMethod = new MethodDeclaration(
                    NodeList.nodeList(Modifier.publicModifier()),
                    prefix == null ? method.getName() : prefix + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, method.getName()),
                    target.getType(),
                    virtualListParameter
                );

                virtualMethod.setBody(new BlockStmt(stmt));

                createModifierInstance(type, virtualMethod, function, addImports, Set.of(), List.of());
            }
        }
    }

    private void createModifierType(String type, ClassExpr target, String typeName, ClassOrInterfaceDeclaration declaration, CompilationUnit unit) {
        MethodDeclaration getTypeMethod = new MethodDeclaration(
                NodeList.nodeList(Modifier.publicModifier()), // public
                new ClassOrInterfaceType(null, "ModifierType").setTypeArguments(new NodeList<>(target.getType())), // ModifierType<BlockBehaviour.Properties>
                "getType"
        );

        // return TYPE_BLOCK_PROPERTIES;
        ReturnStmt getTypeReturn = new ReturnStmt(new NameExpr(typeName));
        getTypeMethod.setBody(new BlockStmt(new NodeList<>(getTypeReturn)));

        // 2. 实现 of(String name, Consumer<[var: target, ClassExpr]> setter) 方法
        MethodDeclaration ofMethod = new MethodDeclaration(
                NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier()), // public static
                toClassOrInterfaceType(declaration), // Transform[var: type, String]Modifier
                "of"
        );

        // of(String name, Consumer<[var: target, ClassExpr]> setter)
        unit.addImport(String.class.getName());
        Parameter nameParam = new Parameter(new ClassOrInterfaceType(null, "String"), "name");

        // Consumer<[var: target, ClassExpr]> setter
        unit.addImport(Function.class.getName());
        ClassOrInterfaceType consumerType = new ClassOrInterfaceType(null, "Function").setTypeArguments(new NodeList<>(target.getType(), target.getType()));
        Parameter setterParam = new Parameter(consumerType, "setter");

        ofMethod.setParameters(new NodeList<>(nameParam, setterParam));


        // 3. 匿名类中的 transform 方法
        MethodDeclaration transformMethod = new MethodDeclaration(
                NodeList.nodeList(Modifier.publicModifier()), // public
                target.getType(), // [var: target, ClassExpr]
                "transform"
        );
        // transformMethod.addAnnotation(new MarkerAnnotationExpr(new Name("Override")));

        // transform([var: target, ClassExpr] originalValue)
        Parameter originalValueParam = new Parameter(NodeList.nodeList(), target.getType(), new SimpleName("originalValue"));
        transformMethod.setParameters(new NodeList<>(originalValueParam));

        ReturnStmt transformReturn = new ReturnStmt(new MethodCallExpr(new NameExpr("setter"), "apply", new NodeList<>(new NameExpr("originalValue"))));
        BlockStmt transformBody = new BlockStmt(new NodeList<>(transformReturn));
        transformMethod.setBody(transformBody);


        // return new SetBlockPropertiesModifier() { ... };
        ObjectCreationExpr objectCreationExpr = new ObjectCreationExpr(null, toClassOrInterfaceType(declaration), toTypeUsage(declaration.getTypeParameters()), NodeList.nodeList(), NodeList.nodeList(transformMethod));
        ReturnStmt ofReturn = new ReturnStmt(objectCreationExpr);
        BlockStmt ofBody = new BlockStmt(new NodeList<>(ofReturn));
        ofMethod.setBody(ofBody);

        declaration.addMember(getTypeMethod);
        declaration.addMember(ofMethod);
    }

    private void onModifierFunctionAnnotation(AnnotationExpr annotationExpr, ResolvedAnnotationDeclaration annotationDeclaration, CompilationUnit source, Function<String, CompilationUnit> function) {

        String type = null;
        List<Type> overrideParameterTypes = new ArrayList<>();
        for (Node childNode : annotationExpr.getChildNodes()) {
            if(!(childNode instanceof MemberValuePair pair))
                continue;
            switch (pair.getNameAsString()) {
                case "type":
                    type = pair.getValue().asStringLiteralExpr().getValue();
                    break;
                case "parameterTypes":
                    StringBuilder builder = new StringBuilder("TypeInference<");
                    boolean i = false;
                    for (Expression value : pair.getValue().asArrayInitializerExpr().getValues()) {
                        if(i) builder.append(",");
                        builder.append(value.asStringLiteralExpr().getValue());
                        i = true;
                    };
                    builder.append(">");
                    overrideParameterTypes.addAll(StaticJavaParser.parseClassOrInterfaceType(builder.toString()).getTypeArguments().orElseThrow());
            }
        }
        if(type == null)
            return;

        Node node = annotationExpr;
        while(!(node instanceof MethodDeclaration methodDeclaration)) {
            node = node.getParentNode().orElse(null);
            if(node == null)
                return;
        }
        Set<String> tAs = collectTypeArgumentNames(methodDeclaration);
        methodDeclaration.remove();
        createModifierInstance(type, methodDeclaration, function, List.of(), tAs, overrideParameterTypes);
    }

    private void createModifierInstance(String type, MethodDeclaration methodDeclaration, Function<String, CompilationUnit> function, Collection<String> newImports, Set<String> typeArguments, List<Type> overrideParameterTypes) {
        CompilationUnit configurationUnit = function.apply("Configurations");
        CompilationUnit modifierUnit = function.apply("Modifiers");

        newImports.forEach(modifierUnit::addImport);
        newImports.forEach(configurationUnit::addImport);

        ClassOrInterfaceDeclaration configurationDecl = configurationUnit.getPrimaryType().orElseThrow().asClassOrInterfaceDeclaration();
        ClassOrInterfaceDeclaration modifierDecl = modifierUnit.getPrimaryType().orElseThrow().asClassOrInterfaceDeclaration();

        String modifierName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, methodDeclaration.getNameAsString());


        if(methodDeclaration.getParameters().size() >= 2) {
            List<String> str = methodDeclaration
                    .getParameters()
                    .stream()
                    .map(Parameter::getType)
                    .map(this::describeType)
                    .collect(Collectors.toList());
            str.removeFirst();
            modifierName += "_BY_" + CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE,
                    String.join("", str)
            );
        }

        Parameter modifyParameter = methodDeclaration.getParameters().removeFirst();
        Type paramType = modifyParameter.getType();
        modifyParameter.setType(new UnknownType());

        Expression newMethodExpr = new MethodCallExpr(
                new NameExpr(type),
                new SimpleName("of"),
                NodeList.nodeList(
                        new StringLiteralExpr(methodDeclaration.getNameAsString()),
                        new LambdaExpr(
                                modifyParameter,
                                methodDeclaration.getBody().orElseThrow()
                        )
                )
        );

        Type newMethodType = new ClassOrInterfaceType(null, new SimpleName("Modifier"), NodeList.nodeList(methodDeclaration.getType()));

        Expression referenceExpr = new FieldAccessExpr(
                new NameExpr(modifierUnit.getPrimaryTypeName().orElseThrow()),
                modifierName
        );

        NodeList<Parameter> copyParam = new NodeList<>();

        if(methodDeclaration.getParameters().isNonEmpty()) {
            if(methodDeclaration.getParameters().size() > 2)
                throw new RuntimeException("Too much arguments for " + methodDeclaration.getNameAsString() + "in" + type + "!");
            modifierUnit.addImport("lib.kasuga.registration.core.ModifierType");
            NodeList<Parameter> parameters = new NodeList<>(methodDeclaration.getParameters());
            NodeList<Type> parameterTypes = new NodeList<>();

            for (int i = 0; i < parameters.size(); i++) {
                Parameter parameter = parameters.get(i);
                Type pT = parameter.getType();
                if (pT.isPrimitiveType())
                    pT = pT.asPrimitiveType().toBoxedType();
                Type fT = pT.clone();
                replaceWildcard(fT, typeArguments);
                if(overrideParameterTypes.size() <= i || overrideParameterTypes.get(i) == null) {
                    parameterTypes.add(fT);
                } else {
                    parameterTypes.add(overrideParameterTypes.get(i));
                }
                copyParam.add(parameter.clone());
                parameter.setType(new UnknownType());
            }

            parameterTypes.add(newMethodType);

            newMethodType = new ClassOrInterfaceType(
                    null,
                    new SimpleName(methodDeclaration.getParameters().size() == 1 ? "Function" : "BiFunction"),
                    parameterTypes
            );

            modifierUnit.addImport(methodDeclaration.getParameters().size() == 1 ? Function.class.getName() : BiFunction.class.getName());

            newMethodExpr = new MethodCallExpr(
                    new NameExpr("ModifierType"),
                    "memoize",
                    NodeList.nodeList(new LambdaExpr(parameters, newMethodExpr))
            );

            referenceExpr =
                    new MethodCallExpr(
                            referenceExpr,
                            "apply",
                            toArguments(copyParam)
                    );
        }

        FieldDeclaration modifierInstanceDecl = new FieldDeclaration(
                NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier(), Modifier.finalModifier()),
                new VariableDeclarator(
                        newMethodType,
                        modifierName,
                        newMethodExpr
                )
        );

        modifierDecl.addMember(modifierInstanceDecl);

        MethodDeclaration modifierConf = new MethodDeclaration(
                NodeList.nodeList(Modifier.publicModifier()),
                methodDeclaration.getNameAsString(),
                new TypeParameter("S"),
                copyParam
        );

        modifierConf.setDefault(true);
        methodDeclaration.setFinal(false);

        modifierConf.setBody(new BlockStmt(
                NodeList.nodeList(
                        new ReturnStmt(
                                new MethodCallExpr(null, new SimpleName("configure"), NodeList.nodeList(
                                        referenceExpr
                                ))
                        )
                )
        ));

        modifierConf.setTypeParameters(cloneList(methodDeclaration.getTypeParameters()));


        configurationDecl.addMember(modifierConf);
    }

    private Set<String> collectTypeArgumentNames(MethodDeclaration methodDeclaration) {
        Collection<TypeParameter> args = new ArrayList<>(methodDeclaration.getTypeParameters());
        Node n = methodDeclaration;
        while(n != null) {
            if(n instanceof NodeWithTypeParameters<?> typeParameters) {
                args.addAll(new ArrayList<>(typeParameters.getTypeParameters()));
            }

            n = n.getParentNode().orElse(null);
        }
        return args.stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toUnmodifiableSet());
    }

    private void replaceWildcard(Type fT, Set<String> typeArgumentList) {
        if(fT instanceof ClassOrInterfaceType t) {
            if(typeArgumentList.contains(t.getNameAsString())) {
                fT.replace(new WildcardType());
                return;
            }
            t.getTypeArguments().ifPresent(s->s.forEach((u)->replaceWildcard(u, typeArgumentList)));
            return;
        }

        if(fT instanceof TypeParameter) {
            fT.replace(new WildcardType());
            return;
        }

        if(fT instanceof ArrayType t) {
            replaceWildcard(t.getComponentType(), typeArgumentList);
            return;
        }

        if(fT instanceof WildcardType wt){
            if(wt.getExtendedType().map(s-> s instanceof TypeParameter).orElse(false)) {
                wt.replace(new WildcardType());
                return;
            } else if(wt.getSuperType().map(s-> s instanceof TypeParameter).orElse(false)) {
                wt.replace(new WildcardType());
                return;
            } else if(wt.getSuperType().filter(s->s instanceof ClassOrInterfaceType).map(s->s.asClassOrInterfaceType().getNameAsString()).filter(typeArgumentList::contains).isPresent()) {
                wt.replace(new WildcardType());
                return;
            } else if(wt.getExtendedType().filter(s->s instanceof ClassOrInterfaceType).map(s->s.asClassOrInterfaceType().getNameAsString()).filter(typeArgumentList::contains).isPresent()) {
                wt.replace(new WildcardType());
                return;
            }
            wt.getExtendedType().ifPresent((s)->replaceWildcard(s, typeArgumentList));
            wt.getSuperType().ifPresent((s)->replaceWildcard(s, typeArgumentList));
            return;
        }
    }

    private String describeType(Type s) {
        if(s.isPrimitiveType()) {
            return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, s.asPrimitiveType().getType().asString());
        }
        if(s.isClassOrInterfaceType()) {
            return s.asClassOrInterfaceType().getName().getIdentifier();
        }
        if(s.isVoidType()) {
            return "Void";
        }
        if(s.isArrayType()) {
            return describeType(s.asArrayType().getComponentType()) + "Array";
        }
        return "Unknown";
    }

    private void onSelfReferenceAnnotation(AnnotationExpr annotationExpr, ResolvedAnnotationDeclaration annotationDeclaration, CompilationUnit source) {
        Node node = annotationExpr;
        while(!(node instanceof ConstructorDeclaration constructorDeclaration)) {
            node = node.getParentNode().orElse(null);
            if(node == null)
                return;
        }

        if(constructorDeclaration.containsData(SELF_REFERENCE_PROCESSED))
            return;

        NodeList<Parameter> parameters = new NodeList<>();
        List<Boolean> shouldWrap = new ArrayList<>();

        for (Parameter parameter : constructorDeclaration.getParameters()) {
            boolean shouldWrapThis = false;
            Parameter dParam = parameter.clone();
            parameters.add(dParam);
            var pList = List.copyOf(parameter.getAnnotations());
            for (int i = 0; i < pList.size(); i++) {
                AnnotationExpr annotation = pList.get(i);
                AnnotationExpr dAnnotation = dParam.getAnnotation(i);
                try{
                    ResolvedAnnotationDeclaration decl = annotation.resolve();
                    if(Objects.equals(decl.getQualifiedName(), SELF_REFERENCE)) {
                        dAnnotation.remove();
                        Type type = parameter.getType();
                        if(!type.isClassOrInterfaceType())
                            return;
                        var dType = dParam.getType();
                        var ciType = type.asClassOrInterfaceType();
                        if(!ciType.getNameAsString().equals("Function")){
                            continue;
                        }
                        var tao = ciType.getTypeArguments();
                        if(tao.isEmpty() || tao.get().size() != 2)
                            continue;
                        var tat = tao.get().get(1);
                        dParam.replace(dType, tat.clone());
                        shouldWrapThis = true;
                        break;
                    }
                }catch (UnsolvedSymbolException e){
                    System.err.println("[RegCodeGen.onSelfReferenceAnnotation] Failed to resolve annotation or type:");
                    e.printStackTrace();
                }
            }
            shouldWrap.add(shouldWrapThis);
        }

        MethodDeclaration declaration = new MethodDeclaration(
                NodeList.nodeList(Modifier.publicModifier(), Modifier.staticModifier()),
                "of",
                toClassOrInterfaceType(source.getPrimaryType().orElseThrow()),
                parameters
        );

        NodeList<Expression> expressions = toArguments(parameters);
        for (int i = 0; i < expressions.size(); i++) {
            if(shouldWrap.get(i)) {
                expressions.set(i, new LambdaExpr(new Parameter(new UnknownType(), "ig"), expressions.get(i)));
            }
        }

        declaration.setBody(new BlockStmt(NodeList.nodeList(
                new ReturnStmt(
                    new ObjectCreationExpr(
                            null,
                            toClassOrInterfaceType(source.getPrimaryType().orElseThrow()),
                            expressions
                    )
                )
        )));

        declaration.setTypeParameters(source.getPrimaryType().orElseThrow().asClassOrInterfaceDeclaration().getTypeParameters());

        source.getPrimaryType().orElseThrow().getMembers().add(0, declaration);

        constructorDeclaration.setData(SELF_REFERENCE_PROCESSED, true);

    }

    private NodeList<Expression> toArguments(NodeList<Parameter> parameters) {
        NodeList<Expression> expressions = new NodeList<>();
        for (Parameter parameter : parameters) {
            expressions.add(new NameExpr(parameter.getName()));
        }
        return expressions;
    }

    public static DataKey<Boolean> SHOULD_RESOLVE = new DataKey<Boolean>() {};

    private ClassOrInterfaceType toClassOrInterfaceType(TypeDeclaration<?> typeDeclaration) {
        return new ClassOrInterfaceType(
                null,
                typeDeclaration.getName(),
                toTypeUsage(typeDeclaration.asClassOrInterfaceDeclaration().getTypeParameters())
        );
    }

    private NodeList<Type> toTypeUsage(NodeList<TypeParameter> typeDeclaration) {
        NodeList<Type> types = new NodeList<>();
        for (TypeParameter typeParameter : typeDeclaration) {
            TypeParameter parameter = typeParameter.clone();
            parameter.setTypeBound(NodeList.nodeList());
            types.add(parameter);
        }
        if(types.isEmpty())
            return null;
        return types;
    }

    protected Type rebuildType(ResolvedType resolvedType, Consumer<String> importAdder) {
        Type paramType = null;
        if(resolvedType.isPrimitive()) {
            switch (resolvedType.asPrimitive()) {
                case BYTE -> paramType = new PrimitiveType(PrimitiveType.Primitive.BYTE);
                case SHORT -> paramType = new PrimitiveType(PrimitiveType.Primitive.SHORT);
                case CHAR -> paramType = new PrimitiveType(PrimitiveType.Primitive.CHAR);
                case INT -> paramType = new PrimitiveType(PrimitiveType.Primitive.INT);
                case LONG -> paramType = new PrimitiveType(PrimitiveType.Primitive.LONG);
                case BOOLEAN -> paramType = new PrimitiveType(PrimitiveType.Primitive.BOOLEAN);
                case DOUBLE -> paramType = new PrimitiveType(PrimitiveType.Primitive.DOUBLE);
                case FLOAT -> paramType = new PrimitiveType(PrimitiveType.Primitive.FLOAT);
            }
            return paramType;
        } else if(resolvedType.isArray()) {
            return new ArrayType(rebuildType(resolvedType.asArrayType().getComponentType(), importAdder));
        } else if(resolvedType.isTypeVariable()) {
            return new TypeParameter(resolvedType.asTypeVariable().qualifiedName());
        } else if(resolvedType.isReferenceType()) {
            ResolvedReferenceType ref = resolvedType.asReferenceType();
            ResolvedTypeDeclaration typeDeclaration = ref.getTypeDeclaration().orElseThrow();
            importAdder.accept(typeDeclaration.getQualifiedName());
            NodeList<Type> typeArguments = new NodeList<>();
            for (Pair<ResolvedTypeParameterDeclaration, ResolvedType>
                    res :
                    resolvedType.asReferenceType().getTypeParametersMap()
            ) {

                typeArguments.add(rebuildType(res.b, importAdder));
            }
            ClassOrInterfaceType type = new ClassOrInterfaceType(null, typeDeclaration.getName());
            type.setTypeArguments(typeArguments.isEmpty() ? null : typeArguments);

            return type;
        } else if(resolvedType.isVoid()) {
            return new VoidType();
        } else if(resolvedType.isWildcard()) {
            ReferenceType extendedType = null;
            ReferenceType superType = null;
            if(resolvedType.asWildcard().isBounded()) {
                if(resolvedType.asWildcard().isUpperBounded()) {
                    extendedType = rebuildType(resolvedType.asWildcard().getBoundedType(), importAdder).asReferenceType();
                } else {
                    superType = rebuildType(resolvedType.asWildcard().getBoundedType(), importAdder).asReferenceType();
                }
            }
            return new WildcardType(extendedType, superType, NodeList.nodeList());
        } else if(resolvedType.isConstraint()) {
            return new WildcardType(rebuildType(resolvedType.asConstraintType().getBound(), importAdder).asReferenceType());
        } else if(resolvedType.isTypeVariable()) {
            return new WildcardType();
        } else if(resolvedType.isUnionType()) {
            NodeList<ReferenceType> elements = new NodeList<>();
            for (ResolvedType type: resolvedType.asUnionType().getElements()) {
                elements.add(rebuildType(type, importAdder).asReferenceType());
            }
            return new UnionType(elements);
        }
        return StaticJavaParser.parseType(resolvedType.describe());
    }
}

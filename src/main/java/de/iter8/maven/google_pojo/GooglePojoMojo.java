package de.iter8.maven.google_pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.discovery.Discovery;
import com.google.api.services.discovery.model.JsonSchema;
import com.google.api.services.discovery.model.RestDescription;
import com.google.api.services.discovery.model.RestMethod;
import com.google.api.services.discovery.model.RestResource;
import com.google.common.base.CaseFormat;
import com.squareup.javapoet.*;
import de.iter8.google.api.webclient.AbstractReactiveGoogleClientRequest;
import de.iter8.google.api.webclient.AbstractReactiveGoogleJsonClient;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.springframework.http.HttpMethod;

import javax.annotation.Nullable;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

@Mojo(name = "generate-sources", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresDependencyResolution = ResolutionScope.COMPILE)
@Getter
@Setter
public class GooglePojoMojo extends AbstractMojo {
    private static final Function<String, String> FIELD_NAME_TO_CLASS_NAME = s -> CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, s);
    private static final Function<String, String> API_NAME_TO_PACKAGE_NAME = s -> CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, s);
    private static final Pattern ENUM_VALUE_MATCHER = Pattern.compile("\"(\\w+)\"[ -]+([^\\.]+\\.)", Pattern.MULTILINE);
    private static final AnnotationSpec LOMBOK_FLUENT_ACCESSORS = AnnotationSpec.builder(lombok.experimental.Accessors.class).addMember("fluent", "$L", true).build();

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "target-base-package", defaultValue = "info.plomer.generated.google")
    private String targetBasePackage;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/java", required = true)
    private File outputJavaDirectory;

    @Parameter(required = true)
    private String[] apis;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            final var discoveryClient = new Discovery.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), null).build();
            for (final var apiNameAndVersionStr : apis) {
                final var apiNameAndVersion = apiNameAndVersionStr.split(":");
                final var api = discoveryClient.apis().getRest(apiNameAndVersion[0], apiNameAndVersion[1]).execute();
                new GoogleApiBuilder(api).build();
            }

            project.addCompileSourceRoot(outputJavaDirectory.getAbsolutePath());
        } catch (GeneralSecurityException | IOException e) {
            throw new MojoExecutionException(e);
        }
    }

    /**
     * Represents an API field / parameter name while avoiding collisions with Java reserved words / keywords
     * <p>
     * If the API name collides with a reserved word, the resulting Java identifier name will have a "__" (double-underscore)
     * suffix, while a <code>com.fasterxml.jackson.annotation.JsonProperty</code> annotation will be provided to maintain
     * the original name on (de-)serialization.
     */
    private static class FieldName {
        public final String name;
        public final String jsonName;

        private FieldName(String name) {
            this.name = SourceVersion.isKeyword(name) ? (name + "__") : name;
            this.jsonName = name;
        }

        FieldSpec.Builder maybeAddJsonAnnotation(FieldSpec.Builder builder) {
            if (!name.equals(jsonName)) {
                builder.addAnnotation(AnnotationSpec
                        .builder(JsonProperty.class)
                        .addMember("value", "$S", jsonName)
                        .build());
            }
            return builder;
        }

        TypeSpec.Builder maybeAddJsonAnnotation(TypeSpec.Builder builder) {
            if (!name.equals(jsonName)) {
                builder.addAnnotation(AnnotationSpec
                        .builder(JsonProperty.class)
                        .addMember("value", "$S", jsonName)
                        .build());
            }
            return builder;
        }

        String toClassName() {
            return FIELD_NAME_TO_CLASS_NAME.apply(name);
        }

        static FieldName of(String name) {
            return new FieldName(name);
        }
    }

    /**
     * Delegate performing the actual work of generating API classes from the given Google <code>RestDescription</code>.
     */
    public class GoogleApiBuilder {
        private final RestDescription api;
        private final String packageName;
        private final String modelPackageName;
        private final ClassName requestClassName;
        private final ClassName clientClassName;

        public GoogleApiBuilder(RestDescription api) {
            this.api = api;
            packageName = String.format("%s.%s", targetBasePackage, API_NAME_TO_PACKAGE_NAME.apply(api.getName()));
            modelPackageName = String.format("%s.model", packageName);
            requestClassName = ClassName.get(packageName, FIELD_NAME_TO_CLASS_NAME.apply(api.getName()) + "Request");
            clientClassName = ClassName.get(packageName, FIELD_NAME_TO_CLASS_NAME.apply(api.getName()));
        }

        public void build() throws IOException {
            createModelClasses(api);
            createRequestClass(api);
            createClientClass(api);
        }

        /**
         * Iterates through the <code>schemas</code> element of the given <code>RestDescription</code> and generates
         * model classes from the schemas contained therein.
         *
         * @param api
         * @throws IOException
         * @throws MojoExecutionException
         */
        private void createModelClasses(RestDescription api) {
            for (final var schemaEntry : api.getSchemas().entrySet()) {
                final var schema = schemaEntry.getValue();
                maybeCreateModelTypeAndGetName(schema, null, null, null);
            }
        }

        /**
         * Create subclass of <code>AbstractReactiveGoogleJsonClient</code> with all the features specified in the
         * given <code>RestDescription</code>
         *
         * @param api
         * @throws IOException
         */
        private void createClientClass(RestDescription api) throws IOException {
            // todo: is the api.name really in LOWER_CAMEL? need to find examples of multi-word API names
            final var classBuilder = TypeSpec.classBuilder(clientClassName.simpleName())
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(SuperBuilder.class)
                    .superclass(AbstractReactiveGoogleJsonClient.class);
            ofNullable(api.getDescription()).map(GooglePojoMojo::escapeJavaDoc).ifPresent(classBuilder::addJavadoc);

            classBuilder.addField(createStringConstant("API_TITLE", api.getTitle()));
            classBuilder.addField(createStringConstant("API_VERSION", api.getVersion()));
            classBuilder.addField(createStringConstant("DEFAULT_ROOT_URL", api.getRootUrl()));
            classBuilder.addField(createStringConstant("DEFAULT_SERVICE_PATH", api.getServicePath()));
            classBuilder.addField(createStringConstant("DEFAULT_BATCH_PATH", api.getBatchPath()));
            classBuilder.addField(createStringConstantBuilder("DEFAULT_BASE_URL").initializer("DEFAULT_ROOT_URL + DEFAULT_SERVICE_PATH").build());

            addResources(clientClassName, classBuilder, api.getResources());

            JavaFile.builder(packageName, classBuilder.build()).build().writeTo(outputJavaDirectory);
        }

        private void addResources(ClassName parentResourceClassName, TypeSpec.Builder classBuilder, Map<String, RestResource> resources) throws IOException {
            if (resources == null) {
                return;
            }
            for (final var resourceEntry : resources.entrySet()) {
                // create the resource class and factory...

                try (final var resourceComponentBuilder = addApiComponent(parentResourceClassName, classBuilder, resourceEntry.getKey(), null, null)) {
                    final var resourceDescription = resourceEntry.getValue();
                    final var methods = resourceDescription.getMethods();
                    addMethods(resourceComponentBuilder, methods);
                    addResources(resourceComponentBuilder.className, resourceComponentBuilder.classBuilder, resourceDescription.getResources());
                }
            }
        }

        private void addMethods(ApiComponentBuilder resourceComponentBuilder, Map<String, RestMethod> methods) throws IOException {
            if (methods == null) {
                return;
            }
            for (final var methodEntry : methods.entrySet()) {
                final var methodDescription = methodEntry.getValue();

                // build ParamSpecs for the mandatory parameters of this method
                var params = ofNullable(methodDescription.getParameterOrder())
                        .map(paramNames -> paramNames.stream()
                                .map(FieldName::of)
                                .map(paramName -> ParameterSpec
                                        .builder(maybeCreateModelTypeAndGetName(methodDescription.getParameters().get(paramName.name),
                                                paramName, null, null), paramName.name)
                                        .build())
                                .toArray(ParameterSpec[]::new))
                        .orElseGet(() -> new ParameterSpec[0]);
                // create the method class and factory...
                try (final var methodComponent = addApiComponent(resourceComponentBuilder.className,
                        resourceComponentBuilder.classBuilder,
                        methodEntry.getKey(),
                        methodDescription.getDescription(),
                        ofNullable(methodDescription.getRequest()).map(req -> ParameterSpec.builder(resolveRef(req.get$ref()), "content").build()).orElse(null),
                        params)) {
                    final var responseClassName = ofNullable(methodDescription.getResponse())
                            .map(RestMethod.Response::get$ref).map(this::resolveRef).orElse(TypeName.get(Void.class));
                    methodComponent.classBuilder
                            .addAnnotation(Getter.class)
                            .addAnnotation(Setter.class)
                            .superclass(ParameterizedTypeName.get(requestClassName, responseClassName))
                            .addField(createStringConstant("REST_PATH", methodDescription.getPath()));

                    ofNullable(methodDescription.getParameters())
                            .map(Map::entrySet)
                            .map(Collection::stream)
                            .ifPresent(entryStream -> entryStream.filter(stringJsonSchemaEntry -> !ofNullable(stringJsonSchemaEntry.getValue().getRequired()).orElse(false))
                                    .forEach(paramEntry -> {
                                        final var paramName = FieldName.of(paramEntry.getKey());
                                        final var paramDescription = paramEntry.getValue();
                                        methodComponent.classBuilder.addField(FieldSpec
                                                .builder(maybeCreateModelTypeAndGetName(paramDescription, paramName, null, null),
                                                        paramName.name, Modifier.PRIVATE)
                                                .addAnnotation(ClassName.get("com.google.api.client.util", "Key"))
                                                .build());
                                    }));
                    methodComponent.constructorBuilder.addCode("super($T.this, $T.class, $T.$L, REST_PATH, $L);\n", clientClassName, responseClassName, HttpMethod.class, methodDescription.getHttpMethod(), methodDescription.getRequest() != null ? "content" : "null");
                }
            }
        }

        /**
         * Create subclass of AbstractReactiveGoogleClientRequest with same type parameter(s) and constructor(s),
         * and add all global request parameters.
         *
         * @param api
         * @return
         * @throws IOException
         */
        private void createRequestClass(RestDescription api) throws IOException {
            final var className = ClassName.get(packageName, FIELD_NAME_TO_CLASS_NAME.apply(api.getName()) + "Request");
            final var tvVars = Arrays.stream(AbstractReactiveGoogleClientRequest.class.getTypeParameters())
                    .map(classTypeVariable -> TypeVariableName.get(classTypeVariable.getName()))
                    .toArray(TypeVariableName[]::new);
            final var classBuilder = TypeSpec.classBuilder(className.simpleName())
                    .addModifiers(Modifier.PUBLIC)
                    .addTypeVariables(Arrays.asList(tvVars))
                    .superclass(ParameterizedTypeName.get(ClassName.get(AbstractReactiveGoogleClientRequest.class), tvVars));

            for (Constructor<?> constructor : AbstractReactiveGoogleClientRequest.class.getConstructors()) {
                final var params = new ArrayList<ParameterSpec>(constructor.getParameterTypes().length);
                for (final var param : constructor.getParameters()) {
                    params.add(ParameterSpec.builder(param.getType(), param.getName()).build());
                }
                classBuilder.addMethod(MethodSpec.constructorBuilder()
                        .addParameters(params)
                        .addCode("super($L);", params.stream()
                                .map(parameterSpec -> parameterSpec.name)
                                .collect(Collectors.joining(", "))).build());
            }

            JavaFile.builder(packageName, classBuilder.build()).build().writeTo(outputJavaDirectory);
        }

        @RequiredArgsConstructor
        private static abstract class ApiComponentBuilder implements Closeable {
            final ClassName className;
            final TypeSpec.Builder classBuilder;
            final MethodSpec.Builder constructorBuilder;
        }

        /**
         * Add inner class to the client that represents and API component (feature, method) and provide a factory
         * method on the containing class.
         * <p>
         * If the component has required parameters (as is the case for most methods), these can be specified and will
         * be added as class fields as well as constructor and factory method parameters.
         *
         * @param outerName
         * @param outerBuilder
         * @param name
         * @param description
         * @param params
         * @return
         */
        public ApiComponentBuilder addApiComponent(ClassName outerName, TypeSpec.Builder outerBuilder, String name,
                                                   @Nullable String description,
                                                   @Nullable ParameterSpec bodyParam,
                                                   ParameterSpec... params) {
            final var allParams = new ArrayList<>(Arrays.asList(params));
            if (bodyParam != null) {
                allParams.add(bodyParam);
            }

            final var componentName = FieldName.of(name);
            final var className = outerName.nestedClass(componentName.toClassName());
            final var classBuilder = TypeSpec.classBuilder(className)
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(AnnotationSpec.builder(Accessors.class).addMember("fluent", "true").build());
            final var constructorBuilder = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameters(allParams);

            return new ApiComponentBuilder(className, classBuilder, constructorBuilder) {
                @Override
                public void close() {
                    for (ParameterSpec param : params) {
                        addField(param, true);
                    }
                    if (bodyParam != null) {
                        addField(bodyParam, false);
                    }

                    outerBuilder.addType(classBuilder
                            .addMethod(constructorBuilder.build())
                            .build());
                    final var methodBuilder = MethodSpec.methodBuilder(componentName.name)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(className)
                            .addParameters(allParams)
                            .addCode("return new $T($L);", className, allParams.stream()
                                    .map(parameterSpec -> parameterSpec.name)
                                    .collect(Collectors.joining(", ")));
                    ofNullable(description).map(GooglePojoMojo::escapeJavaDoc).ifPresent(methodBuilder::addJavadoc);
                    outerBuilder.addMethod(methodBuilder.build());
                }

                private void addField(ParameterSpec param, boolean isKey) {
                    final var fieldSpecBuilder = FieldSpec.builder(param.type, param.name, Modifier.PRIVATE, Modifier.FINAL);
                    if (isKey) {
                        fieldSpecBuilder.addAnnotation(ClassName.get("com.google.api.client.util", "Key"));
                    }
                    classBuilder.addField(fieldSpecBuilder.build());
                    constructorBuilder.addCode("this.$L = $T.requireNonNull($L, \"Required parameter $L must be specified.\");\n", param.name, Objects.class, param.name, param.name);
                }
            };
        }

        /**
         * Helper method - creates a <code>FieldSpec.Builder</code> for a <code>public static final String NAME = "value";</code> field.
         *
         * @param name
         * @param value
         * @return
         */
        private FieldSpec createStringConstant(String name, String value) {
            return createStringConstantBuilder(name).initializer("$S", value).build();
        }

        /**
         * Helper method - creates a <code>FieldSpec.Builder</code> for a <code>public static final</code> field.
         *
         * @param name
         * @return
         */
        private FieldSpec.Builder createStringConstantBuilder(String name) {
            return FieldSpec.builder(String.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        }

        private TypeSpec.Builder getModelClassBuilder(JsonSchema schema, ClassName typeName) {
            final var classBuilder = TypeSpec.classBuilder(typeName.simpleName())
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(NoArgsConstructor.class)
                    .addAnnotation(Builder.class)
                    .addAnnotation(Data.class)
                    .addAnnotation(AnnotationSpec.builder(JsonInclude.class).addMember("value", codeBlockForEnumValue(JsonInclude.Include.NON_NULL)).build());
            if (schema.getProperties() != null && schema.getProperties().size() > 0) {
                classBuilder.addAnnotation(AllArgsConstructor.class);
                for (final var propEntry : schema.getProperties().entrySet()) {
                    final var fldName = FieldName.of(propEntry.getKey());
                    final var fldSchema = propEntry.getValue();
                    final var fldSpec = FieldSpec.builder(maybeCreateModelTypeAndGetName(fldSchema, fldName, typeName, classBuilder), fldName.name)
                            .addModifiers(Modifier.PRIVATE);
                    fldName.maybeAddJsonAnnotation(fldSpec);
                    maybeDecorate(fldSpec, fldSchema);
                    ofNullable(fldSchema.getDescription()).map(GooglePojoMojo::escapeJavaDoc).ifPresent(fldSpec::addJavadoc);
                    classBuilder.addField(fldSpec.build());
                }
            }
            return classBuilder;
        }

        private void maybeDecorate(FieldSpec.Builder fldSpec, JsonSchema propEntry) {
            ofNullable(propEntry.getDescription())
                    .filter(s -> s.contains("formatted according to RFC3339"))
                    .ifPresent(s -> fldSpec
                            .addAnnotation(AnnotationSpec.builder(JsonFormat.class)
                                    .addMember("shape", codeBlockForEnumValue(JsonFormat.Shape.STRING))
                                    .addMember("pattern", "$S","yyyy-MM-dd'T'HH:mm:ssXXX")
                                    .build()));
        }

        private static <E extends Enum<E>> CodeBlock codeBlockForEnumValue(E value) {
            return CodeBlock.of("$T.$L", value.getClass(), value);
        }

        /**
         * Return a <code>TypeName</code> representing the class referenced by the passed <code>$ref</code>.
         *
         * @param ref
         * @return
         */
        private TypeName resolveRef(String ref) {
            return ClassName.get(modelPackageName, ref);
        }

        @SneakyThrows({MojoExecutionException.class, IOException.class})
        private TypeName maybeCreateModelTypeAndGetName(JsonSchema schema, @Nullable FieldName fieldNameHint,
                                                        @Nullable ClassName parentType,
                                                        @Nullable TypeSpec.Builder parentBuilder) {
            if (schema.get$ref() != null) {
                return resolveRef(schema.get$ref());
            }

            switch (schema.getType()) {
                case "object":
                    // if schema has Id, that is our external class name
                    // if no ID:
                    //  - if regular properties, type is an inner class (must have fieldNameHint)
                    //  - if only additionalProperties, type is a Map<String, additional-prop-type>

                    if (schema.getId() != null) {
                        // top-level class
                        final var typeName = ClassName.get(modelPackageName, schema.getId());
                        final TypeSpec.Builder classBuilder = getModelClassBuilder(schema, typeName);
                        if (parentType == null) {
                            ofNullable(schema.getDescription()).map(GooglePojoMojo::escapeJavaDoc).ifPresent(classBuilder::addJavadoc);
                        }

                        JavaFile.builder(modelPackageName, classBuilder.build()).build().writeTo(outputJavaDirectory);
                        return typeName;
                    }
                    if (fieldNameHint != null && schema.getProperties() != null) {
                        // nested class
                        // todo: handle null parent context -> create top level class in model directory (also for enums below)
                        if (parentType == null || parentBuilder == null) {
                            throw new MojoExecutionException(String.format("Parent context for nested class %s missing", fieldNameHint.name));
                        }
                        final var typeName = parentType.nestedClass(fieldNameHint.toClassName());
                        final TypeSpec.Builder classBuilder = getModelClassBuilder(schema, typeName);
                        classBuilder.addModifiers(Modifier.STATIC);
                        parentBuilder.addType(classBuilder.build());
                        return typeName;
                    }
                    if (schema.getAdditionalProperties() != null) {
                        return ParameterizedTypeName.get(ClassName.get(Map.class), TypeName.get(String.class), maybeCreateModelTypeAndGetName(schema.getAdditionalProperties(), fieldNameHint, parentType, parentBuilder));
                    }
                    throw new MojoExecutionException("No name provided for type");
                case "string":
                    // todo: distinguish other types? date / time?
                    if (schema.getFormat() != null) {
                        switch (schema.getFormat()) {
                            case "date":
                                return TypeName.get(LocalDate.class);
                            case "date-time":
                                return TypeName.get(OffsetDateTime.class);
                            case "int64":
                                return TypeName.get(Long.class);
                            default:
                        }
                    }
                    // todo: infer enums from description?
                    if (schema.getDescription() != null && schema.getDescription().contains("Possible values are:")) {
                        final var enumSpec = TypeSpec.enumBuilder(fieldNameHint.toClassName())
                                .addModifiers(Modifier.PUBLIC);
                        final var matcher = ENUM_VALUE_MATCHER.matcher(schema.getDescription());
                        while (matcher.find()) {
                            final var enumName = FieldName.of(matcher.group(1));
                            enumSpec.addEnumConstant(enumName.name, enumName.maybeAddJsonAnnotation(TypeSpec.anonymousClassBuilder("").addJavadoc(escapeJavaDoc(matcher.group(2)))).build());
                        }
                        parentBuilder.addType(enumSpec.build());
                        return parentType.nestedClass(fieldNameHint.toClassName());
                    }
                    return ofNullable(fieldNameHint)
                            .map(s -> {
                                switch (s.jsonName) {
                                    case "timeZone":
                                        return TypeName.get(ZoneId.class);
                                    default:
                                }
                                return TypeName.get(String.class);
                            })
                            .orElseGet(() -> TypeName.get(String.class));
                case "number":
                    if (schema.getFormat() != null) {
                        switch (schema.getFormat()) {
                            case "float":
                                return TypeName.get(Float.class);
                            case "double":
                                return TypeName.get(Double.class);
                            default:
                                getLog().warn(String.format("Don't know how to handle JsonSchema number format %s for schema %s", schema.getFormat(), schema.getId()));
                        }
                    }
                    return TypeName.get(Long.class);
                case "integer":
                    return TypeName.get(Integer.class);
                case "boolean":
                    return TypeName.get(Boolean.class);
                case "null":
                    return TypeName.get(Void.class);
                case "array":
                    final var itemType = maybeCreateModelTypeAndGetName(schema.getItems(), fieldNameHint, parentType, parentBuilder);
                    return ParameterizedTypeName.get(ClassName.get(List.class), itemType);
                case "any":
                    return TypeName.OBJECT;
                default:
                    getLog().warn(String.format("Don't know how to handle JsonSchema type %s for schema %s", schema.getType(), schema.getId()));

            }
            return TypeName.get(Void.class);
        }
    }

    private static String escapeJavaDoc(String s) {
        return s.replaceAll("/", "&#47;");
    }

    private <T> T[] append(T[] arr, T elem) {
        final var res = Arrays.copyOf(arr, arr.length + 1);
        res[res.length - 1] = elem;
        return res;
    }
}


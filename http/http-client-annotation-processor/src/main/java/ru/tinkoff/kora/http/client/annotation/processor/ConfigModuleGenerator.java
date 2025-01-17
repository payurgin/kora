package ru.tinkoff.kora.http.client.annotation.processor;

import com.squareup.javapoet.*;
import com.typesafe.config.Config;
import ru.tinkoff.kora.common.Module;
import ru.tinkoff.kora.config.common.extractor.ConfigValueExtractor;
import ru.tinkoff.kora.http.client.common.annotation.HttpClient;

import javax.annotation.processing.Generated;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

public class ConfigModuleGenerator {
    private final Elements elements;

    public ConfigModuleGenerator(ProcessingEnvironment processingEnvironment) {
        this.elements = processingEnvironment.getElementUtils();
    }

    public JavaFile generate(TypeElement element) {
        var lowercaseName = new StringBuilder(element.getSimpleName());
        lowercaseName.setCharAt(0, Character.toLowerCase(lowercaseName.charAt(0)));
        var packageName = this.elements.getPackageOf(element).getQualifiedName().toString();
        var configPath = element.getAnnotation(HttpClient.class).configPath();
        if (configPath.isBlank()) {
            configPath = "httpClient." + lowercaseName;
        }

        var configName = HttpClientUtils.configName(element);
        var moduleName = HttpClientUtils.moduleName(element);
        var configClass = ClassName.get(packageName, configName);
        var extractorClass = ParameterizedTypeName.get(ClassName.get(ConfigValueExtractor.class), configClass);

        var type = TypeSpec.interfaceBuilder(moduleName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "$S", ConfigModuleGenerator.class.getCanonicalName()).build())
            .addAnnotation(AnnotationSpec.builder(Module.class).build())
            .addOriginatingElement(element)
            .addMethod(MethodSpec.methodBuilder(lowercaseName.toString() + "Config")
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .returns(configClass)
                .addParameter(ParameterSpec.builder(Config.class, "config").build())
                .addParameter(ParameterSpec.builder(extractorClass, "extractor").build())
                .addStatement("var value = config.getValue($S)", configPath)
                .addStatement("return extractor.extract(value)")
                .build());
        return JavaFile.builder(packageName, type.build())
            .build();
    }
}

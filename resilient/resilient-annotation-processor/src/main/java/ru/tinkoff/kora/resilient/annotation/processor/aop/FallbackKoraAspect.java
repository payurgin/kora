package ru.tinkoff.kora.resilient.annotation.processor.aop;

import com.squareup.javapoet.CodeBlock;
import ru.tinkoff.kora.annotation.processor.common.MethodUtils;
import ru.tinkoff.kora.annotation.processor.common.ProcessingError;
import ru.tinkoff.kora.annotation.processor.common.ProcessingErrorException;
import ru.tinkoff.kora.aop.annotation.processor.KoraAspect;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;

import static com.squareup.javapoet.CodeBlock.joining;

public class FallbackKoraAspect implements KoraAspect {

    private static final String ANNOTATION_TYPE = "ru.tinkoff.kora.resilient.fallback.annotation.Fallback";

    private final ProcessingEnvironment env;

    public FallbackKoraAspect(ProcessingEnvironment env) {
        this.env = env;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ANNOTATION_TYPE);
    }

    @Override
    public ApplyResult apply(ExecutableElement method, String superCall, AspectContext aspectContext) {
        if (MethodUtils.isFuture(method, env)) {
            throw new ProcessingErrorException(new ProcessingError(Diagnostic.Kind.NOTE, "@Fallback can't be applied for types assignable from " + Future.class, method));
        }

        final Optional<? extends AnnotationMirror> mirror = method.getAnnotationMirrors().stream().filter(a -> a.getAnnotationType().toString().equals(ANNOTATION_TYPE)).findFirst();
        final FallbackMeta fallback = mirror.flatMap(a -> a.getElementValues().entrySet().stream()
                .filter(e -> e.getKey().getSimpleName().contentEquals("method"))
                .map(e -> String.valueOf(e.getValue().getValue())).findFirst()
                .filter(v -> !v.isBlank()))
            .map(v -> FallbackMeta.ofFallbackMethod(v, method))
            .orElseThrow(() -> new IllegalStateException("Method argument for @Fallback is mandatory!"));
        final String name = mirror.flatMap(a -> a.getElementValues().entrySet().stream()
                .filter(e -> e.getKey().getSimpleName().contentEquals("value"))
                .map(e -> String.valueOf(e.getValue().getValue())).findFirst())
            .orElseThrow();

        var managerType = env.getTypeUtils().getDeclaredType(env.getElementUtils().getTypeElement("ru.tinkoff.kora.resilient.fallback.FallbackerManager"));
        var fieldManager = aspectContext.fieldFactory().constructorParam(managerType, List.of());

        final CodeBlock body;
        if (MethodUtils.isMono(method, env)) {
            body = buildBodyMono(method, fallback, superCall, name, fieldManager);
        } else if (MethodUtils.isFlux(method, env)) {
            body = buildBodyFlux(method, fallback, superCall, name, fieldManager);
        } else {
            body = buildBodySync(method, fallback, superCall, name, fieldManager);
        }

        return new ApplyResult.MethodBody(body);
    }

    private CodeBlock buildBodySync(ExecutableElement method, FallbackMeta fallbackCall, String superCall, String fallbackName, String fieldManager) {
        if (MethodUtils.isVoid(method)) {
            final CodeBlock superMethod = buildMethodCall(method, superCall);
            final String fallbackMethod = fallbackCall.call();
            return CodeBlock.builder().add("""
                var _fallbacker = $L.get("$L");
                _fallbacker.fallback(() -> $L, () -> $L);
                """, fieldManager, fallbackName, superMethod.toString(), fallbackMethod).build();
        }

        final CodeBlock superMethod = buildMethodSupplier(method, superCall);
        final String fallbackMethod = fallbackCall.call();
        return CodeBlock.builder().add("""
            var _fallbacker = $L.get("$L");
            return _fallbacker.fallback($L, () -> $L);
            """, fieldManager, fallbackName, superMethod.toString(), fallbackMethod).build();
    }

    private CodeBlock buildBodyMono(ExecutableElement method, FallbackMeta fallbackCall, String superCall, String fallbackName, String fieldManager) {
        final CodeBlock superMethod = buildMethodCall(method, superCall);
        final String fallbackMethod = fallbackCall.call();
        return CodeBlock.builder().add("""
            var _fallbacker = $L.get("$L");
            return $L
                .onErrorResume(e -> _fallbacker.canFallback(e), e -> $L);
                 """, fieldManager, fallbackName, superMethod.toString(), fallbackMethod).build();
    }

    private CodeBlock buildBodyFlux(ExecutableElement method, FallbackMeta fallbackCall, String superCall, String fallbackName, String fieldManager) {
        final CodeBlock superMethod = buildMethodCall(method, superCall);
        final String fallbackMethod = fallbackCall.call();
        return CodeBlock.builder().add("""
            var _fallbacker = $L.get("$L");
            return $L
                .onErrorResume(e -> _fallbacker.canFallback(e), e -> $L);
                 """, fieldManager, fallbackName, superMethod.toString(), fallbackMethod).build();
    }

    private CodeBlock buildMethodCall(ExecutableElement method, String call) {
        return method.getParameters().stream().map(p -> CodeBlock.of("$L", p)).collect(joining(", ", call + "(", ")"));
    }

    private CodeBlock buildMethodSupplier(ExecutableElement method, String call) {
        return method.getParameters().stream().map(p -> CodeBlock.of("$L", p)).collect(joining(", ", "() -> " + call + "(", ")"));
    }
}

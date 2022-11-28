package ru.tinkoff.kora.resilient.symbol.processor.aop

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toClassName
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.tinkoff.kora.aop.symbol.processor.KoraAspect
import ru.tinkoff.kora.ksp.common.FunctionUtils.isFlow
import ru.tinkoff.kora.ksp.common.FunctionUtils.isFlux
import ru.tinkoff.kora.ksp.common.FunctionUtils.isFuture
import ru.tinkoff.kora.ksp.common.FunctionUtils.isMono
import ru.tinkoff.kora.ksp.common.FunctionUtils.isSuspend
import ru.tinkoff.kora.ksp.common.exception.ProcessingError
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import java.util.concurrent.Future
import javax.tools.Diagnostic

@KspExperimental
class TimeoutKoraAspect(val resolver: Resolver) : KoraAspect {

    companion object {
        const val ANNOTATION_TYPE: String = "ru.tinkoff.kora.resilient.timeout.annotation.Timeout"
    }

    override fun getSupportedAnnotationTypes(): Set<String> {
        return setOf(ANNOTATION_TYPE)
    }

    override fun apply(method: KSFunctionDeclaration, superCall: String, aspectContext: KoraAspect.AspectContext): KoraAspect.ApplyResult {
        if (method.isFuture()) {
            throw ProcessingErrorException(
                ProcessingError(
                    "@Timeout can't be applied for types assignable from ${Future::class.java}", method, Diagnostic.Kind.NOTE
                )
            )
        }
        if (method.isMono()) {
            throw ProcessingErrorException(
                ProcessingError(
                    "@Timeout can't be applied for types assignable from ${Mono::class.java}", method, Diagnostic.Kind.NOTE
                )
            )
        }
        if (method.isFlux()) {
            throw ProcessingErrorException(
                ProcessingError(
                    "@Timeout can't be applied for types assignable from ${Flux::class.java}", method, Diagnostic.Kind.NOTE
                )
            )
        }

        val annotation = method.annotations.asSequence().filter { a -> a.annotationType.resolve().toClassName().canonicalName == ANNOTATION_TYPE }.first()
        val timeoutName = annotation.arguments.asSequence().filter { arg -> arg.name!!.getShortName() == "value" }.map { arg -> arg.value.toString() }.first()

        val managerType = resolver.getClassDeclarationByName("ru.tinkoff.kora.resilient.timeout.TimeouterManager")!!.asType(listOf())
        val fieldManager = aspectContext.fieldFactory.constructorParam(managerType, listOf())
        val timeoutField = aspectContext.fieldFactory.constructorInitialized(
            resolver.getClassDeclarationByName("ru.tinkoff.kora.resilient.timeout.Timeouter")!!.asType(listOf()),
            CodeBlock.of("%L[%S]", fieldManager, timeoutName)
        )

        val body = if (method.isFlow()) {
            buildBodyFlow(method, superCall, timeoutField)
        } else if (method.isSuspend()) {
            buildBodySuspend(method, superCall, timeoutField)
        } else {
            buildBodySync(method, superCall, timeoutField)
        }

        return KoraAspect.ApplyResult.MethodBody(body)
    }

    private fun buildBodySync(
        method: KSFunctionDeclaration, superCall: String, timeoutName: String
    ): CodeBlock {
        val superMethod = buildMethodCall(method, superCall)
        val supplierMember = MemberName("java.util.function", "Supplier")
        return CodeBlock.builder().add(
            """
            val _timeouter = %L
            return _timeouter.execute(%M { %L })
            """.trimIndent(), timeoutName, supplierMember, superMethod.toString()
        ).build()
    }

    private fun buildBodySuspend(
        method: KSFunctionDeclaration, superCall: String, timeoutName: String
    ): CodeBlock {
        val superMethod = buildMethodCall(method, superCall)
        val timeoutMember = MemberName("kotlinx.coroutines", "withTimeout")
        return CodeBlock.builder().add(
            """
            val _timeouter = %L
            return %M(_timeouter.timeout().toMillis()) {
                %L
            }
            """.trimIndent(), timeoutName, timeoutMember, superMethod.toString()
        ).build()
    }

    private fun buildBodyFlow(
        method: KSFunctionDeclaration, superCall: String, timeoutName: String
    ): CodeBlock {
        val flowMember = MemberName("kotlinx.coroutines.flow", "flow")
        val emitMember = MemberName("kotlinx.coroutines.flow", "emitAll")
        val startMember = MemberName("kotlinx.coroutines.flow", "onStart")
        val systemMember = MemberName("java.lang", "System")
        val atomicMember = MemberName("java.util.concurrent.atomic", "AtomicLong")
        val timeoutMember = MemberName("ru.tinkoff.kora.resilient.timeout", "TimeoutException")
        val superMethod = buildMethodCall(method, superCall)
        return CodeBlock.builder().add(
            """
            val _timeouter = %L
            val duration = _timeouter.timeout().nano
            val started = %M()
            return %M {
                takeUnless {
                    val current = %M.nanoTime()
                    if (current - started.get() > duration) {
                        throw %M("Timeout exceeded " + _timeouter.timeout())
                    } else {
                        false
                    }
                }
                %M(%L)
            }.%M{ started.set(%M.nanoTime()) }
            """.trimIndent(), timeoutName, atomicMember, flowMember, systemMember,
            timeoutMember, emitMember, superMethod.toString(), startMember, systemMember
        ).build()
    }

    private fun buildMethodCall(method: KSFunctionDeclaration, call: String): CodeBlock {
        return CodeBlock.of(method.parameters.asSequence().map { p -> CodeBlock.of("%L", p) }.joinToString(", ", "$call(", ")"))
    }
}

package ru.tinkoff.kora.http.client.symbol.processor.extension

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import ru.tinkoff.kora.http.client.common.annotation.HttpClient
import ru.tinkoff.kora.http.client.symbol.processor.clientName
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionResult
import ru.tinkoff.kora.kora.app.ksp.extension.KoraExtension
import ru.tinkoff.kora.kora.app.ksp.hasAopAnnotations
import ru.tinkoff.kora.ksp.common.getOuterClassesAsPrefix

@KspExperimental
class HttpClientKoraExtension : KoraExtension {
    override fun getDependencyGenerator(resolver: Resolver, type: KSType): (() -> ExtensionResult)? {
        val declaration = type.declaration
        if (declaration !is KSClassDeclaration || declaration.classKind != ClassKind.INTERFACE) {
            return null
        }
        if (declaration.getAnnotationsByType(HttpClient::class).firstOrNull() == null) {
            return null
        }
        return lambda@{
            val implName = declaration.clientName()
            val packageName: String = declaration.packageName.asString()
            val maybeGenerated = resolver.getClassDeclarationByName("$packageName.$implName")
            if (maybeGenerated == null) {
                return@lambda ExtensionResult.RequiresCompilingResult
            }
            if (!hasAopAnnotations(resolver, maybeGenerated)) {
                return@lambda maybeGenerated.getConstructors().map { ExtensionResult.fromConstructor(it, maybeGenerated) }.first()
            }
            val aopProxy = maybeGenerated.getOuterClassesAsPrefix() + maybeGenerated.simpleName.getShortName() + "__AopProxy"
            val aopProxyElement = resolver.getClassDeclarationByName("$packageName.$aopProxy")
            if (aopProxyElement == null) {
                return@lambda ExtensionResult.RequiresCompilingResult
            }
            return@lambda aopProxyElement.getConstructors().map { ExtensionResult.fromConstructor(it, aopProxyElement) }.first()
        }
    }
}


package app.cash.quickjs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class QuickJsAbiTest {

    @Test
    fun `matches extension-facing method ABI`() {
        QuickJs::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map(Method::abiSignature)
            .toSet() shouldBe
            setOf(
                "create()",
                "evaluate(String)",
                "evaluate(String,String)",
                "execute(byte[])",
                "compile(String,String)",
                "get(String,Class)",
                "set(String,Class,Object)",
                "close()",
            )
    }

    @Test
    fun `compatibility API still evaluates JavaScript`() {
        QuickJs.create().use { runtime ->
            runtime.evaluate("21 * 2") shouldBe 42
        }
    }

    @Test
    fun `keeps extension-facing class shape`() {
        Closeable::class.java.isAssignableFrom(QuickJs::class.java) shouldBe true
        Modifier.isFinal(QuickJs::class.java.modifiers) shouldBe true
        QuickJs::class.java.name shouldBe "app.cash.quickjs.QuickJs"
    }

    @Test
    fun `keeps exception type and constructors`() {
        QuickJsException::class.java.name shouldBe "app.cash.quickjs.QuickJsException"
        QuickJsException::class.java.superclass shouldBe RuntimeException::class.java
        QuickJsException::class.java.declaredConstructors
            .map(Constructor<*>::abiSignature)
            .toSet() shouldBe setOf("(String)", "(String,String)")
    }

    private fun Method.abiSignature(): String =
        "$name(${parameterTypes.joinToString(",") { it.abiName() }})"

    private fun Constructor<*>.abiSignature(): String =
        "(${parameterTypes.joinToString(",") { it.abiName() }})"

    private fun Class<*>.abiName(): String = when {
        isArray -> "${componentType.abiName()}[]"
        this == String::class.java -> "String"
        this == Class::class.java -> "Class"
        this == Any::class.java -> "Object"
        else -> simpleName
    }
}

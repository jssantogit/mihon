package app.cash.quickjs

import com.dokar.quickjs.binding.define
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import com.dokar.quickjs.QuickJs as DokarQuickJs
import com.dokar.quickjs.QuickJsException as DokarQuickJsException

class QuickJs private constructor() : Closeable {

    private val runtime = DokarQuickJs.create(dispatcher)

    @JvmOverloads
    fun evaluate(
        code: String,
        fileName: String = DEFAULT_FILE_NAME,
    ): Any? = translateErrors {
        onJsThread {
            evaluate<Any?>(
                code = code,
                filename = fileName,
                asModule = false,
            )
        }
    }

    fun execute(bytecode: ByteArray): Any? = translateErrors {
        onJsThread { evaluate<Any?>(bytecode) }
    }

    fun compile(
        code: String,
        fileName: String,
    ): ByteArray = translateErrors {
        onJsThread { compile(code, fileName, asModule = false) }
    }

    fun <T : Any> set(
        name: String,
        type: Class<T>,
        value: T,
    ) {
        val methods = bindableMethods(name, type)
        require(type.isInstance(value)) { "$value is not an instance of $type" }
        translateErrors {
            onJsThread {
                define(
                    name = name,
                    type = type,
                    instance = value,
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(
        name: String,
        type: Class<T>,
    ): T {
        bindableMethods(name, type)
        return Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            JsObjectHandler(this, name),
        ) as T
    }

    override fun close() {
        translateErrors {
            onJsThread {
                if (!isClosed) {
                    close()
                }
            }
        }
    }

    private fun bindableMethods(
        name: String,
        type: Class<*>,
    ): List<Method> {
        require(name.isNotBlank()) { "name must not be blank" }
        require(type.isInterface) { "Only interfaces can be bound. Received: $type" }
        require(type.interfaces.isEmpty()) { "$type must not extend other interfaces" }

        val methods = type.methods.sortedBy { it.name }
        methods.groupBy(Method::getName).forEach { (methodName, overloads) ->
            require(overloads.size == 1) { "$methodName is overloaded in $type" }
        }
        return methods
    }

    private fun <T> onJsThread(block: suspend DokarQuickJs.() -> T): T = runBlocking {
        withContext(dispatcher) {
            runtime.block()
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "?"

        private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tsuzuki-quickjs-compat").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()

        @JvmStatic
        fun create(): QuickJs = QuickJs()
    }
}

private inline fun <T> translateErrors(block: () -> T): T = try {
    block()
} catch (error: DokarQuickJsException) {
    throw QuickJsException(error.message ?: "JavaScript error")
}

private class JsObjectHandler(
    private val quickJs: QuickJs,
    private val globalName: String,
) : InvocationHandler {

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? {
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "toString" -> "QuickJsObject($globalName)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        }

        val arguments = args.orEmpty().joinToString(",", prefix = "[", postfix = "]") {
            it.toJsLiteral()
        }
        val call =
            "globalThis[${globalName.toJsLiteral()}][${method.name.toJsLiteral()}]($arguments)"
        return quickJs.evaluate(call, "$globalName.${method.name}.js")
    }
}

private fun Any?.toJsLiteral(): String = when (this) {
    null -> "null"
    is Boolean, is Number -> toString()
    is Enum<*> -> toString().toJsString()
    else -> toString().toJsString()
}

private fun String.toJsString(): String = buildString {
    append('"')
    forEach { char ->
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
    append('"')
}

package io.github.zby1888.codexzh

import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one Codex App Server stdio connection inside this app's private runtime.
 *
 * Agent protocol traffic is deliberately separate from the interactive PTY
 * terminal. Do not attach a terminal view to this process's stdin/stdout.
 */
internal class CodexAppServerClient(
    private val paths: RuntimePaths,
    private val listener: Listener = NoopListener
) : Closeable {

    interface Listener {
        /** Server notifications such as item/started and turn/completed. */
        fun onNotification(message: JSONObject)

        /** Server-initiated requests, including approval / elicitation requests. */
        fun onServerRequest(message: JSONObject)

        /** A non-sensitive diagnostic summary; raw stderr is never forwarded. */
        fun onDiagnostic(message: String)
    }

    private object NoopListener : Listener {
        override fun onNotification(message: JSONObject) = Unit
        override fun onServerRequest(message: JSONObject) = Unit
        override fun onDiagnostic(message: String) = Unit
    }

    private val nextId = AtomicLong(1L)
    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val io: ExecutorService = Executors.newFixedThreadPool(3)

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var initialized = false

    val isAlive: Boolean
        get() = !closed.get() && process?.isAlive == true

    val isInitialized: Boolean
        get() = initialized && isAlive

    /**
     * Starts codex app-server and completes the mandatory initialize handshake.
     * The pinned CLI version must be validated against this protocol before release.
     */
    @Synchronized
    fun connect(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        check(!closed.get()) { "Codex App Server 客户端已经关闭" }
        check(process == null) { "Codex App Server 已经启动" }

        val codex = paths.codex
        check(codex.isFile && codex.canExecute()) { "私有运行环境中没有可执行的 codex" }

        val builder = ProcessBuilder(
            codex.absolutePath,
            "app-server",
            "--listen",
            "stdio://"
        ).directory(paths.home)
        builder.environment().putAll(paths.environment())

        val child = builder.start()
        process = child
        writer = BufferedWriter(OutputStreamWriter(child.outputStream, Charsets.UTF_8))

        io.execute { readStdout(child) }
        io.execute { drainStderr(child) }
        io.execute { watchExit(child) }

        val initParams = JSONObject()
            .put(
                "clientInfo",
                JSONObject()
                    .put("name", "codex-zh-android")
                    .put("title", "Codex 中文版 Android")
                    .put("version", "0.1.0")
            )

        val result = request("initialize", initParams, timeoutMillis)
        sendNotification("initialized", JSONObject())
        initialized = true
        listener.onDiagnostic("Codex App Server 初始化握手完成")
        return result
    }

    /** Sends a JSON-RPC request and waits for its matching response. */
    fun request(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS
    ): JSONObject {
        check(isAlive) { "Codex App Server 尚未启动或已经退出" }
        require(method.isNotBlank()) { "方法名不能为空" }

        val id = nextId.getAndIncrement()
        val key = id.toString()
        val response = CompletableFuture<JSONObject>()
        check(pending.putIfAbsent(key, response) == null) { "请求 ID 冲突" }

        try {
            writeMessage(
                JSONObject()
                    .put("id", id)
                    .put("method", method)
                    .put("params", params)
            )
            val message = response.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val error = message.optJSONObject("error")
            if (error != null) {
                throw CodexAppServerException(
                    code = error.optInt("code", 0),
                    message = error.optString("message", "Codex App Server 请求失败"),
                    data = error.opt("data")
                )
            }
            return message.optJSONObject("result") ?: JSONObject()
        } catch (error: TimeoutException) {
            throw IllegalStateException("等待 Codex App Server 响应超时：$method", error)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            pending.remove(key)
        }
    }

    /** Replies to a server-initiated JSON-RPC request (for example an approval prompt). */
    @Synchronized
    fun respond(requestId: Any, result: JSONObject) {
        check(isAlive) { "Codex App Server 尚未启动或已经退出" }
        writeMessage(JSONObject().put("id", requestId).put("result", result))
    }

    /** Rejects a server-initiated JSON-RPC request. */
    @Synchronized
    fun respondError(requestId: Any, code: Int, message: String, data: Any? = null) {
        check(isAlive) { "Codex App Server 尚未启动或已经退出" }
        val error = JSONObject().put("code", code).put("message", message)
        if (data != null) error.put("data", data)
        writeMessage(JSONObject().put("id", requestId).put("error", error))
    }

    /** Sends a JSON-RPC notification; notifications do not have an id. */
    @Synchronized
    fun sendNotification(method: String, params: JSONObject = JSONObject()) {
        check(isAlive) { "Codex App Server 尚未启动或已经退出" }
        check(method.isNotBlank()) { "方法名不能为空" }
        writeMessage(JSONObject().put("method", method).put("params", params))
    }

    private fun writeMessage(message: JSONObject) {
        val target = writer ?: throw IllegalStateException("Codex App Server 输入流尚未就绪")
        synchronized(target) {
            target.write(message.toString())
            target.newLine()
            target.flush()
        }
    }

    private fun readStdout(child: Process) {
        try {
            BufferedReader(InputStreamReader(child.inputStream, Charsets.UTF_8)).use { reader ->
                while (!closed.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val message = try {
                        JSONObject(line)
                    } catch (error: JSONException) {
                        listener.onDiagnostic("收到无法解析的 App Server 消息，已忽略")
                        continue
                    }

                    when {
                        message.has("id") && (message.has("result") || message.has("error")) -> {
                            val key = message.opt("id")?.toString() ?: continue
                            pending[key]?.complete(message)
                        }
                        message.has("id") && message.has("method") -> {
                            listener.onServerRequest(message)
                        }
                        message.has("method") -> listener.onNotification(message)
                        else -> listener.onDiagnostic("收到无法识别的 App Server 消息，已忽略")
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) listener.onDiagnostic("读取 Codex App Server 输出时发生错误")
        } finally {
            if (!closed.get() && child === process) {
                failPending(IllegalStateException("Codex App Server 输出流已关闭"))
            }
        }
    }

    private fun drainStderr(child: Process) {
        try {
            BufferedReader(InputStreamReader(child.errorStream, Charsets.UTF_8)).use { reader ->
                while (!closed.get() && reader.readLine() != null) {
                    // Consume stderr to avoid blocking the child process. Do not
                    // forward raw lines because they may contain private paths or secrets.
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) listener.onDiagnostic("读取 Codex App Server 诊断流失败")
        }
    }

    private fun watchExit(child: Process) {
        try {
            val code = child.waitFor()
            if (!closed.get() && child === process) {
                initialized = false
                failPending(IllegalStateException("Codex App Server 已退出，退出码：$code"))
                listener.onDiagnostic("Codex App Server 进程已退出（退出码：$code）")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        initialized = false
        failPending(IllegalStateException("Codex App Server 连接已关闭"))
        runCatching { writer?.close() }
        writer = null

        val child = process
        process = null
        if (child != null && child.isAlive) {
            child.destroy()
            var finished = false
            val waiter = Thread {
                try {
                    child.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply { isDaemon = true }
            waiter.start()
            try {
                waiter.join(TERMINATION_GRACE_MS)
                finished = !waiter.isAlive
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (!finished) child.destroyForcibly()
        }
        io.shutdownNow()
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val TERMINATION_GRACE_MS = 1_000L
    }
}

internal class CodexAppServerException(
    val code: Int,
    override val message: String,
    val data: Any?
) : Exception(message)

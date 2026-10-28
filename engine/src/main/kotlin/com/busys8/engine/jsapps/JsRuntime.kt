package com.busys8.engine.jsapps

import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputType
import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.ModuleLoader
import com.dokar.quickjs.ModuleNormalizer
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.JsObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Where a running app's console output and fetches go. */
interface JsHost {
    /** Origin that `http://localhost/...` means inside an app, e.g. `http://127.0.0.1:8080`. */
    val localOrigin: String

    fun console(appId: String, level: String, text: String)
}

/**
 * One run of a JS app (services/js_runner): a QuickJS context on its own thread with the
 * firmware's globals (see `jsrunner/prelude.js`), running `scripts/main.js` as an ES module.
 *
 * The run ends when the script has nothing left that keeps it alive (no timers, fetches or
 * input listener), when it throws at the top level, or when [stop] is called. [onFinished]
 * gets null for a clean end, or the error message.
 */
class JsRun(
    val app: JsApp,
    private val storageFile: File,
    private val host: JsHost,
    private val onFinished: (JsRun, String?) -> Unit,
) {
    val appId get() = app.manifest.id
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "js-$appId").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var qjs: QuickJs? = null

    private val sleeps = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val aborts = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val nextSubscription = AtomicLong(1)

    /** The current listen() subscription and its queue; input is consumed only while set. */
    @Volatile private var input: Pair<Long, Channel<JsObject>>? = null

    @Volatile var stopped = false
        private set

    fun start() {
        scope.launch {
            val error = try {
                run()
                null
            } catch (e: CancellationException) {
                null
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            if (error != null) host.console(appId, "error", error)
            qjs?.close()
            qjs = null
            onFinished(this@JsRun, if (stopped) null else error)
            scope.cancel()
            executor.shutdown()
        }
    }

    /**
     * Offers an input event (js_input.c input_callback). Returns true when the app consumed it,
     * i.e. it has a listener and the event is one apps receive.
     */
    fun deliver(event: InputEvent): Boolean {
        val (_, queue) = input ?: return false
        val js = toJs(event) ?: return false
        queue.trySend(js)
        return true
    }

    /** Force-quits the app (long BACK, /api/apps/quit). */
    fun stop() {
        if (stopped) return
        stopped = true
        input?.second?.close()
        input = null
        sleeps.values.forEach { it.complete(false) }
        aborts.values.forEach { it.complete(Unit) }
        qjs?.interruptEvaluation()
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    private suspend fun run() {
        val scripts = File(app.dir, "scripts")
        val main = File(scripts, "main.js")
        if (!main.isFile) error("Filesystem error: ${app.devicePath}/scripts/main.js")
        if (main.length() > MAX_SCRIPT_BYTES) error("Script too large")
        val js = QuickJs.create(dispatcher, moduleLoader(scripts))
        qjs = js
        js.memoryLimit = MEMORY_LIMIT
        bind(js)
        js.evaluate<Any?>(PRELUDE, "prelude.js", false)
        js.evaluate<Any?>(main.readText(), "main.js", true)
    }

    private fun bind(js: QuickJs) {
        js.defineBinding("__host_console", FunctionBinding<Unit> { a -> host.console(appId, a[0] as String, a[1] as String) })

        js.defineBinding("__host_sleep", AsyncFunctionBinding<Boolean> { a ->
            val id = (a[0] as Number).toLong()
            val ms = (a[1] as Number).toLong()
            val cancel = CompletableDeferred<Boolean>().also { sleeps[id] = it }
            try {
                if (stopped) false else withTimeoutOrNull(ms) { cancel.await() } ?: true
            } finally {
                sleeps.remove(id)
            }
        })
        js.defineBinding("__host_cancelSleep", FunctionBinding<Unit> { a -> sleeps[(a[0] as Number).toLong()]?.complete(false) })

        js.defineBinding("__host_inputSubscribe", FunctionBinding<Long> {
            val id = nextSubscription.getAndIncrement()
            input?.second?.close()
            input = if (stopped) null else id to Channel(INPUT_QUEUE_SIZE)
            id
        })
        js.defineBinding("__host_inputNext", AsyncFunctionBinding<JsObject?> { a ->
            val id = (a[0] as Number).toLong()
            val queue = input?.takeIf { it.first == id }?.second ?: return@AsyncFunctionBinding null
            queue.receiveCatching().getOrNull()
        })
        js.defineBinding("__host_inputUnsubscribe", FunctionBinding<Unit> { a ->
            val current = input
            if (current != null && current.first == (a[0] as Number).toLong()) {
                input = null
                current.second.close()
            }
        })

        js.defineBinding("__host_storageLoad", FunctionBinding<String?> { loadStorage() })
        js.defineBinding("__host_storageSave", FunctionBinding<Unit> { a -> saveStorage(a[0] as String) })

        js.defineBinding("__host_utf8Encode", FunctionBinding<ByteArray> { a -> (a[0] as String).toByteArray() })
        js.defineBinding("__host_utf8Decode", FunctionBinding<String> { a -> bytesOf(a[0]).decodeToString() })

        js.defineBinding("__host_fetch", AsyncFunctionBinding<JsObject> { a ->
            val id = (a[0] as Number).toLong()
            val abort = CompletableDeferred<Unit>().also { aborts[id] = it }
            try {
                val request = scope.async(Dispatchers.IO) {
                    http(a[1] as String, a[2] as String, a[3] as String, a[4]?.let(::bytesOf))
                }
                select {
                    request.onAwait { it }
                    abort.onAwait {
                        request.cancel()
                        JsObject(mapOf("aborted" to true))
                    }
                }
            } finally {
                aborts.remove(id)
            }
        })
        js.defineBinding("__host_fetchAbort", FunctionBinding<Unit> { a -> aborts[(a[0] as Number).toLong()]?.complete(Unit) })
    }

    /** js_fetch.c: plain HTTP(S); `localhost` without a port is the bar's own API. */
    private fun http(url: String, method: String, headersJson: String, body: ByteArray?): JsObject {
        val target = runCatching { URI(url) }.getOrNull()
            ?: return JsObject(mapOf("error" to "invalid URL"))
        if (target.scheme != "http" && target.scheme != "https") return JsObject(mapOf("error" to "unsupported scheme"))
        val resolved = if (target.scheme == "http" && target.host in LOCAL_HOSTS && target.port == -1) {
            host.localOrigin + target.rawPath.ifEmpty { "/" } + (target.rawQuery?.let { "?$it" } ?: "")
        } else {
            url
        }
        return runCatching {
            val conn = URI(resolved).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            for (pair in Json.parseToJsonElement(headersJson).jsonArray) {
                val (k, v) = pair.jsonArray.map { it.jsonPrimitive.content }
                if (!k.equals(FETCH_HEADER, ignoreCase = true)) conn.setRequestProperty(k, v)
            }
            conn.setRequestProperty(FETCH_HEADER, FETCH_HEADER_VALUE)
            if (body != null && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val bytes = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            val headers = conn.headerFields.filterKeys { it != null }.flatMap { (k, vs) -> vs.map { listOf(k.lowercase(), it) } }
            conn.disconnect()
            JsObject(
                mapOf(
                    "status" to status,
                    "statusText" to (conn.responseMessage ?: ""),
                    "headers" to Json.encodeToString(headers),
                    "body" to bytes,
                ),
            )
        }.getOrElse { JsObject(mapOf("error" to (it.message ?: it.javaClass.simpleName))) }
    }

    /** js_local_storage.c: `{"format_version":1,"data":{...}}` per app. */
    private fun loadStorage(): String? = runCatching {
        val o = Json.parseToJsonElement(storageFile.readText()) as kotlinx.serialization.json.JsonObject
        o["data"].toString()
    }.getOrNull()

    private fun saveStorage(data: String) {
        storageFile.parentFile?.mkdirs()
        storageFile.writeText("""{"format_version":1,"data":$data}""")
    }

    private fun moduleLoader(scripts: File) = object : ModuleLoader {
        override val normalizer = object : ModuleNormalizer {
            override fun normalize(base: String, name: String) = if (name.startsWith("./") || name.startsWith("../")) {
                File(File(base).parentFile ?: File(""), name).normalize().path
            } else {
                name
            }
        }

        override fun load(name: String): ModuleContent {
            val file = File(scripts, name).canonicalFile
            require(file.path.startsWith(scripts.canonicalPath) && file.isFile) { "Cannot find module '$name'" }
            return ModuleContent.Source(file.readText())
        }
    }

    private fun toJs(event: InputEvent): JsObject? = when (event.key) {
        InputKey.UP, InputKey.DOWN -> if (event.type != InputType.PRESS) {
            null
        } else {
            val cw = event.key == InputKey.UP
            JsObject(mapOf("key" to "encoder", "action" to if (cw) "clockwise" else "counterclockwise", "delta" to if (cw) 1 else -1))
        }
        InputKey.BACK -> if (event.type == InputType.LONG) null else button("back", event.type)
        InputKey.START -> button("start", event.type)
        InputKey.OK -> button("ok", event.type)
        else -> null
    }

    private fun button(key: String, type: InputType) = JsObject(mapOf("key" to key, "action" to type.name.lowercase()))

    private fun bytesOf(v: Any?): ByteArray = when (v) {
        is ByteArray -> v
        is UByteArray -> v.asByteArray()
        else -> throw IllegalArgumentException("expected bytes")
    }

    companion object {
        /** JS_FETCH_EXTRA_HEADER / _VALUE: marks requests from apps for the API allowlist. */
        const val FETCH_HEADER = "X-busybar-fetch"
        const val FETCH_HEADER_VALUE = "js"

        private const val MAX_SCRIPT_BYTES = 250 * 1024
        private const val MEMORY_LIMIT = 32L * 1024 * 1024
        private const val INPUT_QUEUE_SIZE = 16
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")

        private val PRELUDE: String by lazy {
            JsRun::class.java.getResourceAsStream("/jsrunner/prelude.js")!!.use { it.readBytes().decodeToString() }
        }
    }
}

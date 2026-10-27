package com.busys8.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.coroutines.resume

/** One rendered frame of both displays, ARGB. */
class Frame(val seq: Long, val front: IntArray, val back: IntArray)

/**
 * The firmware's own GUI under the API canvas: desktop, built-in apps and the services they
 * drive. Called on the device thread once per frame.
 */
interface SystemUi {
    /** Priority of the foreground app (loader_get_priority), which gates API draws. */
    val loaderPriority: Int

    /** Advances services (timer poll, input repeat) to [nowMs]. */
    fun tick(nowMs: Long)

    /** Paints the current app onto both displays (already cleared to black). */
    fun render(front: Gfx, back: Gfx, nowMs: Long)
}

/**
 * The emulated BUSY Bar: owns the displays and the services drawing on them. All state is
 * confined to [dispatcher], a single thread, mirroring the firmware's per-service event loops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusyDevice(
    assets: AssetSource,
    extRoot: File,
    decoder: ImageDecoder,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val dispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
    val fonts = FontRegistry(assets)
    val storage = DeviceStorage(assets, extRoot)
    val images = ImageLoader(storage, decoder)
    private val parser = DrawRequestParser(images)
    val anims = AnimLoader(storage, decoder)
    private val renderer = CanvasRenderer(fonts, images, anims)

    /** Set once at startup, before [start]. */
    var systemUi: SystemUi? = null
    private var lastLoaderPriority = 0
    val canvas = Canvas({ systemUi?.loaderPriority ?: 0 }, clock)

    private val front = Framebuffer(Display.FRONT.width, Display.FRONT.height)
    private val back = Framebuffer(Display.BACK.width, Display.BACK.height)
    private val _frames = MutableStateFlow(Frame(0, front.pixels.copyOf(), back.pixels.copyOf()))
    val frames: StateFlow<Frame> = _frames

    /** Handles a draw request body, returning the HTTP status and reply error (null on success). */
    suspend fun draw(body: JsonElement?): Pair<Int, String?> = withContext(dispatcher) {
        val request = try {
            parser.parse(body)
        } catch (e: DrawParseException) {
            return@withContext 400 to e.message
        }
        // A deferred draw resolves on a later frame; the timeout covers a stalled frame loop.
        val result = withTimeoutOrNull(Canvas.DEFERRED_TIMEOUT_MS + DEFERRAL_GRACE_MS) {
            suspendCancellableCoroutine { cont -> canvas.show(request) { cont.resume(it) } }
        } ?: CanvasResult.LOW_PRIORITY.also { canvas.dropDeferred() }
        renderNow()
        result.httpCode to result.message
    }

    suspend fun clear(app: String?, ids: List<String>?): Pair<Int, String?> = withContext(dispatcher) {
        val result = canvas.clear(app, ids)
        renderNow()
        result.httpCode to result.message
    }

    /** Runs [block] on the device thread, for input and other events from outside. */
    fun post(scope: CoroutineScope, block: () -> Unit) {
        scope.launch(dispatcher) {
            block()
            renderNow()
        }
    }

    /** Runs the display refresh loop until [scope] is cancelled. */
    fun start(scope: CoroutineScope, frameIntervalMs: Long = 33): Job = scope.launch(dispatcher) {
        while (isActive) {
            renderNow()
            delay(frameIntervalMs)
        }
    }

    private fun renderNow() {
        val now = clock()
        systemUi?.tick(now)
        val priority = systemUi?.loaderPriority ?: 0
        if (priority != lastLoaderPriority) {
            lastLoaderPriority = priority
            canvas.reevaluatePriority()
        }
        canvas.tick()
        front.clear()
        back.clear()
        if (canvas.isOpen) {
            renderer.render(canvas, front, back, now)
        } else {
            systemUi?.render(Gfx(front, fonts), Gfx(back, fonts), now)
        }
        val prev = _frames.value
        if (!prev.front.contentEquals(front.pixels) || !prev.back.contentEquals(back.pixels)) {
            _frames.value = Frame(prev.seq + 1, front.pixels.copyOf(), back.pixels.copyOf())
        }
    }

    private companion object {
        const val DEFERRAL_GRACE_MS = 1000L
    }
}

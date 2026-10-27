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
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.coroutines.resume

/** One rendered frame of both displays, ARGB. */
class Frame(val seq: Long, val front: IntArray, val back: IntArray)

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
    private val renderer = CanvasRenderer(fonts, images)

    /** Priority of the foreground system app; native apps raise it in later phases. */
    @Volatile var loaderPriority = 0
    val canvas = Canvas({ loaderPriority }, clock)

    /** Lines shown on the back display while nothing else is drawn. */
    @Volatile var idleLines: List<String> = emptyList()

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
        val result = suspendCancellableCoroutine { cont -> canvas.show(request) { cont.resume(it) } }
        renderNow()
        result.httpCode to result.message
    }

    suspend fun clear(app: String?, ids: List<String>?): Pair<Int, String?> = withContext(dispatcher) {
        val result = canvas.clear(app, ids)
        renderNow()
        result.httpCode to result.message
    }

    /** Runs the display refresh loop until [scope] is cancelled. */
    fun start(scope: CoroutineScope, frameIntervalMs: Long = 33): Job = scope.launch(dispatcher) {
        while (isActive) {
            renderNow()
            delay(frameIntervalMs)
        }
    }

    private fun renderNow() {
        canvas.tick()
        front.clear()
        back.clear()
        if (canvas.isOpen) {
            renderer.render(canvas, front, back, clock())
        } else {
            drawIdle()
        }
        val prev = _frames.value
        if (!prev.front.contentEquals(front.pixels) || !prev.back.contentEquals(back.pixels)) {
            _frames.value = Frame(prev.seq + 1, front.pixels.copyOf(), back.pixels.copyOf())
        }
    }

    /** Until the native apps arrive, the idle front display scrolls [idleLines] (name and address). */
    private fun drawIdle() {
        if (idleLines.isEmpty()) return
        val font = fonts.get(ApiFont.NORMAL.file)
        val text = idleLines.joinToString(IDLE_SEPARATOR)
        val clip = CanvasRenderer.Clip(0, 0, Display.FRONT.width, Display.FRONT.height)
        val top = (Display.FRONT.height - font.lineHeight) / 2
        val width = font.textWidth(text)
        if (width <= Display.FRONT.width) {
            renderer.drawLine(front, clip, font, text, (Display.FRONT.width - width) / 2, top, IDLE_TEXT)
            return
        }
        val period = width + font.textWidth(IDLE_SEPARATOR)
        val shift = ((clock() * IDLE_SCROLL_PX_PER_S / 1000) % period).toInt()
        renderer.drawLine(front, clip, font, text, -shift, top, IDLE_TEXT)
        renderer.drawLine(front, clip, font, text, period - shift, top, IDLE_TEXT)
    }

    private companion object {
        const val IDLE_TEXT = 0xFF606060.toInt()
        const val IDLE_SEPARATOR = "   "
        const val IDLE_SCROLL_PX_PER_S = 12L
    }
}

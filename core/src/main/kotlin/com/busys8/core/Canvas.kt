package com.busys8.core

/** Outcomes of canvas operations, with the HTTP status and message api_display.c replies with. */
enum class CanvasResult(val httpCode: Int, val message: String?) {
    OK(200, null),
    BAD_PARAMETERS(400, "Unspecified request error"),
    LOW_PRIORITY(409, "Not drawn due to low priority"),
    EMPTY_SCREEN(400, "Nothing to display"),
    TOO_MANY_ELEMENTS(400, "Elements number limit exceeded"),
    NONEXISTENT_ELEMENT_ID(400, "one of element_ids is non-existent"),
    WRONG_APP_ID(400, "this application_name is currently not displaying anything"),
}

/**
 * Port of the firmware canvas service (services/canvas/canvas.c): the overlay that draw-API
 * clients paint on, with per-app ownership, priorities against the running system app,
 * per-element timeouts and a short deferral queue for requests that lose on priority.
 *
 * Not thread-safe; callers serialize access (the device runs it on one event loop).
 */
class Canvas(
    /** Priority of the foreground system app (loader_get_priority): 0 idle/off, 10 apps, 90 sessions. */
    private val loaderPriority: () -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    class Widget(val element: Element, val createdAtMs: Long, val expiresAtMs: Long?)

    /** Whether the overlay is open (`canvas->gui != NULL`). */
    var isOpen = false
        private set
    var appId: String? = null
        private set
    private var priority = 0
    private val widgets = LinkedHashMap<String, Widget>()
    var clearOnBack = false

    private class Deferred(val request: DrawRequest, val deadlineMs: Long, val callback: (CanvasResult) -> Unit)

    private var deferred: Deferred? = null

    /** Widgets in paint order (ascending z-index, stable). */
    fun widgets(): List<Widget> = widgets.values.sortedBy { it.element.zIndex }

    /** Draws now, or defers up to [DEFERRED_TIMEOUT_MS] when outranked; [callback] gets the outcome. */
    fun show(request: DrawRequest, callback: (CanvasResult) -> Unit) {
        tick()
        if (rejected(request.applicationName, request.priority)) {
            deferred?.callback?.invoke(CanvasResult.LOW_PRIORITY)
            deferred = Deferred(request, nowMs() + DEFERRED_TIMEOUT_MS, callback)
        } else {
            callback(doDraw(request))
        }
    }

    /** DELETE /api/display/draw. A null [app] clears regardless of owner; null [ids] clears everything. */
    fun clear(app: String?, ids: List<String>?): CanvasResult {
        tick()
        if (!isOpen) return if (ids != null) CanvasResult.NONEXISTENT_ELEMENT_ID else CanvasResult.OK
        if (app != null && appId != null && app != appId) return CanvasResult.WRONG_APP_ID
        return clearIds(ids)
    }

    /** A mode-switch press: the canvas exits (CanvasSrvEventExit). */
    fun exit() {
        if (isOpen) clearIds(null)
    }

    /** A short BACK press closes the overlay only when an app asked for it. */
    fun backPressed() {
        if (isOpen && clearOnBack) clearIds(null)
    }

    /** The loader's priority changed (an app started or a session began). */
    fun reevaluatePriority() {
        if (isOpen && priority < loaderPriority()) clearIds(null)
        tryDeferred()
    }

    /** Expires timed-out widgets and resolves the deferred request. Call before each frame. */
    fun tick() {
        val now = nowMs()
        val expired = widgets.values.filter { it.expiresAtMs != null && it.expiresAtMs <= now }
        for (w in expired) widgets.remove(w.element.id)
        if (expired.isNotEmpty() && widgets.isEmpty()) close()
        deferred?.let { d ->
            if (now >= d.deadlineMs) {
                deferred = null
                d.callback(CanvasResult.LOW_PRIORITY)
            } else {
                tryDeferred()
            }
        }
    }

    private fun tryDeferred() {
        val d = deferred ?: return
        if (rejected(d.request.applicationName, d.request.priority)) return
        deferred = null
        d.callback(doDraw(d.request))
    }

    private fun rejected(app: String, requested: Int): Boolean {
        val loader = loaderPriority()
        val current = if (!isOpen || loader > priority) loader else priority
        val sameApp = isOpen && appId == app
        return if (!isOpen || sameApp) requested < current else requested <= current
    }

    private fun doDraw(request: DrawRequest): CanvasResult {
        val now = nowMs()
        if (!isOpen) {
            val anyVisible = request.elements.any { it.displayUntil <= 0 || it.displayUntil - now / 1000 > 0 }
            if (!anyVisible) return CanvasResult.EMPTY_SCREEN
            isOpen = true
        }
        if (appId != request.applicationName) {
            widgets.clear()
            appId = request.applicationName
        }
        priority = request.priority
        var result = CanvasResult.OK
        for (element in request.elements) {
            if (!update(element, now)) {
                result = CanvasResult.BAD_PARAMETERS
                break
            }
            if (widgets.size > DrawRequestParser.MAX_ELEMENTS) {
                result = CanvasResult.TOO_MANY_ELEMENTS
                break
            }
        }
        if (widgets.isEmpty()) close()
        return result
    }

    /** canvas_element_update: same id must keep type and display; a zero effective timeout removes it. */
    private fun update(element: Element, now: Long): Boolean {
        val old = widgets[element.id]
        if (old != null && (old.element.content.kind != element.content.kind || old.element.display != element.display)) {
            return false
        }
        val effectiveTimeoutS: Long = when {
            element.timeoutS > 0 -> element.timeoutS.toLong()
            element.displayUntil > 0 -> maxOf(0, element.displayUntil - now / 1000)
            else -> -1
        }
        if (effectiveTimeoutS == 0L) {
            if (old != null) widgets.remove(element.id)
            return true
        }
        val expires = if (effectiveTimeoutS > 0) now + effectiveTimeoutS * 1000 else null
        // Re-sending a text/animation element keeps its animation clock running, as LVGL objects persist.
        val created = old?.createdAtMs?.takeIf { old.element.content == element.content } ?: now
        widgets[element.id] = Widget(element, created, expires)
        return true
    }

    private fun clearIds(ids: List<String>?): CanvasResult {
        if (ids != null) {
            if (ids.any { it !in widgets }) return CanvasResult.NONEXISTENT_ELEMENT_ID
            ids.forEach { widgets.remove(it) }
        } else {
            widgets.clear()
        }
        if (widgets.isEmpty()) close()
        return CanvasResult.OK
    }

    private fun close() {
        isOpen = false
        priority = 0
    }

    companion object {
        const val DEFERRED_TIMEOUT_MS = 1500L
    }
}

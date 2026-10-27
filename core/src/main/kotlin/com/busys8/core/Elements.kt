package com.busys8.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Anchor points; `DEFAULT` behaves as top-left, like the LVGL widget class default. */
enum class Align(val apiName: String?, val hx: Int, val vy: Int) {
    DEFAULT(null, 0, 0),
    TOP_LEFT("top_left", 0, 0),
    TOP_MID("top_mid", 1, 0),
    TOP_RIGHT("top_right", 2, 0),
    MID_LEFT("mid_left", 0, 1),
    CENTER("center", 1, 1),
    MID_RIGHT("mid_right", 2, 1),
    BOTTOM_LEFT("bottom_left", 0, 2),
    BOTTOM_MID("bottom_mid", 1, 2),
    BOTTOM_RIGHT("bottom_right", 2, 2);

    /** Top-left corner of a [w]×[h] box whose anchor sits at ([x], [y]). */
    fun origin(x: Int, y: Int, w: Int, h: Int) = Pair(x - w * hx / 2, y - h * vy / 2)
}

enum class RectFill { NONE, SOLID, GRADIENT_H, GRADIENT_V }

/** One parsed draw element (CanvasElement in canvas.h). */
data class Element(
    val id: String,
    val timeoutS: Int,
    val displayUntil: Long,
    val x: Int,
    val y: Int,
    val display: Display,
    val align: Align,
    val zIndex: Int,
    val content: Content,
) {
    sealed interface Content {
        /** Element type identity for the "same id must keep its type" rule. */
        val kind: String
    }

    data class Text(
        val text: String,
        val font: ApiFont,
        val color: Int,
        val width: Int,
        val scrollRate: Int,
        val scrollStartDelay: Int,
        val scrollRepeatDelay: Int,
    ) : Content { override val kind get() = "text" }

    data class Image(val path: String, val opacity: Int) : Content { override val kind get() = "image" }

    data class Animation(
        val path: String,
        val section: String,
        val loop: Boolean,
        val awaitPreviousEnd: Boolean,
        val opacity: Int,
    ) : Content { override val kind get() = "animation" }

    data class Countdown(val timestamp: Long, val color: Int, val timeSince: Boolean, val alwaysHours: Boolean) :
        Content { override val kind get() = "countdown" }

    data class Rectangle(
        val width: Int,
        val height: Int,
        val radius: Int,
        val fill: RectFill,
        val fillColors: Pair<Int, Int>,
        val borderWidth: Int,
        val borderColor: Int,
    ) : Content { override val kind get() = "rectangle" }

    /** A decoded XPM bitmap (CanvasElementTypeRawImage). */
    data class Raw(val image: RawImage, val opacity: Int) : Content { override val kind get() = "raw" }
}

class DrawRequest(
    val applicationName: String,
    val priority: Int,
    val ledColor: Int?,
    val elements: List<Element>,
)

class DrawParseException(message: String) : Exception(message)

/**
 * Parses `POST /api/display/draw` bodies with the same checks, defaults and error strings as
 * api_display.c. Image elements are decoded up front, as the firmware does, to reject bad files.
 */
class DrawRequestParser(private val images: ImageLoader) {
    fun parse(body: JsonElement?): DrawRequest {
        val obj = body as? JsonObject ?: throw DrawParseException("Missing application_name")
        val appName = obj.str("application_name") ?: throw DrawParseException("Missing application_name")
        val priority = obj.num("priority")?.toInt() ?: DEFAULT_PRIORITY
        if (priority <= 0) throw DrawParseException("Priority must be >= 1")
        if (priority > MAX_PRIORITY) throw DrawParseException("Priority must be <= 100")
        val led = obj.str("led_notification_color")?.let {
            Argb.parseHexRgba(it) ?: throw DrawParseException("Invalid LED notification color")
        }
        val array = obj["elements"] as? JsonArray ?: throw DrawParseException("Missing or invalid elements array")
        val elements = ArrayList<Element>()
        var defaultZ = 0
        array.forEachIndexed { i, el ->
            val prefix = "elements[$i]"
            val (element, nextZ) = parseElement(el as? JsonObject ?: JsonObject(emptyMap()), appName, defaultZ, prefix)
            defaultZ = nextZ
            elements += element
            if (elements.size > MAX_ELEMENTS) throw DrawParseException("Elements number limit exceeded")
        }
        if (elements.isEmpty()) throw DrawParseException("Elements array is empty")
        return DrawRequest(appName, priority, led, elements)
    }

    private fun parseElement(o: JsonObject, app: String, defaultZ: Int, p: String): Pair<Element, Int> {
        val id = o.str("id") ?: fail(p, missing("id"))
        val timeout = (o.num("timeout")?.toLong() ?: -1).let { if (it > 0) it.toInt() else 0 }
        var nextZ = defaultZ
        val z = if (o.containsKey("z_index")) {
            val v = o.num("z_index")?.toLong() ?: -1
            if (v < 0) fail(p, invalid("z_index", "be >= 0"))
            v.toInt()
        } else {
            nextZ += 10
            defaultZ
        }
        val until = o.str("display_until")?.let { atoll(it) } ?: 0
        if (timeout > 0 && until > 0) fail(p, ".{timeout,display_until}: exactly one of these must be present")
        val x = o.num("x")?.toLong()?.toInt() ?: 0
        val y = o.num("y")?.toLong()?.toInt() ?: 0
        val align = o.str("align")?.let { a ->
            Align.entries.firstOrNull { it.apiName == a }
                ?: fail(p, invalid("align", "be one of: ") + Align.entries.drop(1).joinToString(", ") { "\"${it.apiName}\"" })
        } ?: Align.DEFAULT
        val display = o.str("display")?.let {
            Display.fromApi(it) ?: fail(p, invalid("display", "be one of: \"front\", \"back\""))
        } ?: Display.FRONT
        val type = o.str("type") ?: fail(p, missing("type"))
        val content = when (type) {
            "text" -> parseText(o, p)
            "image" -> parseImage(o, app, display, p)
            "animation" -> parseAnimation(o, app, p)
            "rectangle" -> parseRectangle(o, p)
            "countdown" -> parseCountdown(o, p)
            "xpmbitmap" -> parseXpm(o, display, p)
            else -> fail(p, invalid("type", "be one of: ") + TYPES.joinToString(", ") { "\"$it\"" })
        }
        return Element(id, timeout, until, x, y, display, align, z, content) to nextZ
    }

    private fun parseText(o: JsonObject, p: String): Element.Text {
        val text = o.str("text") ?: fail(p, missing("text"))
        val fontName = o.str("font") ?: fail(p, missing("font"))
        val font = ApiFont.fromApi(fontName) ?: fail(p, ".font value is invalid")
        val color = color(o, "color", p)
        val width = o.num("width")?.let { if (it < 1.0) fail(p, invalid("width", "be >= 1")); it.toInt() } ?: 0
        fun nonNegative(key: String) = o.num(key)?.let { if (it < 0) fail(p, invalid(key, "be >= 0")); it.toInt() } ?: 0
        return Element.Text(
            text, font, color, width,
            nonNegative("scroll_rate"), nonNegative("scroll_start_delay"), nonNegative("scroll_repeat_delay"),
        )
    }

    private fun parseCountdown(o: JsonObject, p: String): Element.Countdown {
        val color = color(o, "color", p)
        val ts = o.str("timestamp") ?: fail(p, missing("timestamp"))
        val direction = o.str("direction") ?: fail(p, missing("direction"))
        val since = when (direction) {
            "time_left" -> false
            "time_since" -> true
            else -> fail(p, invalid("direction", "be one of: ") + "\"time_left\", \"time_since\"")
        }
        val hours = o.str("show_hours") ?: fail(p, missing("show_hours"))
        val always = when (hours) {
            "when_non_zero" -> false
            "always" -> true
            else -> fail(p, invalid("show_hours", "be one of: ") + "\"when_non_zero\", \"always\"")
        }
        return Element.Countdown(atoll(ts), color, since, always)
    }

    private fun opacity(o: JsonObject, p: String): Int {
        val v = o.num("opacity")?.toLong() ?: 100
        if (v < 0 || v > 100) fail(p, invalid("opacity", "be in range [0; 100]"))
        return (v * 255 / 100).toInt()
    }

    /** api_display_draw_parse_image_path: exactly one of path / stock_path. */
    private fun assetPath(o: JsonObject, app: String, animated: Boolean, p: String): String {
        val uploaded = o.str("path")
        val stock = o.str("stock_path")
        if (uploaded != null && stock != null) fail(p, XOR_PATHS)
        if (uploaded != null) {
            val full = DeviceStorage.userAssets(app, uploaded)
            if (!isSanePath(full)) fail(p, invalid("path", "be clean, without path escape sequences"))
            return full
        }
        if (stock != null) {
            val slash = stock.lastIndexOf('/')
            val name = if (slash < 0) "" else stock.substring(slash + 1)
            if (name.isEmpty()) fail(p, ".stock_path value is invalid")
            return if (animated) DeviceStorage.sharedAnim(name) else DeviceStorage.sharedImage(name)
        }
        fail(p, XOR_PATHS)
    }

    private fun parseImage(o: JsonObject, app: String, display: Display, p: String): Element.Image {
        val opacity = opacity(o, p)
        val path = assetPath(o, app, animated = false, p)
        val img = images.load(path) ?: fail(p, ": failed to decode image $path")
        if (img.width > display.canvasWidth || img.height > display.canvasHeight) {
            fail(p, ": image $path exceeds display dimensions ${display.canvasWidth}x${display.canvasHeight}.")
        }
        return Element.Image(path, opacity)
    }

    private fun parseAnimation(o: JsonObject, app: String, p: String): Element.Animation {
        val path = assetPath(o, app, animated = true, p)
        val section = o.str("section") ?: "default"
        val loop = o.bool("loop") ?: false
        val await = o.bool("await_previous_end") ?: false
        return Element.Animation(path, section, loop, await, opacity(o, p))
    }

    private fun parseRectangle(o: JsonObject, p: String): Element.Rectangle {
        val width = o.num("width")?.toLong() ?: -1
        if (width <= 0) fail(p, invalid("width", "be >= 1"))
        val height = o.num("height")?.toLong() ?: -1
        if (height <= 0) fail(p, invalid("height", "be >= 1"))
        val fill = o.str("fill")?.let { f ->
            when (f) {
                "none" -> RectFill.NONE
                "solid" -> RectFill.SOLID
                "gradient_h" -> RectFill.GRADIENT_H
                "gradient_v" -> RectFill.GRADIENT_V
                else -> fail(p, invalid("fill", "be one of: ") + "\"none\", \"solid\", \"gradient_h\", \"gradient_v\"")
            }
        } ?: RectFill.NONE
        val colors = (o["fill_colors"] as? JsonArray).orEmpty()
        fun fillColor(i: Int): Int? = (colors.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
            Argb.parseHexRgba(it) ?: fail(p, invalid("fill_colors[$i]", "be in #RRGGBBAA format"))
        }
        val c0 = fillColor(0)
        val c1 = fillColor(1)
        if ((fill == RectFill.GRADIENT_H || fill == RectFill.GRADIENT_V) && (c0 != null) != (c1 != null)) {
            fail(p, invalid("fill_colors", "have exactly 2 elements with \"gradient_X\" fill value"))
        }
        if (fill == RectFill.SOLID && c1 != null) {
            fail(p, invalid("fill_colors", "have only one element with \"solid\" fill value"))
        }
        val borderWidth = o.num("border_width")?.toLong() ?: 1
        if (borderWidth < 0) fail(p, invalid("border_width", "be >= 0"))
        val radius = o.num("radius")?.toLong() ?: 0
        if (radius < 0) fail(p, invalid("radius", "be >= 0"))
        return Element.Rectangle(
            width.toInt(), height.toInt(), radius.toInt(), fill,
            (c0 ?: WHITE) to (c1 ?: TRANSPARENT), borderWidth.toInt(), color(o, "border_color", p),
        )
    }

    private fun parseXpm(o: JsonObject, display: Display, p: String): Element.Raw {
        val opacity = opacity(o, p)
        val data = o.str("data") ?: fail(p, missing("data"))
        val xpm = Xpm(data)
        val header = xpm.header() ?: fail(p, invalid("data", "have valid XPM header"))
        if (header.colors > 32 || header.cpp > 4) fail(p, invalid("data", "have <= 32 colors and <= 4 chars per pixel"))
        if (header.width > display.canvasWidth || header.height > display.canvasHeight) {
            fail(p, invalid("data", "be no larger than the display") + " (${display.canvasWidth}x${display.canvasHeight})")
        }
        val palette = xpm.colors(header) ?: fail(p, invalid("data", "have valid XPM color table"))
        val image = xpm.pixels(header, palette) ?: fail(p, invalid("data", "have valid XPM pixel data"))
        return Element.Raw(image, opacity)
    }

    private fun color(o: JsonObject, key: String, p: String): Int =
        o.str(key)?.let { Argb.parseHexRgba(it) ?: fail(p, invalid(key, "be in #RRGGBBAA format")) } ?: WHITE

    private fun fail(prefix: String, message: String): Nothing = throw DrawParseException(prefix + message)

    companion object {
        const val DEFAULT_PRIORITY = 50
        const val MAX_PRIORITY = 100
        const val MAX_ELEMENTS = 100
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TRANSPARENT = 0
        private val TYPES = listOf("text", "image", "animation", "rectangle", "countdown", "xpmbitmap")
        private const val XOR_PATHS = ".{path,stock_path}: exactly one of these must be present"

        private fun missing(field: String) = ".$field is required"
        private fun invalid(field: String, expectation: String) = ".$field value is invalid; must $expectation"

        /** C atoll(): leading digits, 0 when none. */
        fun atoll(s: String): Long = s.trim().takeWhile { it.isDigit() || it == '-' }.toLongOrNull() ?: 0

        /** mg_path_is_sane: no `..` path segments. */
        fun isSanePath(path: String) = path.split('/').none { it == ".." }
    }
}

/** Loads and caches decoded images by device path. */
class ImageLoader(private val storage: DeviceStorage, private val decoder: ImageDecoder) {
    private val cache = object : LinkedHashMap<String, RawImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RawImage>) = size > 64
    }

    @Synchronized
    fun load(path: String): RawImage? = cache[path] ?: storage.read(path)?.let(decoder::decode)?.also { cache[path] = it }

    @Synchronized
    fun invalidate(path: String) {
        cache.remove(path)
    }
}

internal fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
internal fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

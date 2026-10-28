package com.busys8

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.FaceDetector
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.busys8.engine.Busybar
import com.busys8.engine.system.Vad
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.Executors

/**
 * Presence: the front camera at low resolution, about two frames a second, through Android's
 * built-in [FaceDetector] (part of the platform, so the APK stays free of proprietary code).
 * It finds upright frontal faces, which is what someone looking at the bar shows. Frames are
 * analysed in memory as a small grayscale image and dropped; nothing is stored or sent.
 */
class PresenceCamera(private val activity: androidx.activity.ComponentActivity, private val bar: Busybar, private val scope: CoroutineScope) {
    private val executor = Executors.newSingleThreadExecutor()
    private val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
    private var detector: FaceDetector? = null
    private var detectorSize = 0 to 0
    private var gray = IntArray(0)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lastFrameMs = 0L
    @Volatile private var sawFace = false

    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (!running) return@addListener
            val p = future.get()
            val a = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(activity as LifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
            }.onFailure { Log.w(TAG, "camera unavailable", it); running = false }
            provider = p
            analysis = a
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        if (!running) return
        running = false
        provider?.unbindAll()
        analysis?.clearAnalyzer()
        analysis = null
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameMs < FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastFrameMs = now
        val bitmap = try {
            uprightGray(image)
        } finally {
            image.close()
        }
        // A FaceDetector is made for one image size; the size only changes with the rotation.
        val d = detector?.takeIf { detectorSize == bitmap.width to bitmap.height }
            ?: FaceDetector(bitmap.width, bitmap.height, MAX_FACES).also {
                detector = it
                detectorSize = bitmap.width to bitmap.height
                Log.i(TAG, "detecting faces in ${bitmap.width}×${bitmap.height} frames")
            }
        val found = d.findFaces(bitmap, faces)
        bitmap.recycle()
        faces.fill(null)
        if ((found > 0) != sawFace) {
            sawFace = found > 0
            Log.i(TAG, if (sawFace) "face seen ($found)" else "no face")
        }
        if (found > 0) bar.device.post(scope) { bar.presence.seen() }
    }

    /**
     * The luma plane, halved to about 320×240 and turned upright ([ImageProxy] rotation), as an
     * RGB_565 bitmap: the input FaceDetector wants (even width).
     */
    private fun uprightGray(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buf = plane.buffer
        val stride = plane.rowStride
        val pixel = plane.pixelStride
        val sw = image.width / 2
        val sh = image.height / 2
        val rotation = image.imageInfo.rotationDegrees
        val (w, h) = if (rotation % 180 == 0) sw to sh else sh to sw
        val outW = w and 1.inv()
        if (gray.size != outW * h) gray = IntArray(outW * h)
        for (y in 0 until h) for (x in 0 until outW) {
            // (x, y) in the upright image → (sx, sy) in the sensor's half-size image.
            val (sx, sy) = when (rotation) {
                90 -> y to (sh - 1 - x)
                180 -> (sw - 1 - x) to (sh - 1 - y)
                270 -> (sw - 1 - y) to x
                else -> x to y
            }
            val v = buf.get(sy * 2 * stride + sx * 2 * pixel).toInt() and 0xFF
            gray[y * outW + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(gray, outW, h, Bitmap.Config.RGB_565)
    }

    private companion object {
        const val TAG = "Presence"
        const val FRAME_INTERVAL_MS = 500L
        const val MAX_FACES = 3
    }
}

/**
 * Voice activity for auto ON CALL: 16 kHz mono from the microphone through [Vad], one decision
 * per 100 ms handed to the engine. Audio stays in a small reused buffer and is never stored.
 */
class VoiceMonitor(private val bar: Busybar, private val scope: CoroutineScope) {
    @Volatile private var thread: Thread? = null

    val running get() = thread != null

    @SuppressLint("MissingPermission") // granted as device owner (Kiosk)
    fun start() {
        if (thread != null) return
        val t = Thread({
            val minBuf = AudioRecord.getMinBufferSize(Vad.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Vad.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Vad.FRAME_SAMPLES * 20))
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            if (record == null) {
                Log.w(TAG, "microphone unavailable")
                thread = null
                return@Thread
            }
            val vad = Vad()
            val frame = ShortArray(Vad.FRAME_SAMPLES)
            var active = 0
            var frames = 0
            var wasTalking = false
            record.startRecording()
            try {
                while (thread === Thread.currentThread()) {
                    var got = 0
                    while (got < frame.size) {
                        val r = record.read(frame, got, frame.size - got)
                        if (r <= 0) break
                        got += r
                    }
                    if (got < frame.size) continue
                    if (vad.frame(frame)) active++
                    if (++frames == FRAMES_PER_DECISION) {
                        val talking = active * 2 > frames
                        if (talking != wasTalking) {
                            wasTalking = talking
                            Log.i(TAG, if (talking) "voice (%.0f dBFS)".format(vad.lastDb) else "quiet")
                        }
                        bar.device.post(scope) { bar.autoOnCall.voice(talking) }
                        active = 0
                        frames = 0
                    }
                }
            } finally {
                record.stop()
                record.release()
                frame.fill(0)
            }
        }, "voice-monitor")
        thread = t
        t.start()
    }

    fun stop() {
        thread = null
    }

    private companion object {
        const val TAG = "VoiceMonitor"
        /** 5 × 20 ms frames = one decision per 100 ms. */
        const val FRAMES_PER_DECISION = 5
    }
}

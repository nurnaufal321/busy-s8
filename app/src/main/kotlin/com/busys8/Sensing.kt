package com.busys8

import android.annotation.SuppressLint
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.Executors

/**
 * Presence: the front camera at low resolution, about two frames a second, through ML Kit's
 * bundled face detector (on-device, no Play Services). Frames are analysed in memory and
 * dropped; nothing is stored or sent.
 */
class PresenceCamera(private val activity: androidx.activity.ComponentActivity, private val bar: Busybar, private val scope: CoroutineScope) {
    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // Small faces too: the bar is seen from across a room.
            .setMinFaceSize(0.05f)
            .build(),
    )
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

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val media = image.image
        if (now - lastFrameMs < FRAME_INTERVAL_MS || media == null) {
            image.close()
            return
        }
        lastFrameMs = now
        detector.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty() != sawFace) {
                    sawFace = faces.isNotEmpty()
                    Log.i(TAG, if (sawFace) "face seen (${faces.size})" else "no face")
                }
                if (faces.isNotEmpty()) bar.device.post(scope) { bar.presence.seen() }
            }
            .addOnCompleteListener { image.close() }
    }

    private companion object {
        const val TAG = "Presence"
        const val FRAME_INTERVAL_MS = 500L
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

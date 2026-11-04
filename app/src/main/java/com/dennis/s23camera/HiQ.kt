package com.dennis.s23camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageProxy
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/*
 * Hi-Q: capture a short burst of RAW frames, merge them (less noise, more
 * real bit depth) and develop the result in-app, then apply the camera look
 * and save a JPEG. The maths lives in RawPipeline (pure Kotlin, unit-tested);
 * this file is the Android/Camera2 glue.
 */

/**
 * Keeps recent Camera2 capture results so each RAW frame can be developed
 * with the ISP's own white balance, colour matrix, lens shading and noise
 * figures. Attached to both the preview and the still captures.
 */
class CaptureResultStore : CameraCaptureSession.CaptureCallback() {

    private val byTimestamp = ConcurrentHashMap<Long, TotalCaptureResult>()

    @Volatile
    var latest: TotalCaptureResult? = null
        private set

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult
    ) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        latest = result
        byTimestamp[ts] = result
        if (byTimestamp.size > 90) {
            val cutoff = ts - 3_000_000_000L
            byTimestamp.keys.removeIf { it < cutoff }
        }
    }

    fun find(timestamp: Long): TotalCaptureResult? = byTimestamp[timestamp]
}

/** Sensor facts from CameraCharacteristics that RAW developing needs. */
class RawStatics(
    val cfa: Int,
    val black: FloatArray,          // per 2x2 position
    val white: Float,
    val forwardMatrix: FloatArray?, // camera -> XYZ D50
    val maxRawWidth: Int            // full RAW width (to detect hardware zoom); 0 if unknown
) {
    companion object {
        fun read(cameraInfo: CameraInfo): RawStatics? =
            try {
                val info = Camera2CameraInfo.from(cameraInfo)
                val cfa = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
                )
                val white = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL
                )
                if (cfa == null || cfa !in 0..3 || white == null) {
                    null
                } else {
                    val pattern = info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN
                    )
                    val black = FloatArray(4) { p ->
                        pattern?.getOffsetForIndex(p and 1, p shr 1)?.toFloat() ?: 0f
                    }
                    val fm = (
                        info.getCameraCharacteristic(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
                            ?: info.getCameraCharacteristic(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
                        )?.let { toFloatArray(it) }
                    val maxRaw = info.getCameraCharacteristic(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )?.getOutputSizes(ImageFormat.RAW_SENSOR)
                        ?.maxByOrNull { it.width }?.width ?: 0
                    RawStatics(cfa, black, white.toFloat(), fm, maxRaw)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reading RAW characteristics failed", e)
                null
            }
    }
}

/** One RAW frame copied out of CameraX (so the ImageProxy can close quickly). */
class RawFrame(
    val width: Int,
    val height: Int,
    val data: ShortArray,
    val timestamp: Long,
    val rotation: Int
)

/** Copies a RAW_SENSOR ImageProxy into a ShortArray, or null if it isn't one. */
fun copyRawFrame(image: ImageProxy): RawFrame? {
    if (image.format != ImageFormat.RAW_SENSOR) return null
    val plane = image.planes[0]
    if (plane.pixelStride != 2) return null

    val w = image.width and 1.inv()   // even dimensions keep the CFA intact
    val h = image.height and 1.inv()
    val out = ShortArray(w * h)
    val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val base = buf.position()
    for (y in 0 until h) {
        buf.position(base + y * plane.rowStride)
        buf.asShortBuffer().get(out, y * w, w)
    }
    return RawFrame(w, h, out, image.imageInfo.timestamp, image.imageInfo.rotationDegrees)
}

/**
 * Merges and develops one Hi-Q burst. [add] runs on the camera executor as
 * each frame arrives; [finish] develops and returns the final pixels.
 */
class HiQProcessor(
    private val statics: RawStatics,
    private val store: CaptureResultStore,
    private val evBias: Float,
    private val zoom: Float = 1f
) {
    class Result(val pixels: IntArray, val width: Int, val height: Int, val rotation: Int)

    private val pool: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))

    private var params: RawPipeline.Params? = null
    private var merger: RawPipeline.BurstMerger? = null
    private var rotation = 0

    // Digital-zoom crop (even) applied to every frame, and the output size.
    private var cropW = 0
    private var cropH = 0
    private var outW = 0
    private var outH = 0

    @Volatile
    private var failure: Throwable? = null

    var frames = 0
        private set

    /** Where the developing metadata came from: frame, preview or none. */
    var metadataSource = "none"
        private set

    fun add(frame: RawFrame) {
        if (failure != null) return
        try {
            val m = merger
            if (m == null) {
                // Output stays the full RAW frame size, whatever the zoom.
                outW = frame.width
                outH = frame.height

                // Some sensors crop RAW for hardware zoom; most deliver full
                // frames. Software-crop only the zoom the hardware didn't.
                val hwZoom =
                    if (statics.maxRawWidth > 0 && frame.width > 0)
                        (statics.maxRawWidth.toFloat() / frame.width).coerceAtLeast(1f)
                    else 1f
                val swZoom = (zoom / hwZoom).coerceAtLeast(1f)
                cropW = RawPipeline.evenClamp((frame.width / swZoom).roundToInt(), frame.width)
                cropH = RawPipeline.evenClamp((frame.height / swZoom).roundToInt(), frame.height)

                val refData = cropFor(frame)
                val p = buildParams(frame, cropW, cropH, refData)
                params = p
                rotation = frame.rotation
                merger = RawPipeline.BurstMerger(p, pool).also { it.addReference(refData) }
            } else {
                val p = params ?: return
                if (m.frames >= RawPipeline.MAX_FRAMES) return
                val data = cropFor(frame)
                if (data.size != p.width * p.height) return
                m.addFrame(data)
            }
            frames++
        } catch (t: Throwable) {
            Log.e(TAG, "Hi-Q frame merge failed", t)
            failure = t
        }
    }

    private fun cropFor(frame: RawFrame): ShortArray =
        if (cropW < frame.width || cropH < frame.height)
            RawPipeline.cropBayerCentered(frame.data, frame.width, frame.height, cropW, cropH)
        else frame.data

    fun finish(lut: LutLook?): Result {
        failure?.let { throw it }
        val m = merger ?: throw IllegalStateException("No Hi-Q frames")
        val p = params ?: throw IllegalStateException("No Hi-Q parameters")

        val merged = m.result()
        merger = null
        val px = RawPipeline.develop(merged, p, pool, RawPipeline.Tone(evBias = evBias))
        RawPipeline.suppressFalseColour(px, p.width, p.height, pool)
        var out = RawPipeline.sharpen(px, p.width, p.height, SHARPEN_AMOUNT, pool)
        if (lut != null && lut.isLoaded) {
            RawPipeline.parallelFor(pool, p.height) { y0, y1 ->
                lut.applyToPixels(out, y0 * p.width, y1 * p.width)
            }
        }
        // Digital zoom: a cropped merge is upscaled back to the full size, so a
        // zoomed Hi-Q shot is the same pixel size as a 1x one.
        if (p.width != outW || p.height != outH) {
            out = RawPipeline.upscale(out, p.width, p.height, outW, outH, pool)
        }
        return Result(out, outW, outH, rotation)
    }

    fun close() {
        pool.shutdown()
    }

    private fun buildParams(
        frame: RawFrame,
        w: Int,
        h: Int,
        data: ShortArray
    ): RawPipeline.Params {
        val own = store.find(frame.timestamp)
        val result = own ?: store.latest
        metadataSource = when {
            own != null -> "frame"
            result != null -> "preview"
            else -> "none"
        }

        val cropped = w < frame.width || h < frame.height
        val cfa = statics.cfa
        val channel = RawPipeline.channelAt(cfa)

        val black = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?.takeIf { it.size == 4 } ?: statics.black
        val white = result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
            ?.takeIf { it > black.max() } ?: statics.white

        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb =
            if (gains != null) FloatArray(4) { p -> gains.getComponent(channel[p]) }
            else RawPipeline.grayWorldGains(data, w, h, cfa, black, white)

        val ccm = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            ?.let { toFloatArray(it) }
            ?.takeIf { RawPipeline.isPlausibleCcm(it) }
            ?: statics.forwardMatrix
                ?.let { RawPipeline.ccmFromForwardMatrix(it) }
                ?.takeIf { RawPipeline.isPlausibleCcm(it) }
            ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        // The shading map covers the full frame; on a centre crop (zoom) it
        // would over-correct the crop's edges, and the centre barely vignettes,
        // so it is only used at 1x.
        val shading = if (cropped) null else
            result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?.let { map ->
                val cols = map.columnCount
                val rows = map.rowCount
                if (cols < 2 || rows < 2) {
                    null
                } else {
                    val g = FloatArray(cols * rows * 4)
                    for (r in 0 until rows) for (c in 0 until cols) for (p in 0 until 4) {
                        g[(r * cols + c) * 4 + p] = map.getGainFactor(channel[p], c, r)
                    }
                    RawPipeline.Shading(cols, rows, g)
                }
            }

        var noiseS: Float? = null
        var noiseO: Float? = null
        val noise = result?.get(CaptureResult.SENSOR_NOISE_PROFILE)
        if (noise != null && noise.size == 4) {
            val s = noise.sumOf { it.first }.toFloat()
            val o = noise.sumOf { it.second }.toFloat()
            if (s > 0f && o > 0f) {
                noiseS = s
                noiseO = o
            }
        }

        Log.i(
            TAG,
            "Hi-Q params ${w}x$h (frame ${frame.width}x${frame.height}, zoom $zoom) cfa=$cfa " +
                "meta=$metadataSource shading=${shading != null} noise=${noiseS != null}"
        )
        return RawPipeline.Params(
            w, h, cfa, black, white, wb, ccm, shading, noiseS, noiseO
        )
    }

    companion object {
        private const val SHARPEN_AMOUNT = 0.6f
    }
}

private const val TAG = "HiQ"

/** Row-major 3x3 from a Camera2 ColorSpaceTransform. */
fun toFloatArray(t: ColorSpaceTransform): FloatArray =
    FloatArray(9) { i -> t.getElement(i % 3, i / 3).toFloat() }

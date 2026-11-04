package com.dennis.s23camera

import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Hi-Q pipeline: merge a burst of RAW_SENSOR frames, then develop the merged
 * Bayer data into an 8-bit ARGB image.
 *
 * Pure Kotlin — no Android types — so it can be unit-tested on a desktop JVM.
 *
 * Per-pixel CFA tables are indexed by 2x2 *position*
 *     p = (y and 1) * 2 + (x and 1)
 * which is the same order as Android's SENSOR_BLACK_LEVEL_PATTERN and
 * SENSOR_DYNAMIC_BLACK_LEVEL.
 */
object RawPipeline {

    const val CFA_RGGB = 0
    const val CFA_GRBG = 1
    const val CFA_GBRG = 2
    const val CFA_BGGR = 3

    /** Most frames a burst may hold (weights are stored in a byte). */
    const val MAX_FRAMES = 15

    private const val REF_WEIGHT = 8
    private const val ACCEPT_LO = 1.2f   // mean |diff| / sigma below this: full merge
    private const val ACCEPT_HI = 2.0f   // above this: reject (motion / misalignment)
    private val SQRT2 = sqrt(2f)

    /** Colour (0 = R, 1 = G, 2 = B) at each 2x2 position. */
    fun colourAt(cfa: Int): IntArray = when (cfa) {
        CFA_RGGB -> intArrayOf(0, 1, 1, 2)
        CFA_GRBG -> intArrayOf(1, 0, 2, 1)
        CFA_GBRG -> intArrayOf(1, 2, 0, 1)
        CFA_BGGR -> intArrayOf(2, 1, 1, 0)
        else -> throw IllegalArgumentException("Unsupported CFA layout $cfa")
    }

    /**
     * For each 2x2 position, the index into Android's [R, G_even, G_odd, B]
     * channel order (COLOR_CORRECTION_GAINS, lens shading map).
     */
    fun channelAt(cfa: Int): IntArray = when (cfa) {
        CFA_RGGB -> intArrayOf(0, 1, 2, 3)
        CFA_GRBG -> intArrayOf(1, 0, 3, 2)
        CFA_GBRG -> intArrayOf(1, 3, 0, 2)
        CFA_BGGR -> intArrayOf(3, 1, 2, 0)
        else -> throw IllegalArgumentException("Unsupported CFA layout $cfa")
    }

    /** Lens shading gain grid: gains[(row * cols + col) * 4 + position]. */
    class Shading(val cols: Int, val rows: Int, val gains: FloatArray) {
        init {
            require(cols >= 2 && rows >= 2 && gains.size == cols * rows * 4)
        }
    }

    /**
     * Everything needed to merge and develop one burst.
     *
     * @param black   black level per 2x2 position, raw DN
     * @param white   white (saturation) level, raw DN
     * @param wbGain  white-balance gain per 2x2 position
     * @param ccm     3x3 row-major: white-balanced camera RGB -> linear sRGB
     * @param noiseS  sum over the 4 CFA channels of the noise model S
     * @param noiseO  sum over the 4 CFA channels of the noise model O
     *                (variance = S * x + O, x normalised 0..1); null = estimate
     */
    class Params(
        val width: Int,
        val height: Int,
        val cfa: Int,
        val black: FloatArray,
        val white: Float,
        val wbGain: FloatArray,
        val ccm: FloatArray,
        val shading: Shading? = null,
        val noiseS: Float? = null,
        val noiseO: Float? = null
    ) {
        init {
            require(width >= 16 && height >= 16 && width % 2 == 0 && height % 2 == 0) {
                "Bad raw size ${width}x$height"
            }
            require(black.size == 4 && wbGain.size == 4 && ccm.size == 9)
            require(white > black.max()) { "White level must exceed black level" }
            colourAt(cfa) // validates the layout
        }
    }

    /** Tone settings for [develop]. */
    class Tone(
        val exposureGain: Float? = null,  // null = automatic
        val evBias: Float = 0f,            // user EV compensation, in stops
        val filmic: Boolean = true,        // false = plain sRGB (for testing)
        // Fitted to Samsung's own JPEG on a real S23 Ultra shot pair: the
        // ISP's colour matrix is "accurate", Samsung adds ~2x on top; 1.6
        // sits between, and the camera looks add their own on top.
        val saturation: Float = 1.6f
    )

    // ---------------------------------------------------------------- utils

    /** Runs block(start, end) over [0, n) across the pool and waits for it. */
    fun parallelFor(pool: ExecutorService?, n: Int, block: (Int, Int) -> Unit) {
        if (pool == null || n < 64) {
            block(0, n)
            return
        }
        val chunks = 48
        val step = (n + chunks - 1) / chunks
        val futures = ArrayList<Future<*>>(chunks)
        var s = 0
        while (s < n) {
            val a = s
            val b = min(n, s + step)
            futures.add(pool.submit { block(a, b) })
            s = b
        }
        for (f in futures) f.get()
    }

    private fun u(v: Short): Int = v.toInt() and 0xFFFF

    private class Img(val w: Int, val h: Int, val d: FloatArray)

    // -------------------------------------------------------------- merging

    /**
     * Aligns each new frame to the reference with a coarse-to-fine global
     * search, then merges it pixel by pixel, rejecting areas that differ by
     * more than the noise allows (moving subjects, misalignment).
     */
    class BurstMerger(private val p: Params, private val pool: ExecutorService?) {

        private val w = p.width
        private val h = p.height
        private val qw = w / 2
        private val qh = h / 2

        private val acc = FloatArray(w * h)
        private val wsum = ByteArray(qw * qh)
        private val scale = FloatArray(4) { 1f / (p.white - p.black[it]) }

        private lateinit var refLuma: Img
        private lateinit var refPyramid: List<Img>
        private var constSigmaD = 0f

        var frames = 0
            private set

        /** Global shift (in 2x2 quads) found for each added frame. */
        val shifts = ArrayList<Pair<Int, Int>>()

        /** Fraction of the image merged for each added frame. */
        val mergedFraction = ArrayList<Float>()

        private var finished = false

        fun addReference(raw: ShortArray) {
            check(frames == 0 && !finished)
            require(raw.size == w * h)

            parallelFor(pool, h) { y0, y1 ->
                for (y in y0 until y1) {
                    val o = y * w
                    for (x in 0 until w) acc[o + x] = (u(raw[o + x]) * REF_WEIGHT).toFloat()
                }
            }
            Arrays.fill(wsum, REF_WEIGHT.toByte())

            refLuma = quadLuma(raw)
            refPyramid = pyramid(refLuma)
            if (p.noiseS == null || p.noiseO == null) {
                constSigmaD = estimateSigma(refLuma) * SQRT2
            }
            frames = 1
        }

        fun addFrame(raw: ShortArray) {
            check(frames >= 1 && !finished)
            check(frames < MAX_FRAMES)
            require(raw.size == w * h)

            val cur = quadLuma(raw)
            val shift = align(refPyramid, pyramid(cur))
            shifts.add(shift)
            val dx = shift.first
            val dy = shift.second

            // |cur - ref| per quad after the shift; NaN where it falls outside.
            val diff = FloatArray(qw * qh)
            parallelFor(pool, qh) { y0, y1 ->
                for (qy in y0 until y1) {
                    val cy = qy + dy
                    for (qx in 0 until qw) {
                        val cx = qx + dx
                        val i = qy * qw + qx
                        diff[i] =
                            if (cx < 0 || cy < 0 || cx >= qw || cy >= qh) Float.NaN
                            else abs(cur.d[cy * qw + cx] - refLuma.d[i])
                    }
                }
            }

            // Robustness weight per quad (0..REF_WEIGHT) from the 3x3 mean |diff|.
            val wq = ByteArray(qw * qh)
            parallelFor(pool, qh) { y0, y1 ->
                for (qy in y0 until y1) {
                    for (qx in 0 until qw) {
                        val i = qy * qw + qx
                        var s = 0f
                        var n = 0
                        var outside = false
                        for (yy in max(0, qy - 1)..min(qh - 1, qy + 1)) {
                            for (xx in max(0, qx - 1)..min(qw - 1, qx + 1)) {
                                val d = diff[yy * qw + xx]
                                if (d.isNaN()) outside = true else {
                                    s += d
                                    n++
                                }
                            }
                        }
                        if (outside || n == 0) continue
                        val sigmaD =
                            if (constSigmaD > 0f) constSigmaD
                            else {
                                val m = refLuma.d[i] / 4f
                                sqrt(2f * max(1e-10f, p.noiseS!! * m + p.noiseO!!))
                            }
                        val e = (s / n) / sigmaD
                        val weight = ((ACCEPT_HI - e) / (ACCEPT_HI - ACCEPT_LO)).coerceIn(0f, 1f)
                        wq[i] = (weight * REF_WEIGHT + 0.5f).toInt().toByte()
                    }
                }
            }

            parallelFor(pool, qh) { y0, y1 ->
                for (qy in y0 until y1) {
                    for (qx in 0 until qw) {
                        val q = qy * qw + qx
                        val k = wq[q].toInt()
                        if (k == 0) continue
                        val dst = qy * 2 * w + qx * 2
                        val src = (qy + dy) * 2 * w + (qx + dx) * 2
                        acc[dst] += (k * u(raw[src])).toFloat()
                        acc[dst + 1] += (k * u(raw[src + 1])).toFloat()
                        acc[dst + w] += (k * u(raw[src + w])).toFloat()
                        acc[dst + w + 1] += (k * u(raw[src + w + 1])).toFloat()
                        wsum[q] = (wsum[q] + k).toByte()
                    }
                }
            }

            var total = 0L
            for (b in wq) total += b
            mergedFraction.add(total.toFloat() / (wq.size.toLong() * REF_WEIGHT))
            frames++
        }

        /** Merged raw values in DN (the internal buffer, returned in place). */
        fun result(): FloatArray {
            check(frames >= 1 && !finished)
            finished = true
            parallelFor(pool, qh) { y0, y1 ->
                for (qy in y0 until y1) {
                    for (qx in 0 until qw) {
                        val inv = 1f / wsum[qy * qw + qx].toInt()
                        val i = qy * 2 * w + qx * 2
                        acc[i] *= inv
                        acc[i + 1] *= inv
                        acc[i + w] *= inv
                        acc[i + w + 1] *= inv
                    }
                }
            }
            return acc
        }

        private fun quadLuma(raw: ShortArray): Img {
            val d = FloatArray(qw * qh)
            parallelFor(pool, qh) { y0, y1 ->
                for (qy in y0 until y1) {
                    val r0 = qy * 2 * w
                    val r1 = r0 + w
                    for (qx in 0 until qw) {
                        val x = qx * 2
                        val a = max(0f, (u(raw[r0 + x]) - p.black[0]) * scale[0])
                        val b = max(0f, (u(raw[r0 + x + 1]) - p.black[1]) * scale[1])
                        val c = max(0f, (u(raw[r1 + x]) - p.black[2]) * scale[2])
                        val e = max(0f, (u(raw[r1 + x + 1]) - p.black[3]) * scale[3])
                        d[qy * qw + qx] = a + b + c + e
                    }
                }
            }
            return Img(qw, qh, d)
        }

        private fun pyramid(base: Img): List<Img> {
            val levels = ArrayList<Img>(4)
            levels.add(base)
            var cur = base
            while (levels.size < 4 && cur.w >= 64 && cur.h >= 64) {
                cur = downsample(cur)
                levels.add(cur)
            }
            return levels
        }

        /**
         * Gaussian pyramid step: 5-tap binomial blur, then take every second
         * pixel. A plain 2x2 average aliases fine repeating detail (fences,
         * fabric, brickwork) into false matches one period away.
         */
        private fun downsample(s: Img): Img {
            val sw = s.w
            val shh = s.h
            val tmp = FloatArray(sw * shh)
            for (y in 0 until shh) {
                val o = y * sw
                for (x in 0 until sw) {
                    val a = s.d[o + max(0, x - 2)]
                    val b = s.d[o + max(0, x - 1)]
                    val c = s.d[o + x]
                    val d = s.d[o + min(sw - 1, x + 1)]
                    val e = s.d[o + min(sw - 1, x + 2)]
                    tmp[o + x] = (a + 4f * b + 6f * c + 4f * d + e) * (1f / 16f)
                }
            }
            val nw = sw / 2
            val nh = shh / 2
            val out = FloatArray(nw * nh)
            for (y in 0 until nh) {
                val sy = 2 * y
                val r0 = max(0, sy - 2) * sw
                val r1 = max(0, sy - 1) * sw
                val r2 = sy * sw
                val r3 = min(shh - 1, sy + 1) * sw
                val r4 = min(shh - 1, sy + 2) * sw
                for (x in 0 until nw) {
                    val sx = 2 * x
                    out[y * nw + x] = (tmp[r0 + sx] + 4f * tmp[r1 + sx] + 6f * tmp[r2 + sx] +
                        4f * tmp[r3 + sx] + tmp[r4 + sx]) * (1f / 16f)
                }
            }
            return Img(nw, nh, out)
        }

        private fun align(ref: List<Img>, cur: List<Img>): Pair<Int, Int> {
            val top = min(ref.size, cur.size) - 1
            var dx = 0
            var dy = 0
            for (level in top downTo 0) {
                if (level != top) {
                    dx *= 2
                    dy *= 2
                }
                // +-4 at finer levels: a half-pixel ambiguity one level up can
                // put the true shift 3 away, and the nearest false match on
                // repeating texture is often exactly there.
                val radius = if (level == top) 12 else 4
                val step = if (level == 0) 2 else 1
                val cap = robustCap(ref[level])
                val side = 2 * radius + 1
                val costs = FloatArray(side * side)
                val cx0 = dx
                val cy0 = dy
                parallelFor(pool, costs.size) { a, b ->
                    for (k in a until b) {
                        costs[k] = sad(
                            ref[level], cur[level],
                            cx0 - radius + k % side, cy0 - radius + k / side, step, cap
                        )
                    }
                }
                var bestCost = Float.MAX_VALUE
                var best = 0
                for (k in costs.indices) {
                    if (costs[k] < bestCost) {
                        bestCost = costs[k]
                        best = k
                    }
                }
                dx = cx0 - radius + best % side
                dy = cy0 - radius + best / side
                debugLog?.invoke("level $level: ${ref[level].w}x${ref[level].h} -> ($dx, $dy) cost=$bestCost")
            }
            return dx to dy
        }

        /** Test hook: receives per-level alignment decisions. */
        var debugLog: ((String) -> Unit)? = null

        /**
         * Truncated L1 cost: each pixel's difference is capped, so a moving
         * subject or a light that changed adds a constant instead of pulling
         * the alignment toward the wrong shift.
         */
        private fun sad(r: Img, c: Img, dx: Int, dy: Int, step: Int, cap: Float): Float {
            val xa = max(r.w / 8, -dx)
            val xb = min(r.w - r.w / 8, c.w - dx)
            val ya = max(r.h / 8, -dy)
            val yb = min(r.h - r.h / 8, c.h - dy)
            if (xb - xa < 8 || yb - ya < 8) return Float.MAX_VALUE
            var s = 0.0
            var n = 0
            var y = ya
            while (y < yb) {
                val ro = y * r.w
                val co = (y + dy) * c.w + dx
                var x = xa
                while (x < xb) {
                    val d = abs(r.d[ro + x] - c.d[co + x])
                    s += if (d > cap) cap else d
                    n++
                    x += step
                }
                y += step
            }
            return (s / n).toFloat()
        }

        /** Cap for [sad]: a quarter of the level's mean brightness. */
        private fun robustCap(l: Img): Float {
            var s = 0.0
            var i = 0
            while (i < l.d.size) {
                s += l.d[i]
                i += 7
            }
            val mean = (s / ((l.d.size + 6) / 7)).toFloat()
            return max(1e-3f, 0.25f * mean)
        }

        /** Robust noise estimate of the quad sums (used without a noise profile). */
        private fun estimateSigma(l: Img): Float {
            val vals = FloatArray(((l.w - 2) / 3 + 1) * ((l.h - 2) / 3 + 1))
            var n = 0
            var y = 1
            while (y < l.h - 1) {
                var x = 1
                while (x < l.w - 1) {
                    val i = y * l.w + x
                    vals[n++] = abs(l.d[i] - 0.5f * (l.d[i - 1] + l.d[i + 1]))
                    x += 3
                }
                y += 3
            }
            if (n == 0) return 1e-3f
            val sorted = vals.copyOf(n)
            sorted.sort()
            val mad = sorted[n / 2]
            return max(1e-4f, mad * 1.4826f / sqrt(1.5f))
        }
    }

    // ------------------------------------------------------------ developing

    /**
     * Develops merged raw data (DN, modified in place) into ARGB pixels:
     * black/white level, lens shading, white balance, demosaic (Malvar-He-
     * Cutler), colour matrix, exposure and tone curve.
     */
    fun develop(
        raw: FloatArray,
        p: Params,
        pool: ExecutorService?,
        tone: Tone = Tone()
    ): IntArray {
        val w = p.width
        val h = p.height
        require(raw.size == w * h)
        val scale = FloatArray(4) { 1f / (p.white - p.black[it]) }
        val sh = p.shading

        // 1. Normalise, lens shading, white balance (in place).
        parallelFor(pool, h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = (y and 1) shl 1
                var fy = 0f
                var r0 = 0
                var r1 = 0
                if (sh != null) {
                    val gy = y * (sh.rows - 1).toFloat() / (h - 1)
                    r0 = min(gy.toInt(), sh.rows - 2)
                    r1 = r0 + 1
                    fy = gy - r0
                }
                for (x in 0 until w) {
                    val pos = row or (x and 1)
                    val i = y * w + x
                    var v = (raw[i] - p.black[pos]) * scale[pos]
                    if (v < 0f) v = 0f else if (v > 1f) v = 1f
                    if (sh != null) {
                        val gx = x * (sh.cols - 1).toFloat() / (w - 1)
                        val c0 = min(gx.toInt(), sh.cols - 2)
                        val fx = gx - c0
                        val g00 = sh.gains[(r0 * sh.cols + c0) * 4 + pos]
                        val g01 = sh.gains[(r0 * sh.cols + c0 + 1) * 4 + pos]
                        val g10 = sh.gains[(r1 * sh.cols + c0) * 4 + pos]
                        val g11 = sh.gains[(r1 * sh.cols + c0 + 1) * 4 + pos]
                        val top = g00 + (g01 - g00) * fx
                        val bot = g10 + (g11 - g10) * fx
                        v *= top + (bot - top) * fy
                    }
                    v *= p.wbGain[pos]
                    raw[i] = if (v > 1f) 1f else v
                }
            }
        }

        val colour = colourAt(p.cfa)
        val m = p.ccm

        // 2. Exposure.
        val gain = (tone.exposureGain ?: autoExposureGain(raw, w, h, colour, m))
            .times(2f.pow(tone.evBias))
            .coerceIn(0.25f, 8f)

        // 3. Combined tone + sRGB encode table on linear [0, TONE_MAX].
        val table = toneTable(tone.filmic)
        val tableScale = (table.size - 1) / TONE_MAX
        val sat = tone.saturation

        val out = IntArray(w * h)
        parallelFor(pool, h) { y0, y1 ->
            val rgb = FloatArray(3)
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    demosaicAt(raw, w, h, x, y, colour, rgb)
                    var r = m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2]
                    var g = m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2]
                    var b = m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2]
                    if (sat != 1f) {
                        val yl = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        r = yl + (r - yl) * sat
                        g = yl + (g - yl) * sat
                        b = yl + (b - yl) * sat
                    }
                    val ri = idx(r * gain, tableScale, table.size)
                    val gi = idx(g * gain, tableScale, table.size)
                    val bi = idx(b * gain, tableScale, table.size)
                    out[y * w + x] =
                        -0x1000000 or (table[ri] shl 16) or (table[gi] shl 8) or table[bi]
                }
            }
        }
        return out
    }

    private const val TONE_MAX = 3f

    private fun idx(v: Float, scale: Float, size: Int): Int {
        if (v <= 0f) return 0
        val i = (v * scale + 0.5f).toInt()
        return if (i >= size) size - 1 else i
    }

    /**
     * linear (0..TONE_MAX, after exposure) -> 8-bit sRGB.
     *
     * Camera curve: small black point, linear up to a knee, then an
     * exponential shoulder that rolls highlights off instead of clipping,
     * sRGB encode, then an S-curve for contrast. Parameters were fitted to
     * Samsung's own JPEG of the same scene (v1 used extended Reinhard, which
     * left whites at ~180 and blacks lifted to ~40).
     */
    private fun toneTable(filmic: Boolean): IntArray {
        val n = 16384
        val t = IntArray(n)
        for (i in 0 until n) {
            val x = i * TONE_MAX / (n - 1)
            var s: Float
            if (filmic) {
                val xb = max(0f, (x - BLACK_POINT) / (1f - BLACK_POINT))
                val y =
                    if (xb < KNEE) xb
                    else KNEE + (1f - KNEE) * (1f - exp(-(xb - KNEE) / (1f - KNEE)))
                s = srgbEncode(min(1f, y))
                val smooth = s * s * (3f - 2f * s)
                s += (smooth - s) * CONTRAST
            } else {
                s = srgbEncode(min(1f, x))
            }
            t[i] = (s * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return t
    }

    private const val BLACK_POINT = 0.02f  // capped: deeper crushes Hi-Q's clean shadows
    private const val KNEE = 0.55f
    private const val CONTRAST = 0.30f
    private const val EXPOSURE_TRIM = 1.30f
    private const val HIGHLIGHT_TARGET = 0.9f  // brightest 1% kept below the shoulder's end
    private const val MAX_PULLBACK = 0.65f     // a lamp in frame mustn't darken everything

    fun srgbEncode(v: Float): Float =
        if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f

    fun srgbDecode(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    /**
     * Scales the scene so its log-average luminance lands on a mid key, then
     * pulls back (by at most MAX_PULLBACK) if that would push the brightest
     * 1% of the frame past the highlight shoulder.
     */
    private fun autoExposureGain(
        raw: FloatArray,
        w: Int,
        h: Int,
        colour: IntArray,
        m: FloatArray
    ): Float {
        var sum = 0.0
        var n = 0
        val samples = FloatArray(((h / 2 + 3) / 4) * ((w / 2 + 3) / 4))
        var qy = 0
        while (qy < h / 2) {
            var qx = 0
            while (qx < w / 2) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (pos in 0 until 4) {
                    val v = raw[(qy * 2 + (pos shr 1)) * w + qx * 2 + (pos and 1)]
                    when (colour[pos]) {
                        0 -> r = v
                        1 -> g += 0.5f * v
                        else -> b = v
                    }
                }
                val lr = m[0] * r + m[1] * g + m[2] * b
                val lg = m[3] * r + m[4] * g + m[5] * b
                val lb = m[6] * r + m[7] * g + m[8] * b
                val yl = max(0f, 0.2126f * lr + 0.7152f * lg + 0.0722f * lb)
                sum += ln(yl + 1e-4)
                if (n < samples.size) samples[n] = yl
                n++
                qx += 4
            }
            qy += 4
        }
        if (n == 0) return 1f
        val logAvg = exp(sum / n).toFloat()
        val keyGain = (KEY / logAvg).coerceIn(0.8f, 2.5f) * EXPOSURE_TRIM

        val count = min(n, samples.size)
        val sorted = samples.copyOf(count)
        sorted.sort()
        val p99 = sorted[min(count - 1, (count * 0.99f).toInt())]
        val protect = if (p99 > 1e-4f) HIGHLIGHT_TARGET / p99 else keyGain
        return max(keyGain * MAX_PULLBACK, min(keyGain, protect))
    }

    private const val KEY = 0.21f

    /** Malvar-He-Cutler 5x5 demosaic at one pixel (reflects at the borders). */
    private fun demosaicAt(
        raw: FloatArray,
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        colour: IntArray,
        out: FloatArray
    ) {
        fun v(dx: Int, dy: Int): Float {
            var xx = x + dx
            var yy = y + dy
            if (xx < 0) xx = -xx else if (xx >= w) xx = 2 * (w - 1) - xx
            if (yy < 0) yy = -yy else if (yy >= h) yy = 2 * (h - 1) - yy
            return raw[yy * w + xx]
        }

        val pos = ((y and 1) shl 1) or (x and 1)
        val c = colour[pos]
        val centre = v(0, 0)

        if (c == 1) {
            // Green site: the colour beside it horizontally vs vertically.
            val hColour = colour[pos xor 1]
            val horiz = (5f * centre +
                4f * (v(-1, 0) + v(1, 0)) -
                (v(-2, 0) + v(2, 0)) -
                (v(-1, -1) + v(1, -1) + v(-1, 1) + v(1, 1)) +
                0.5f * (v(0, -2) + v(0, 2))) / 8f
            val vert = (5f * centre +
                4f * (v(0, -1) + v(0, 1)) -
                (v(0, -2) + v(0, 2)) -
                (v(-1, -1) + v(1, -1) + v(-1, 1) + v(1, 1)) +
                0.5f * (v(-2, 0) + v(2, 0))) / 8f
            out[1] = centre
            if (hColour == 0) {
                out[0] = horiz
                out[2] = vert
            } else {
                out[2] = horiz
                out[0] = vert
            }
        } else {
            val green = (4f * centre +
                2f * (v(-1, 0) + v(1, 0) + v(0, -1) + v(0, 1)) -
                (v(-2, 0) + v(2, 0) + v(0, -2) + v(0, 2))) / 8f
            val other = (6f * centre +
                2f * (v(-1, -1) + v(1, -1) + v(-1, 1) + v(1, 1)) -
                1.5f * (v(-2, 0) + v(2, 0) + v(0, -2) + v(0, 2))) / 8f
            out[1] = green
            if (c == 0) {
                out[0] = centre
                out[2] = other
            } else {
                out[2] = centre
                out[0] = other
            }
        }
        if (out[0] < 0f) out[0] = 0f
        if (out[1] < 0f) out[1] = 0f
        if (out[2] < 0f) out[2] = 0f
    }

    /**
     * False-colour (colour moire) suppression, in place. Demosaicing invents
     * colour in fine repeating detail (fabric, fences, fine text), so chroma
     * is pulled toward neutral where the neighbourhood is BOTH rich in fine
     * luminance detail AND oscillating (the detail flips light/dark several
     * times within 9 px). A single edge — even a hard red/white one — flips
     * once, so real colour edges keep their colour.
     */
    fun suppressFalseColour(px: IntArray, w: Int, h: Int, pool: ExecutorService?) {
        fun luma(c: Int) =
            (77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) shr 8

        // 1. Signed high-frequency luma (luma minus 3x3 Gaussian), clamped.
        val hf = ByteArray(w * h)
        parallelFor(pool, h) { y0, y1 ->
            for (y in max(1, y0) until min(h - 1, y1)) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val c = luma(px[i])
                    val blur = (4 * c +
                        2 * (luma(px[i - 1]) + luma(px[i + 1]) + luma(px[i - w]) + luma(px[i + w])) +
                        luma(px[i - w - 1]) + luma(px[i - w + 1]) +
                        luma(px[i + w - 1]) + luma(px[i + w + 1]) + 8) shr 4
                    hf[i] = (c - blur).coerceIn(-127, 127).toByte()
                }
            }
        }

        // 2. Horizontal 5-tap mean of |hf| (first half of a 5x5 box).
        val rowAvg = ByteArray(w * h)
        parallelFor(pool, h) { y0, y1 ->
            for (y in y0 until y1) {
                val o = y * w
                for (x in 0 until w) {
                    var s = 0
                    for (t in -2..2) s += abs(hf[o + (x + t).coerceIn(0, w - 1)].toInt())
                    rowAvg[o + x] = (s / 5).toByte()
                }
            }
        }

        fun sig(v: Int) = if (v > FC_SIG) 1 else if (v < -FC_SIG) -1 else 0

        // Light/dark flips of significant detail along a line through i.
        fun flips(i: Int, stride: Int, pos: Int, len: Int): Int {
            var n = 0
            for (k in -FC_R until FC_R) {
                val a = pos + k
                if (a < 0 || a + 2 >= len) continue
                val sa = sig(hf[i + k * stride].toInt())
                if (sa == 0) continue
                val sb = sig(hf[i + (k + 1) * stride].toInt())
                if (sa * sb < 0) n++
                else if (sb == 0 && sa * sig(hf[i + (k + 2) * stride].toInt()) < 0) n++
            }
            return n
        }

        // 3. Attenuate chroma where texture is both strong and oscillating.
        parallelFor(pool, h) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    var s = 0
                    for (t in -2..2) s += rowAvg[(y + t).coerceIn(0, h - 1) * w + x].toInt() and 0xFF
                    val energy = s / 5f
                    if (energy <= FC_T0) continue
                    val i = y * w + x
                    val crossings = flips(i, 1, x, w) + flips(i, w, y, h)
                    val oscillation = when {
                        crossings <= 1 -> continue
                        crossings == 2 -> 0.6f
                        else -> 1f
                    }
                    val amount = oscillation *
                        ((energy - FC_T0) / (FC_T1 - FC_T0)).coerceIn(0f, 1f)
                    val f = 1f - amount * (1f - FC_MIN)
                    val c = px[i]
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    val yl = 0.299f * r + 0.587f * g + 0.114f * b
                    val nr = yl + (r - yl) * f
                    val nb = yl + (b - yl) * f
                    val ng = (yl - 0.299f * nr - 0.114f * nb) / 0.587f
                    px[i] = (c and -0x1000000) or
                        ((nr + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((ng + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                        (nb + 0.5f).toInt().coerceIn(0, 255)
                }
            }
        }
    }

    // Tuned on synthetic data: noise/gradients/colour edges measure ~1-3,
    // fine repeating texture ~7-14 (8-bit levels of 5x5 mean |hf|).
    private const val FC_T0 = 4f    // detail energy below this: untouched
    private const val FC_T1 = 11f   // at/above this: full suppression
    private const val FC_MIN = 0.2f // chroma kept at full suppression
    private const val FC_SIG = 3    // |hf| counted as significant detail
    private const val FC_R = 4      // half-width of the 9 px flip window

    /** Unsharp mask (3x3 Gaussian) with coring so flat noise isn't boosted. */
    fun sharpen(src: IntArray, w: Int, h: Int, amount: Float, pool: ExecutorService?): IntArray {
        val dst = IntArray(src.size)
        val coring = 2
        parallelFor(pool, h) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                        dst[i] = src[i]
                        continue
                    }
                    var outPx = -0x1000000
                    var shift = 16
                    while (shift >= 0) {
                        fun ch(j: Int) = (src[j] shr shift) and 0xFF
                        val c = ch(i)
                        val blur = (4 * c +
                            2 * (ch(i - 1) + ch(i + 1) + ch(i - w) + ch(i + w)) +
                            ch(i - w - 1) + ch(i - w + 1) + ch(i + w - 1) + ch(i + w + 1) + 8) shr 4
                        var d = c - blur
                        d = when {
                            d > coring -> d - coring
                            d < -coring -> d + coring
                            else -> 0
                        }
                        val v = (c + amount * d + 0.5f).toInt().coerceIn(0, 255)
                        outPx = outPx or (v shl shift)
                        shift -= 8
                    }
                    dst[i] = outPx
                }
            }
        }
        return dst
    }

    // ------------------------------------------------------------ zoom

    /**
     * Centre-crops a Bayer frame to [cropW]x[cropH] for digital zoom. The crop
     * origin is forced even so the 2x2 CFA phase is preserved and [cfa] stays
     * valid. [cropW]/[cropH] are rounded down to even. Returns the cropped
     * ShortArray; its dimensions are the (evened) cropW/cropH.
     */
    fun cropBayerCentered(
        src: ShortArray,
        w: Int,
        h: Int,
        cropW: Int,
        cropH: Int
    ): ShortArray {
        val cw = (cropW and 1.inv()).coerceIn(2, w)
        val ch = (cropH and 1.inv()).coerceIn(2, h)
        val ox = ((w - cw) / 2) and 1.inv()
        val oy = ((h - ch) / 2) and 1.inv()
        val out = ShortArray(cw * ch)
        var dy = 0
        while (dy < ch) {
            System.arraycopy(src, (oy + dy) * w + ox, out, dy * cw, cw)
            dy++
        }
        return out
    }

    /** Even value nearest to v, at least 2 and at most max. */
    fun evenClamp(v: Int, max: Int): Int = (v and 1.inv()).coerceIn(2, max and 1.inv())

    /**
     * Upscales ARGB pixels to [outW]x[outH] with a separable Catmull-Rom
     * bicubic filter. Used after merge+develop so a cropped (zoomed) frame
     * comes out at the same size as a 1x Hi-Q shot. Alpha is forced opaque.
     */
    fun upscale(
        src: IntArray,
        w: Int,
        h: Int,
        outW: Int,
        outH: Int,
        pool: ExecutorService?
    ): IntArray {
        if (outW == w && outH == h) return src

        // Horizontal pass: w x h -> outW x h (per channel, float).
        val tmpR = FloatArray(outW * h)
        val tmpG = FloatArray(outW * h)
        val tmpB = FloatArray(outW * h)
        val sx = w.toFloat() / outW
        parallelFor(pool, h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                val orow = y * outW
                for (ox in 0 until outW) {
                    val fx = (ox + 0.5f) * sx - 0.5f
                    val ix = floorToInt(fx)
                    val t = fx - ix
                    val w0 = cubic(t + 1f); val w1 = cubic(t)
                    val w2 = cubic(1f - t); val w3 = cubic(2f - t)
                    var r = 0f; var g = 0f; var b = 0f
                    var k = -1
                    while (k <= 2) {
                        val c = src[row + (ix + k).coerceIn(0, w - 1)]
                        val wt = when (k) { -1 -> w0; 0 -> w1; 1 -> w2; else -> w3 }
                        r += wt * ((c ushr 16) and 0xFF)
                        g += wt * ((c ushr 8) and 0xFF)
                        b += wt * (c and 0xFF)
                        k++
                    }
                    tmpR[orow + ox] = r; tmpG[orow + ox] = g; tmpB[orow + ox] = b
                }
            }
        }

        // Vertical pass: outW x h -> outW x outH.
        val out = IntArray(outW * outH)
        val sy = h.toFloat() / outH
        parallelFor(pool, outH) { y0, y1 ->
            for (oy in y0 until y1) {
                val fy = (oy + 0.5f) * sy - 0.5f
                val iy = floorToInt(fy)
                val t = fy - iy
                val w0 = cubic(t + 1f); val w1 = cubic(t)
                val w2 = cubic(1f - t); val w3 = cubic(2f - t)
                val orow = oy * outW
                for (ox in 0 until outW) {
                    var r = 0f; var g = 0f; var b = 0f
                    var k = -1
                    while (k <= 2) {
                        val yy = (iy + k).coerceIn(0, h - 1) * outW + ox
                        val wt = when (k) { -1 -> w0; 0 -> w1; 1 -> w2; else -> w3 }
                        r += wt * tmpR[yy]; g += wt * tmpG[yy]; b += wt * tmpB[yy]
                        k++
                    }
                    out[orow + ox] = -0x1000000 or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    private fun floorToInt(v: Float): Int = if (v >= 0f) v.toInt() else v.toInt() - 1

    // Catmull-Rom cubic kernel (a = -0.5).
    private fun cubic(x: Float): Float {
        val a = -0.5f
        val ax = abs(x)
        return when {
            ax < 1f -> ((a + 2f) * ax - (a + 3f)) * ax * ax + 1f
            ax < 2f -> (((ax - 5f) * ax + 8f) * ax - 4f) * a
            else -> 0f
        }
    }

    // ------------------------------------------------------------ fallbacks

    /** Grey-world white balance per 2x2 position (when the ISP gives none). */
    fun grayWorldGains(
        raw: ShortArray,
        w: Int,
        h: Int,
        cfa: Int,
        black: FloatArray,
        white: Float
    ): FloatArray {
        val colour = colourAt(cfa)
        val sum = DoubleArray(3)
        val cnt = LongArray(3)
        var y = 0
        while (y < h - 1) {
            var x = 0
            while (x < w - 1) {
                for (pos in 0 until 4) {
                    val v = (u(raw[(y + (pos shr 1)) * w + x + (pos and 1)]) - black[pos]) /
                        (white - black[pos])
                    if (v in 0.02f..0.95f) {
                        sum[colour[pos]] += v.toDouble()
                        cnt[colour[pos]]++
                    }
                }
                x += 8
            }
            y += 8
        }
        val mean = DoubleArray(3) { if (cnt[it] > 0) sum[it] / cnt[it] else 0.0 }
        if (mean[0] <= 0.0 || mean[1] <= 0.0 || mean[2] <= 0.0) return FloatArray(4) { 1f }
        val gain = floatArrayOf(
            (mean[1] / mean[0]).toFloat(),
            1f,
            (mean[1] / mean[2]).toFloat()
        )
        return FloatArray(4) { gain[colour[it]].coerceIn(0.25f, 8f) }
    }

    /** CCM from a DNG-style forward matrix (camera -> XYZ D50), to linear sRGB. */
    fun ccmFromForwardMatrix(fm: FloatArray): FloatArray {
        require(fm.size == 9)
        val xyzD50ToSrgb = floatArrayOf(
            3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f, 1.9161415f, 0.0334540f,
            0.0719453f, -0.2289914f, 1.4052427f
        )
        return mul3(xyzD50ToSrgb, fm)
    }

    fun mul3(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]
            o[r * 3 + c] = s
        }
        return o
    }

    /** True if every row sums to ~1 (white stays white), i.e. plausible CCM. */
    fun isPlausibleCcm(m: FloatArray): Boolean {
        if (m.size != 9 || m.any { it.isNaN() || abs(it) > 8f }) return false
        for (r in 0 until 3) {
            val s = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2]
            if (abs(s - 1f) > 0.35f) return false
        }
        return true
    }
}

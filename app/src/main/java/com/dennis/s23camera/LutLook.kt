package com.dennis.s23camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * A camera "look": a 3D LUT (standard .cube file in assets/) applied to the
 * live preview and to saved photos.
 *
 * Current looks:
 *  - fz55_look_v1_1.cube      — Kodak PIXPRO FZ55 character
 *  - canon_g7x3_look_v1.cube  — Canon PowerShot G7 X Mark III character
 *
 *  - Saved photos use [applyToBitmap] (trilinear interpolation — best quality).
 *  - The live preview uses [renderEffect] (an AGSL LUT shader, Android 13+).
 *
 * Both read the same .cube, so the preview and the saved file match.
 */
class LutLook(context: Context, private val assetName: String) {

    // size of the cube (e.g. 33 -> 33x33x33)
    private var size: Int = 0

    // flattened LUT, 3 floats per entry, indexed as ((b*size + g)*size + r)*3
    private var data: FloatArray = FloatArray(0)

    val isLoaded: Boolean get() = size > 0 && data.isNotEmpty()

    init {
        try {
            parseCube(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LUT $assetName", e)
            size = 0
            data = FloatArray(0)
        }
    }

    private fun parseCube(context: Context) {
        var n = 0
        val values = ArrayList<Float>(36000)

        context.assets.open(assetName).bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                when {
                    line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                        n = line.split(Regex("\\s+")).last().toInt()
                    }
                    line.startsWith("TITLE", ignoreCase = true) -> { /* ignore */ }
                    line.startsWith("DOMAIN_MIN", ignoreCase = true) -> { /* ignore */ }
                    line.startsWith("DOMAIN_MAX", ignoreCase = true) -> { /* ignore */ }
                    line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> { /* ignore */ }
                    else -> {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values.add(r); values.add(g); values.add(b)
                            }
                        }
                    }
                }
            }
        }

        if (n <= 0 || values.size != n * n * n * 3) {
            throw IllegalStateException(
                "Bad cube: size=$n entries=${values.size / 3} expected=${n * n * n}"
            )
        }

        size = n
        data = FloatArray(values.size)
        for (i in values.indices) data[i] = values[i]
    }

    // ---- Saved-photo path: trilinear LUT over the whole bitmap ----

    /**
     * Returns a new bitmap with the FZ55 look applied. Runs on the CPU with
     * trilinear interpolation. Call this off the main thread.
     */
    fun applyToBitmap(src: Bitmap): Bitmap {
        if (!isLoaded) return src

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val n = size
        val nm1 = (n - 1).toFloat()

        var i = 0
        while (i < px.size) {
            val c = px[i]
            val a = c and -0x1000000 // 0xFF000000
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF

            // map 0..255 -> 0..n-1 in LUT space
            val rf = r / 255f * nm1
            val gf = g / 255f * nm1
            val bf = b / 255f * nm1

            val r0 = rf.toInt(); val r1 = if (r0 < n - 1) r0 + 1 else r0; val rd = rf - r0
            val g0 = gf.toInt(); val g1 = if (g0 < n - 1) g0 + 1 else g0; val gd = gf - g0
            val b0 = bf.toInt(); val b1 = if (b0 < n - 1) b0 + 1 else b0; val bd = bf - b0

            // 8 corners, trilinear
            var outR = 0f; var outG = 0f; var outB = 0f
            var corner = 0
            while (corner < 8) {
                val xr = if (corner and 1 == 0) r0 else r1
                val xg = if (corner and 2 == 0) g0 else g1
                val xb = if (corner and 4 == 0) b0 else b1
                val wr = if (corner and 1 == 0) 1f - rd else rd
                val wg = if (corner and 2 == 0) 1f - gd else gd
                val wb = if (corner and 4 == 0) 1f - bd else bd
                val weight = wr * wg * wb
                if (weight > 0f) {
                    val idx = ((xb * n + xg) * n + xr) * 3
                    outR += data[idx] * weight
                    outG += data[idx + 1] * weight
                    outB += data[idx + 2] * weight
                }
                corner++
            }

            val or = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val og = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
            val ob = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)

            px[i] = a or (or shl 16) or (og shl 8) or ob
            i++
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ---- Live-preview path: AGSL LUT shader as a RenderEffect (Android 13+) ----

    /**
     * A RenderEffect that applies the LUT to whatever view it's set on.
     * Null below Android 13 (API 33) or if the LUT failed to load — the
     * preview then shows unfiltered, while saved photos still get the look.
     */
    val renderEffect: RenderEffect? by lazy {
        if (!isLoaded || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            null
        } else {
            try {
                buildRenderEffect()
            } catch (e: Exception) {
                Log.e(TAG, "RenderEffect build failed", e)
                null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun buildRenderEffect(): RenderEffect {
        val n = size

        // Pack the 3D LUT into a 2D bitmap: width = n*n (one n-wide tile per
        // blue slice), height = n. Within a tile x = red, y = green.
        val lutBmp = Bitmap.createBitmap(n * n, n, Bitmap.Config.ARGB_8888)
        val lpx = IntArray(n * n * n)
        var bi = 0
        while (bi < n) {
            var gi = 0
            while (gi < n) {
                var ri = 0
                while (ri < n) {
                    val idx = ((bi * n + gi) * n + ri) * 3
                    val r = (data[idx] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (data[idx + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (data[idx + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val x = bi * n + ri
                    val y = gi
                    lpx[y * (n * n) + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
                    ri++
                }
                gi++
            }
            bi++
        }
        lutBmp.setPixels(lpx, 0, n * n, 0, 0, n * n, n)

        val shader = RuntimeShader(agsl(n))
        shader.setInputShader(
            "lut",
            BitmapShader(lutBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        )
        shader.setFloatUniform("n", n.toFloat())

        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    private fun agsl(n: Int): String = """
        uniform shader content;
        uniform shader lut;
        uniform float n;

        half4 main(float2 coord) {
            half4 c = content.eval(coord);
            float nm1 = n - 1.0;

            // nearest-neighbour LUT lookup (preview quality; saved photo is trilinear)
            float ri = floor(clamp(c.r, 0.0, 1.0) * nm1 + 0.5);
            float gi = floor(clamp(c.g, 0.0, 1.0) * nm1 + 0.5);
            float bi = floor(clamp(c.b, 0.0, 1.0) * nm1 + 0.5);

            float x = bi * n + ri + 0.5;
            float y = gi + 0.5;

            half4 mapped = lut.eval(float2(x, y));
            return half4(mapped.rgb, c.a);
        }
    """.trimIndent()

    companion object {
        private const val TAG = "LutLook"
    }
}

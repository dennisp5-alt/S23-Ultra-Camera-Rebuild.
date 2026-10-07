package com.dennis.s23camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.dennis.s23camera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var gridEnabled = false
    private var currentMode = "Photo"

    private var minZoomRatio = 1f
    private var maxZoomRatio = 1f
    private var currentZoomRatio = 1f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupControls()
        updateUi()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupControls() {
        binding.captureButton.setOnClickListener { captureWithTimer() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.flashButton.setOnClickListener { toggleFlash() }
        binding.timerButton.setOnClickListener { toggleTimer() }
        binding.gridButton.setOnClickListener { toggleGrid() }

        binding.modePhotoButton.setOnClickListener { setMode("Photo") }
        binding.modePortraitButton.setOnClickListener { setMode("Portrait") }
        binding.modeNightButton.setOnClickListener { setMode("Night") }
        binding.modeButton.setOnClickListener { cycleMode() }

        binding.zoomSlider.max = 100
        binding.zoomSlider.progress = 0

        binding.zoomSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser) return

                    val target = sliderProgressToZoom(progress)
                    camera?.cameraControl?.setZoomRatio(target)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .build()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()

                camera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageCapture
                )

                observeZoomState()
                updateUi()

            } catch (e: Exception) {
                Log.e(TAG, "Camera startup failed", e)

                Toast.makeText(
                    this,
                    "Unable to start the camera.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeZoomState() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            if (state == null) return@observe

            minZoomRatio = state.minZoomRatio
            maxZoomRatio = state.maxZoomRatio
            currentZoomRatio = state.zoomRatio

            binding.zoomSlider.progress =
                zoomToSliderProgress(currentZoomRatio)

            binding.zoomLabel.text =
                String.format(Locale.US, "%.1fx", currentZoomRatio)
        }
    }

    private fun sliderProgressToZoom(progress: Int): Float {
        if (maxZoomRatio <= minZoomRatio) {
            return minZoomRatio
        }

        val fraction = progress.coerceIn(0, 100) / 100f
        return minZoomRatio + ((maxZoomRatio - minZoomRatio) * fraction)
    }

    private fun zoomToSliderProgress(zoom: Float): Int {
        if (maxZoomRatio <= minZoomRatio) {
            return 0
        }

        val fraction =
            (zoom - minZoomRatio) / (maxZoomRatio - minZoomRatio)

        return (fraction * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun switchCamera() {
        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        currentZoomRatio = 1f
        startCamera()
    }

    private fun toggleFlash() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) {
            Toast.makeText(
                this,
                "Flash is not available on this camera.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        flashMode =
            when (flashMode) {
                ImageCapture.FLASH_MODE_OFF ->
                    ImageCapture.FLASH_MODE_AUTO

                ImageCapture.FLASH_MODE_AUTO ->
                    ImageCapture.FLASH_MODE_ON

                else ->
                    ImageCapture.FLASH_MODE_OFF
            }

        imageCapture?.flashMode = flashMode
        updateUi()
    }

    private fun toggleTimer() {
        timerSeconds =
            when (timerSeconds) {
                0 -> 3
                3 -> 5
                else -> 0
            }

        updateUi()
    }

    private fun toggleGrid() {
        gridEnabled = !gridEnabled

        binding.gridOverlay.alpha =
            if (gridEnabled) 1f else 0f

        updateUi()
    }

    private fun setMode(mode: String) {
        currentMode = mode

        val selected = 0xFF242428.toInt()
        val transparent = 0x00000000

        binding.modePhotoButton.setBackgroundColor(
            if (mode == "Photo") selected else transparent
        )

        binding.modePortraitButton.setBackgroundColor(
            if (mode == "Portrait") selected else transparent
        )

        binding.modeNightButton.setBackgroundColor(
            if (mode == "Night") selected else transparent
        )

        updateUi()
    }

    private fun cycleMode() {
        val modes = listOf("Photo", "Portrait", "Night")
        val next = (modes.indexOf(currentMode) + 1) % modes.size
        setMode(modes[next])
    }

    private fun updateUi() {
        binding.flashButton.text =
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "Flash Auto"
                ImageCapture.FLASH_MODE_ON -> "Flash On"
                else -> "Flash Off"
            }

        binding.timerButton.text =
            if (timerSeconds == 0) {
                "Timer Off"
            } else {
                "Timer ${timerSeconds}s"
            }

        binding.gridButton.text =
            if (gridEnabled) "Grid On" else "Grid Off"

        binding.modeButton.text = currentMode

        binding.switchCameraButton.text =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                "Rear"
            } else {
                "Front"
            }

        binding.zoomLabel.text =
            String.format(Locale.US, "%.1fx", currentZoomRatio)

        binding.statusText.text =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                "Rear camera • $currentMode"
            } else {
                "Front camera • $currentMode"
            }

        binding.gridOverlay.alpha =
            if (gridEnabled) 1f else 0f
    }

    private fun captureWithTimer() {
        if (timerSeconds == 0) {
            takePhoto()
            return
        }

        binding.captureButton.isEnabled = false
        runCountdown(timerSeconds)
    }

    private fun runCountdown(seconds: Int) {
        if (seconds <= 0) {
            binding.statusText.text = "Capturing…"
            takePhoto()
            return
        }

        binding.statusText.text = "Capturing in $seconds"

        mainHandler.postDelayed({
            runCountdown(seconds - 1)
        }, 1000L)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            binding.captureButton.isEnabled = true
            return
        }

        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(System.currentTimeMillis())

        val fileName = "S23U_$timestamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Pictures/S23UltraCamera"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val options =
            ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()

        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    val uri = outputFileResults.savedUri

                    if (uri != null) {
                        finishPendingMedia(uri)
                        loadThumbnail(uri)
                    }

                    runOnUiThread {
                        binding.captureButton.isEnabled = true
                        updateUi()

                        Toast.makeText(
                            this@MainActivity,
                            "Photo saved.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onError(
                    exception: ImageCaptureException
                ) {
                    Log.e(TAG, "Photo capture failed", exception)

                    runOnUiThread {
                        binding.captureButton.isEnabled = true
                        updateUi()

                        Toast.makeText(
                            this@MainActivity,
                            "Capture failed.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    private fun finishPendingMedia(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        val update = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }

        contentResolver.update(uri, update, null, null)
    }

    private fun loadThumbnail(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            var sample = 1

            while (
                bounds.outWidth / sample > 256 ||
                bounds.outHeight / sample > 256
            ) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
            }

            val bitmap =
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

            if (bitmap != null) {
                runOnUiThread {
                    binding.thumbnailImage.setImageBitmap(bitmap)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail decode failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "S23UltraCamera"
    }
}

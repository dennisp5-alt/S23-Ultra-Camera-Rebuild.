package com.dennis.s23camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.dennis.s23camera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var extensionsInitialized = false

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var gridEnabled = false
    private var currentMode = "Photo"
    private var activeModeBackend = "Photo"
    private var usingOemExtension = false

    private var minZoomRatio = 1f
    private var maxZoomRatio = 1f
    private var currentZoomRatio = 1f

    private var exposureLower = 0
    private var exposureUpper = 0
    private var exposureStep = 0f
    private var currentExposureIndex = 0
    private var camera2Control: Camera2CameraControl? = null

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
        updateModeButtons()
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

        binding.zoom06Button.setOnClickListener { setZoomShortcut(0.6f) }
        binding.zoom1Button.setOnClickListener { setZoomShortcut(1f) }
        binding.zoom3Button.setOnClickListener { setZoomShortcut(3f) }
        binding.zoom10Button.setOnClickListener { setZoomShortcut(10f) }

        binding.evResetButton.setOnClickListener { resetExposure() }

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

        binding.evSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser) return

                    val index = exposureLower + progress

                    if (index < exposureLower || index > exposureUpper) {
                        return
                    }

                    currentExposureIndex = index
                    updateExposureLabel()
                    applyExposureIndex(index)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )

        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAndMeterAt(event.x, event.y)
            }
            true
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                cameraProvider = future.get()
                initializeExtensionsAndBind()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider startup failed", e)
                showCameraStartupError()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initializeExtensionsAndBind() {
        val provider = cameraProvider ?: return

        if (extensionsInitialized) {
            bindCamera()
            return
        }

        extensionsInitialized = true

        try {
            val future =
                ExtensionsManager.getInstanceAsync(this, provider)

            future.addListener({
                try {
                    extensionsManager = future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "Camera extensions unavailable", e)
                    extensionsManager = null
                }

                bindCamera()
            }, ContextCompat.getMainExecutor(this))

        } catch (e: Exception) {
            Log.w(TAG, "Camera extensions initialization failed", e)
            extensionsManager = null
            bindCamera()
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        try {
            camera?.cameraInfo?.zoomState?.removeObservers(this)

            val baseSelector =
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

            val selector =
                resolveCameraSelector(baseSelector)

            val rotation =
                binding.previewView.display?.rotation
                    ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.setSurfaceProvider(
                        binding.previewView.surfaceProvider
                    )
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                )
                .setTargetRotation(rotation)
                .setFlashMode(flashMode)
                .build()

            provider.unbindAll()

            camera = provider.bindToLifecycle(
                this,
                selector,
                preview,
                imageCapture
            )

            observeZoomState()
            configureExposureControl()
            updateUi()

        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)

            if (currentMode != "Photo") {
                currentMode = "Photo"
                activeModeBackend = "Photo"
                updateModeButtons()

                mainHandler.post {
                    bindCamera()
                }

                Toast.makeText(
                    this,
                    "That camera mode was unavailable. Returned to Photo.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showCameraStartupError()
            }
        }
    }

    private fun resolveCameraSelector(
        baseSelector: CameraSelector
    ): CameraSelector {

        val requestedExtension =
            when (currentMode) {
                "Portrait" -> ExtensionMode.BOKEH
                "Night" -> ExtensionMode.NIGHT
                else -> null
            }

        if (requestedExtension == null) {
            usingOemExtension = false
            activeModeBackend = "Photo"
            return baseSelector
        }

        val manager = extensionsManager

        if (
            manager != null &&
            manager.isExtensionAvailable(
                baseSelector,
                requestedExtension
            )
        ) {
            usingOemExtension = true
            activeModeBackend =
                if (currentMode == "Portrait") {
                    "Portrait • OEM bokeh"
                } else {
                    "Night • OEM"
                }

            return manager.getExtensionEnabledCameraSelector(
                baseSelector,
                requestedExtension
            )
        }

        usingOemExtension = false
        activeModeBackend =
            currentMode + " • standard"

        return baseSelector
    }

    private fun showCameraStartupError() {
        Toast.makeText(
            this,
            "Unable to start the camera.",
            Toast.LENGTH_LONG
        ).show()
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
                String.format(
                    Locale.US,
                    "%.1fx",
                    currentZoomRatio
                )

            updateLensShortcutAvailability()
            highlightNearestLensShortcut()
        }
    }

    private fun updateLensShortcutAvailability() {
        val rear =
            lensFacing == CameraSelector.LENS_FACING_BACK

        binding.lensRow.visibility =
            if (rear) View.VISIBLE else View.GONE

        if (!rear) return

        binding.zoom06Button.isEnabled =
            minZoomRatio <= 0.61f &&
                maxZoomRatio >= 0.6f

        binding.zoom1Button.isEnabled =
            minZoomRatio <= 1f &&
                maxZoomRatio >= 1f

        binding.zoom3Button.isEnabled =
            minZoomRatio <= 3f &&
                maxZoomRatio >= 3f

        binding.zoom10Button.isEnabled =
            minZoomRatio <= 10f &&
                maxZoomRatio >= 10f
    }

    private fun highlightNearestLensShortcut() {
        val buttons = listOf(
            binding.zoom06Button to 0.6f,
            binding.zoom1Button to 1f,
            binding.zoom3Button to 3f,
            binding.zoom10Button to 10f
        )

        buttons.forEach {
            it.first.alpha =
                if (it.first.isEnabled) 0.62f else 0.28f
        }

        val nearest =
            buttons
                .filter { it.first.isEnabled }
                .minByOrNull {
                    abs(currentZoomRatio - it.second)
                }

        nearest?.first?.alpha = 1f
    }

    private fun setZoomShortcut(target: Float) {
        if (lensFacing != CameraSelector.LENS_FACING_BACK) {
            return
        }

        if (
            target < minZoomRatio - 0.01f ||
            target > maxZoomRatio + 0.01f
        ) {
            Toast.makeText(
                this,
                String.format(
                    Locale.US,
                    "%.1fx is not exposed by this camera mode.",
                    target
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        camera?.cameraControl?.setZoomRatio(target)
    }

    private fun sliderProgressToZoom(progress: Int): Float {
        if (maxZoomRatio <= minZoomRatio) {
            return minZoomRatio
        }

        val fraction =
            progress.coerceIn(0, 100) / 100f

        return minZoomRatio +
            ((maxZoomRatio - minZoomRatio) * fraction)
    }

    private fun zoomToSliderProgress(zoom: Float): Int {
        if (maxZoomRatio <= minZoomRatio) {
            return 0
        }

        val fraction =
            (zoom - minZoomRatio) /
                (maxZoomRatio - minZoomRatio)

        return (fraction * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun configureExposureControl() {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.exposureState
        val range = state.exposureCompensationRange

        exposureLower = range.lower
        exposureUpper = range.upper
        exposureStep =
            state.exposureCompensationStep.toFloat()

        currentExposureIndex =
            state.exposureCompensationIndex

        camera2Control =
            if (!usingOemExtension) {
                try {
                    Camera2CameraControl.from(
                        activeCamera.cameraControl
                    )
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Camera2 exposure control unavailable",
                        e
                    )
                    null
                }
            } else {
                null
            }

        val sliderRange =
            (exposureUpper - exposureLower)
                .coerceAtLeast(0)

        binding.evSlider.max = sliderRange

        binding.evSlider.progress =
            (currentExposureIndex - exposureLower)
                .coerceIn(0, sliderRange)

        val supported =
            exposureUpper > exposureLower &&
                camera2Control != null &&
                !usingOemExtension

        binding.evSlider.isEnabled = supported
        binding.evResetButton.isEnabled = supported
        binding.evSlider.alpha =
            if (supported) 1f else 0.35f
        binding.evResetButton.alpha =
            if (supported) 1f else 0.35f

        if (usingOemExtension) {
            binding.evLabel.text = "EV locked"
        } else if (!supported) {
            binding.evLabel.text = "EV unavailable"
        } else {
            updateExposureLabel()
            applyExposureIndex(currentExposureIndex)
        }
    }

    private fun updateExposureLabel() {
        val ev =
            currentExposureIndex * exposureStep

        binding.evLabel.text =
            String.format(
                Locale.US,
                "EV %+.1f",
                ev
            )
    }

    private fun applyExposureIndex(index: Int) {
        if (usingOemExtension) {
            binding.evLabel.text = "EV locked"
            return
        }

        val control = camera2Control ?: run {
            binding.evLabel.text = "EV unavailable"
            return
        }

        val options =
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    index
                )
                .build()

        val future =
            control.setCaptureRequestOptions(options)

        future.addListener({
            try {
                future.get()

                runOnUiThread {
                    if (currentExposureIndex == index) {
                        updateExposureLabel()
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Exposure compensation request failed",
                    e
                )

                runOnUiThread {
                    binding.evLabel.text = "EV failed"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun resetExposure() {
        if (
            exposureUpper < exposureLower ||
            camera2Control == null ||
            usingOemExtension
        ) {
            return
        }

        val zero =
            0.coerceIn(
                exposureLower,
                exposureUpper
            )

        currentExposureIndex = zero

        binding.evSlider.progress =
            (zero - exposureLower)
                .coerceAtLeast(0)

        updateExposureLabel()
        applyExposureIndex(zero)
    }

    private fun focusAndMeterAt(
        x: Float,
        y: Float
    ) {
        val activeCamera = camera ?: return

        val point =
            binding.previewView
                .meteringPointFactory
                .createPoint(x, y)

        val action =
            FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or
                    FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(
                    4,
                    TimeUnit.SECONDS
                )
                .build()

        activeCamera.cameraControl
            .startFocusAndMetering(action)

        showFocusIndicator(x, y)
    }

    private fun showFocusIndicator(
        x: Float,
        y: Float
    ) {
        binding.focusIndicator.animate().cancel()

        val previewLocation = IntArray(2)
        val rootLocation = IntArray(2)

        binding.previewView.getLocationInWindow(
            previewLocation
        )

        binding.root.getLocationInWindow(
            rootLocation
        )

        val parentX =
            previewLocation[0] -
                rootLocation[0] +
                x

        val parentY =
            previewLocation[1] -
                rootLocation[1] +
                y

        binding.focusIndicator.x =
            parentX -
                (binding.focusIndicator.width / 2f)

        binding.focusIndicator.y =
            parentY -
                (binding.focusIndicator.height / 2f)

        binding.focusIndicator.alpha = 1f
        binding.focusIndicator.scaleX = 1.18f
        binding.focusIndicator.scaleY = 1.18f
        binding.focusIndicator.visibility = View.VISIBLE

        binding.focusIndicator
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(0f)
            .setStartDelay(700L)
            .setDuration(250L)
            .withEndAction {
                binding.focusIndicator.visibility = View.GONE
                binding.focusIndicator.alpha = 1f
                binding.focusIndicator.scaleX = 1f
                binding.focusIndicator.scaleY = 1f
            }
            .start()
    }

    private fun switchCamera() {
        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        currentZoomRatio = 1f
        bindCamera()
    }

    private fun toggleFlash() {
        if (currentMode == "Night") {
            Toast.makeText(
                this,
                "Flash is disabled in Night mode.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

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
        if (currentMode == mode) return

        currentMode = mode

        if (mode == "Night") {
            flashMode = ImageCapture.FLASH_MODE_OFF
        }

        updateModeButtons()
        updateUi()
        bindCamera()
    }

    private fun updateModeButtons() {
        applyModeStyle(
            binding.modePhotoButton,
            currentMode == "Photo"
        )

        applyModeStyle(
            binding.modePortraitButton,
            currentMode == "Portrait"
        )

        applyModeStyle(
            binding.modeNightButton,
            currentMode == "Night"
        )
    }

    private fun applyModeStyle(
        view: TextView,
        selected: Boolean
    ) {
        if (selected) {
            view.setBackgroundResource(
                R.drawable.mode_selected_background
            )
            view.setTextColor(0xFFFFFFFF.toInt())
            view.setTypeface(null, Typeface.BOLD)
        } else {
            view.setBackgroundResource(
                android.R.color.transparent
            )
            view.setTextColor(0xFFD9D9D9.toInt())
            view.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun updateUi() {
        val hasFlash =
            camera?.cameraInfo?.hasFlashUnit() == true &&
                currentMode != "Night"

        binding.flashButton.isEnabled = hasFlash
        binding.flashButton.alpha =
            if (hasFlash) 1f else 0.42f

        binding.flashButton.text =
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO ->
                    "Flash Auto"

                ImageCapture.FLASH_MODE_ON ->
                    "Flash On"

                else ->
                    "Flash Off"
            }

        binding.timerButton.text =
            if (timerSeconds == 0) {
                "Timer Off"
            } else {
                "Timer " + timerSeconds + "s"
            }

        binding.gridButton.text =
            if (gridEnabled) {
                "Grid On"
            } else {
                "Grid Off"
            }

        binding.switchCameraButton.text =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                "Front"
            } else {
                "Rear"
            }

        binding.zoomLabel.text =
            String.format(
                Locale.US,
                "%.1fx",
                currentZoomRatio
            )

        val facing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                "Rear"
            } else {
                "Front"
            }

        binding.statusText.text =
            facing + " • " + activeModeBackend

        binding.gridOverlay.alpha =
            if (gridEnabled) 1f else 0f

        updateLensShortcutAvailability()
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

        binding.statusText.text =
            "Capturing in " + seconds

        mainHandler.postDelayed({
            runCountdown(seconds - 1)
        }, 1000L)
    }

    private fun takePhoto() {
        val capture =
            imageCapture ?: run {
                binding.captureButton.isEnabled = true
                return
            }

        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(System.currentTimeMillis())

        val fileName =
            "S23U_" + timestamp + ".jpg"

        val values =
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    fileName
                )

                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "image/jpeg"
                )

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "Pictures/S23UltraCamera"
                    )

                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        1
                    )
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
                    outputFileResults:
                        ImageCapture.OutputFileResults
                ) {
                    val uri =
                        outputFileResults.savedUri

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
                    Log.e(
                        TAG,
                        "Photo capture failed",
                        exception
                    )

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
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {
            return
        }

        val update =
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    0
                )
            }

        contentResolver.update(
            uri,
            update,
            null,
            null
        )
    }

    private fun loadThumbnail(uri: Uri) {
        try {
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

            contentResolver
                .openInputStream(uri)
                ?.use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        bounds
                    )
                }

            var sample = 1

            while (
                bounds.outWidth / sample > 256 ||
                bounds.outHeight / sample > 256
            ) {
                sample *= 2
            }

            val options =
                BitmapFactory.Options().apply {
                    inSampleSize =
                        sample.coerceAtLeast(1)
                }

            val bitmap =
                contentResolver
                    .openInputStream(uri)
                    ?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            options
                        )
                    }

            if (bitmap != null) {
                runOnUiThread {
                    binding.thumbnailImage
                        .setImageBitmap(bitmap)
                }
            }

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Thumbnail decode failed",
                e
            )
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

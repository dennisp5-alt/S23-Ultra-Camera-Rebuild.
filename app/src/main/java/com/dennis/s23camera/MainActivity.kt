package com.dennis.s23camera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.dennis.s23camera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var extensionsInitialized = false
    private var cameraStartRequested = false
    private var cameraPermissionDenied = false

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var camera2Control: Camera2CameraControl? = null

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
    private var zoomSliderTracking = false
    private var gestureHadPinch = false
    private var pinchZoomRatio = 1f

    private var exposureLower = 0
    private var exposureUpper = 0
    private var exposureStep = 0f
    private var currentExposureIndex = 0

    // Restored after a rotation and applied once the new camera binding is live.
    private var pendingZoomRatio: Float? = null
    private var pendingExposureIndex: Int? = null

    private var captureInProgress = false
    private var countdownActive = false
    private var lastSavedUri: Uri? = null

    // Camera looks (3D LUTs in assets/). The look button cycles through them.
    // Off = the original CameraX capture path and an unfiltered preview.
    private enum class Look(val label: String, val asset: String?) {
        KODAK("FZ55", "fz55_look_v1_1.cube"),
        CANON("G7X", "canon_g7x3_look_v1.cube"),
        OFF("Off", null)
    }

    private var currentLook = Look.KODAK
    private val lutCache = HashMap<Look, LutLook>()

    // Hi-Q: a RAW burst merged and developed in-app, still saved as a JPEG.
    // Rear camera, Auto mode, 1x only. Off = the normal capture path.
    private var hiQEnabled = false
    private val captureResults = CaptureResultStore()

    // Loaded on first use and kept; returns null for Off or a failed load.
    private fun lutFor(look: Look): LutLook? {
        val asset = look.asset ?: return null
        return lutCache.getOrPut(look) { LutLook(this, asset) }
            .takeIf { it.isLoaded }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val displayManager: DisplayManager? by lazy {
        getSystemService(DisplayManager::class.java)
    }

    // fullSensor lets the screen flip 180° (landscape <-> reverse landscape)
    // WITHOUT recreating the Activity, so the capture rotation must be updated
    // here, otherwise those photos are saved upside down.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val display = binding.root.display ?: return
            if (display.displayId == displayId) {
                imageCapture?.targetRotation = display.rotation
            }
        }
    }

    private val shutterOverlay: View by lazy { createShutterOverlay() }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraPermissionDenied = false
                startCamera()
            } else {
                cameraPermissionDenied = true
                Toast.makeText(
                    this,
                    "Camera permission is required. Tap the shutter to grant it.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        restoreState(savedInstanceState)
        setupControls()
        updateModeButtons()
        updateLookButton()
        updateHiQButton()
        updateUi()
        restoreLastPhoto()

        if (hasCameraPermission()) {
            startCamera()
        } else if (savedInstanceState == null) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        displayManager?.registerDisplayListener(displayListener, mainHandler)

        // Covers coming back from Settings after granting the permission there.
        if (hasCameraPermission() && !cameraStartRequested) {
            startCamera()
        }
    }

    override fun onStop() {
        displayManager?.unregisterDisplayListener(displayListener)
        cancelCountdown()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Rotating the phone recreates the Activity; without this every
        // setting snapped back to rear camera / Photo / flash off.
        outState.putInt(STATE_LENS, lensFacing)
        outState.putString(STATE_MODE, currentMode)
        outState.putInt(STATE_FLASH, flashMode)
        outState.putInt(STATE_TIMER, timerSeconds)
        outState.putBoolean(STATE_GRID, gridEnabled)
        outState.putFloat(STATE_ZOOM, currentZoomRatio)
        outState.putInt(STATE_EV, currentExposureIndex)
        outState.putString(STATE_LOOK, currentLook.name)
        outState.putBoolean(STATE_HIQ, hiQEnabled)
    }

    private fun restoreState(state: Bundle?) {
        if (state == null) return

        lensFacing = state.getInt(STATE_LENS, lensFacing)
        currentMode = state.getString(STATE_MODE) ?: currentMode
        flashMode = state.getInt(STATE_FLASH, flashMode)
        timerSeconds = state.getInt(STATE_TIMER, timerSeconds)
        gridEnabled = state.getBoolean(STATE_GRID, gridEnabled)
        currentLook =
            state.getString(STATE_LOOK)
                ?.let { name -> Look.values().firstOrNull { it.name == name } }
                ?: currentLook
        hiQEnabled = state.getBoolean(STATE_HIQ, false)

        val zoom = state.getFloat(STATE_ZOOM, 1f)
        pendingZoomRatio = zoom
        currentZoomRatio = zoom
        pendingExposureIndex = state.getInt(STATE_EV, 0)
    }

    private fun setupControls() {
        binding.captureButton.setOnClickListener { onShutterPressed() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.flashButton.setOnClickListener { toggleFlash() }
        binding.timerButton.setOnClickListener { toggleTimer() }
        binding.gridButton.setOnClickListener { toggleGrid() }
        binding.lookButton.setOnClickListener { toggleLook() }
        binding.hiQButton.setOnClickListener { toggleHiQ() }
        binding.thumbnailImage.setOnClickListener { openLastPhoto() }

        binding.modePhotoButton.setOnClickListener { setMode("Photo") }
        binding.modePortraitButton.setOnClickListener { setMode("Portrait") }
        binding.modeNightButton.setOnClickListener { setMode("Night") }
        binding.modeMacroButton.setOnClickListener { setMode("Macro") }

        binding.zoom06Button.setOnClickListener { setZoomShortcut(0.6f) }
        binding.zoom1Button.setOnClickListener { setZoomShortcut(1f) }
        binding.zoom3Button.setOnClickListener { setZoomShortcut(3f) }
        binding.zoom10Button.setOnClickListener { setZoomShortcut(10f) }

        binding.evResetButton.setOnClickListener { resetExposure() }

        binding.zoomSlider.max = ZOOM_SLIDER_STEPS
        binding.zoomSlider.progress = 0

        binding.zoomSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser) return
                    setZoomRatioSafely(sliderProgressToZoom(progress))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    zoomSliderTracking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    zoomSliderTracking = false
                    binding.zoomSlider.progress =
                        zoomToSliderProgress(currentZoomRatio)
                }
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

                    val index =
                        (exposureLower + progress)
                            .coerceIn(exposureLower, exposureUpper)

                    currentExposureIndex = index
                    updateExposureLabel()
                    applyExposureIndex(index)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    gestureHadPinch = true
                    pinchZoomRatio = currentZoomRatio
                    return isZoomAdjustable()
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!isZoomAdjustable()) return false

                    pinchZoomRatio =
                        (pinchZoomRatio * detector.scaleFactor)
                            .coerceIn(minZoomRatio, maxZoomRatio)

                    setZoomRatioSafely(pinchZoomRatio)
                    return true
                }
            }
        )

        binding.previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> gestureHadPinch = false

                MotionEvent.ACTION_UP -> {
                    if (!gestureHadPinch) {
                        if (hasCameraPermission()) {
                            focusAndMeterAt(event.x, event.y)
                        } else {
                            requestCameraPermissionOrOpenSettings()
                        }
                        view.performClick()
                    }
                }
            }
            true
        }
    }

    private fun startCamera() {
        if (cameraStartRequested) return
        cameraStartRequested = true

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                cameraProvider = future.get()
                initializeExtensionsAndBind()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider startup failed", e)
                cameraStartRequested = false
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
        if (isFinishing || isDestroyed) return

        try {
            camera?.cameraInfo?.zoomState?.removeObservers(this)
            camera?.cameraInfo?.cameraState?.removeObservers(this)

            // Camera2 overrides are stored on the camera itself and survive
            // unbind/rebind, so clear the previous mode's overrides first.
            camera2Control?.clearCaptureRequestOptions()
            camera2Control = null

            val baseSelector =
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

            val selector =
                resolveCameraSelector(baseSelector)

            val rotation = currentDisplayRotation()

            val previewBuilder = Preview.Builder()
                .setTargetRotation(rotation)

            val captureBuilder = ImageCapture.Builder()
                .setTargetRotation(rotation)

            if (hiQEnabled) {
                // RAW burst: quick captures, no flash, and per-frame metadata
                // (white balance, colour matrix, lens shading, noise) from
                // both the preview and the stills.
                captureBuilder
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                Camera2Interop.Extender(captureBuilder)
                    .setSessionCaptureCallback(captureResults)
                    .setCaptureRequestOption(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(captureResults)
                    .setCaptureRequestOption(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
            } else {
                captureBuilder
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = captureBuilder.build()

            provider.unbindAll()

            val boundCamera = provider.bindToLifecycle(
                this,
                selector,
                preview,
                imageCapture
            )
            camera = boundCamera

            camera2Control =
                if (usingOemExtension) {
                    null
                } else {
                    try {
                        Camera2CameraControl.from(boundCamera.cameraControl)
                    } catch (e: Exception) {
                        Log.w(TAG, "Camera2 control unavailable", e)
                        null
                    }
                }

            observeZoomState()
            observeCameraState()
            configureExposureControl()
            applyModeSpecificCameraControls()
            applyPreviewLook()
            updateUi()

        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)

            if (hiQEnabled) {
                hiQEnabled = false
                updateHiQButton()
                mainHandler.post { bindCamera() }
                Toast.makeText(
                    this,
                    "Hi-Q isn't available on this camera.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

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
            activeModeBackend =
                when (currentMode) {
                    "Macro" -> "Macro • close focus"
                    else -> if (hiQEnabled) "Auto • Hi-Q" else "Auto"
                }
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

            if (
                currentMode == "Macro" &&
                lensFacing == CameraSelector.LENS_FACING_BACK &&
                abs(currentZoomRatio - minZoomRatio) > 0.01f
            ) {
                camera?.cameraControl?.setZoomRatio(minZoomRatio)
                return@observe
            }

            // Hi-Q zooms by cropping the RAW, so cap it at HIQ_MAX_ZOOM and
            // keep it on the main sensor (no lens switch).
            if (hiQEnabled && currentZoomRatio > effMaxZoom() + 0.01f) {
                camera?.cameraControl?.setZoomRatio(effMaxZoom())
                return@observe
            }

            // One-shot restore of the zoom level after a rotation.
            val restoreZoom = pendingZoomRatio
            if (restoreZoom != null) {
                pendingZoomRatio = null
                if (
                    currentMode != "Macro" &&
                    abs(restoreZoom - currentZoomRatio) > 0.01f
                ) {
                    setZoomRatioSafely(restoreZoom)
                }
            }

            if (!zoomSliderTracking) {
                binding.zoomSlider.progress =
                    zoomToSliderProgress(currentZoomRatio)
            }

            binding.zoomLabel.text =
                String.format(
                    Locale.US,
                    "%.1fx",
                    currentZoomRatio
                )

            updateZoomControlsEnabled()
            updateLensShortcutAvailability()
            highlightNearestLensShortcut()
        }
    }

    private fun observeCameraState() {
        camera?.cameraInfo?.cameraState?.observe(this) { state ->
            val error = state.error

            if (error != null) {
                binding.statusText.text =
                    when (error.code) {
                        CameraState.ERROR_CAMERA_IN_USE,
                        CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                            "Camera busy • close other camera apps"

                        CameraState.ERROR_CAMERA_DISABLED ->
                            "Camera disabled by device policy"

                        else ->
                            "Camera error " + error.code
                    }
                return@observe
            }

            if (state.type == CameraState.Type.OPEN) {
                refreshStatusText()

                // CameraX resets exposure compensation whenever the camera
                // closes (app backgrounded, rebind), so re-apply the user's EV.
                if (currentExposureIndex != 0) {
                    applyExposureIndex(currentExposureIndex)
                }
            }
        }
    }

    private fun isZoomAdjustable(): Boolean =
        camera != null &&
            currentMode != "Macro" &&
            effMaxZoom() > minZoomRatio + 0.01f

    // In Hi-Q the digital zoom is a RAW crop, capped so it stays usable.
    private fun effMaxZoom(): Float =
        if (hiQEnabled) minOf(HIQ_MAX_ZOOM, maxZoomRatio) else maxZoomRatio

    private fun setZoomRatioSafely(target: Float) {
        val top = effMaxZoom()
        if (top <= minZoomRatio) return

        camera?.cameraControl?.setZoomRatio(
            target.coerceIn(minZoomRatio, top)
        )
    }

    private fun updateZoomControlsEnabled() {
        val enabled = isZoomAdjustable()

        binding.zoomSlider.isEnabled = enabled
        binding.zoomSlider.alpha = if (enabled) 1f else 0.35f
    }

    private fun updateLensShortcutAvailability() {
        val rear =
            lensFacing == CameraSelector.LENS_FACING_BACK

        val lensesUsable = rear && currentMode != "Macro" && !hiQEnabled

        binding.lensRow.visibility =
            if (lensesUsable) View.VISIBLE else View.GONE

        if (!lensesUsable) return

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
        if (lensFacing != CameraSelector.LENS_FACING_BACK || hiQEnabled) {
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

        setZoomRatioSafely(target)
    }

    // Logarithmic mapping: equal slider travel = equal zoom *factor*, so the
    // 0.6x–3x range gets as much of the slider as 3x–10x (or 10x–100x).
    private fun sliderProgressToZoom(progress: Int): Float {
        val top = effMaxZoom()
        if (top <= minZoomRatio || minZoomRatio <= 0f) {
            return minZoomRatio
        }

        val fraction =
            progress.coerceIn(0, ZOOM_SLIDER_STEPS) /
                ZOOM_SLIDER_STEPS.toFloat()

        return (minZoomRatio * (top / minZoomRatio).pow(fraction))
            .coerceIn(minZoomRatio, top)
    }

    private fun zoomToSliderProgress(zoom: Float): Int {
        val top = effMaxZoom()
        if (top <= minZoomRatio || minZoomRatio <= 0f) {
            return 0
        }

        val fraction =
            ln(zoom.coerceIn(minZoomRatio, top) / minZoomRatio) /
                ln(top / minZoomRatio)

        return (fraction * ZOOM_SLIDER_STEPS)
            .roundToInt()
            .coerceIn(0, ZOOM_SLIDER_STEPS)
    }

    private fun configureExposureControl() {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.exposureState
        val range = state.exposureCompensationRange

        exposureLower = range.lower
        exposureUpper = range.upper
        exposureStep = state.exposureCompensationStep.toFloat()

        // Reported per binding: OEM extensions that support EV enable it too.
        val supported =
            state.isExposureCompensationSupported &&
                exposureUpper > exposureLower

        val restored = pendingExposureIndex
        pendingExposureIndex = null

        currentExposureIndex =
            if (supported && restored != null) {
                restored.coerceIn(exposureLower, exposureUpper)
            } else {
                0
            }

        val sliderRange =
            (exposureUpper - exposureLower).coerceAtLeast(0)

        binding.evSlider.max = sliderRange
        binding.evSlider.progress =
            (currentExposureIndex - exposureLower)
                .coerceIn(0, sliderRange)

        binding.evSlider.isEnabled = supported
        binding.evResetButton.isEnabled = supported
        binding.evSlider.alpha = if (supported) 1f else 0.35f
        binding.evResetButton.alpha = if (supported) 1f else 0.35f

        when {
            supported -> updateExposureLabel()
            usingOemExtension -> binding.evLabel.text = "EV locked"
            else -> binding.evLabel.text = "EV unavailable"
        }
        // The value itself is applied in observeCameraState() once the
        // camera reports OPEN.
    }

    private fun applyModeSpecificCameraControls() {
        if (usingOemExtension) return

        val activeCamera = camera ?: return
        val control = camera2Control ?: return

        try {
            val info = Camera2CameraInfo.from(activeCamera.cameraInfo)

            when (currentMode) {
                "Macro" -> {
                    val afModes =
                        info.getCameraCharacteristic(
                            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                        )

                    if (
                        afModes?.contains(
                            CaptureRequest.CONTROL_AF_MODE_MACRO
                        ) == true
                    ) {
                        val options =
                            CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_MACRO
                                )
                                .build()

                        control.setCaptureRequestOptions(options)
                        activeModeBackend = "Macro • AF macro"
                    } else {
                        activeModeBackend = "Macro • close focus"
                    }

                    mainHandler.postDelayed({
                        camera?.cameraControl?.setZoomRatio(minZoomRatio)
                    }, 250L)
                }

                "Night" -> {
                    val builder = CaptureRequestOptions.Builder()

                    val aeModes =
                        info.getCameraCharacteristic(
                            CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES
                        )

                    val lowLightBoost =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                            aeModes?.contains(
                                CaptureRequest.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
                            ) == true

                    if (lowLightBoost) {
                        builder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
                        )
                        activeModeBackend = "Night • low-light boost"
                    } else {
                        // Let auto-exposure use longer shutter times by
                        // allowing a lower minimum frame rate.
                        val slowRange =
                            pickNightFpsRange(
                                info.getCameraCharacteristic(
                                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                                )
                            )

                        if (slowRange != null) {
                            builder.setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                slowRange
                            )
                            activeModeBackend =
                                "Night • up to 1/" + slowRange.lower + "s"
                        } else {
                            activeModeBackend = "Night • standard"
                        }
                    }

                    // Note: AE compensation is NOT forced here any more, so
                    // the EV slider works in Night mode too.
                    control.setCaptureRequestOptions(builder.build())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mode-specific camera controls unavailable", e)
        }
    }

    private fun pickNightFpsRange(
        ranges: Array<Range<Int>>?
    ): Range<Int>? {
        if (ranges.isNullOrEmpty()) return null

        // Keep the preview usable (upper >= 24 fps) but take the lowest floor.
        val candidate =
            ranges
                .filter { it.upper >= 24 }
                .minByOrNull { it.lower }
                ?: return null

        return if (candidate.lower < 15) candidate else null
    }

    private fun updateExposureLabel() {
        val ev = currentExposureIndex * exposureStep
        binding.evLabel.text =
            String.format(Locale.US, "EV %+.1f", ev)
    }

    // Real exposure compensation: auto-exposure keeps running and simply
    // targets a brighter/darker level, so it still adapts to the scene and
    // flash, tap-to-meter and lens switching keep working.
    private fun applyExposureIndex(index: Int) {
        val activeCamera = camera ?: return

        if (!activeCamera.cameraInfo.exposureState.isExposureCompensationSupported) {
            return
        }

        activeCamera.cameraControl.setExposureCompensationIndex(
            index.coerceIn(exposureLower, exposureUpper)
        )
    }

    private fun resetExposure() {
        if (exposureUpper <= exposureLower) return

        currentExposureIndex = 0.coerceIn(exposureLower, exposureUpper)
        binding.evSlider.progress = currentExposureIndex - exposureLower

        updateExposureLabel()
        applyExposureIndex(currentExposureIndex)
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
        val ring = binding.focusIndicator
        ring.animate().cancel()

        val previewLocation = IntArray(2)
        val rootLocation = IntArray(2)

        binding.previewView.getLocationInWindow(previewLocation)
        binding.root.getLocationInWindow(rootLocation)

        val parentX = previewLocation[0] - rootLocation[0] + x
        val parentY = previewLocation[1] - rootLocation[1] + y

        // A view that has never been laid out (visibility GONE) reports 0
        // width, which put the very first focus ring off-centre.
        val ringWidth =
            if (ring.width > 0) ring.width else ring.layoutParams.width
        val ringHeight =
            if (ring.height > 0) ring.height else ring.layoutParams.height

        ring.x = parentX - (ringWidth / 2f)
        ring.y = parentY - (ringHeight / 2f)

        ring.alpha = 1f
        ring.scaleX = 1.18f
        ring.scaleY = 1.18f
        ring.visibility = View.VISIBLE

        ring
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(0f)
            .setStartDelay(700L)
            .setDuration(250L)
            .withEndAction {
                ring.visibility = View.GONE
                ring.alpha = 1f
                ring.scaleX = 1f
                ring.scaleY = 1f
            }
            .start()
    }

    private fun switchCamera() {
        if (captureInProgress) return

        if (currentMode == "Macro") {
            currentMode = "Photo"
            activeModeBackend = "Photo"
            updateModeButtons()
        }

        // Hi-Q is rear-camera only.
        if (hiQEnabled) {
            hiQEnabled = false
            updateHiQButton()
        }

        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        currentZoomRatio = 1f
        pendingZoomRatio = null
        pendingExposureIndex = null
        bindCamera()
    }

    private fun toggleFlash() {
        if (hiQEnabled) {
            Toast.makeText(
                this,
                "Flash is off in Hi-Q.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

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
        updateUi()
    }

    private fun setMode(mode: String) {
        if (currentMode == mode || captureInProgress) return

        currentMode = mode

        // Hi-Q belongs to Auto; other modes turn it off.
        if (mode != "Photo" && hiQEnabled) {
            hiQEnabled = false
            updateHiQButton()
        }

        if (mode == "Night") {
            flashMode = ImageCapture.FLASH_MODE_OFF
        }

        if (mode == "Macro") {
            lensFacing = CameraSelector.LENS_FACING_BACK
            currentZoomRatio = 0.6f
        }

        pendingZoomRatio = null
        pendingExposureIndex = null

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

        applyModeStyle(
            binding.modeMacroButton,
            currentMode == "Macro"
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
                currentMode != "Night" &&
                !hiQEnabled

        binding.flashButton.isEnabled = hasFlash

        // Off and On used to show the same ⚡ glyph (only opacity differed).
        binding.flashButton.text = ""
        binding.flashButton.foreground =
            ContextCompat.getDrawable(
                this,
                when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                    ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                    else -> R.drawable.ic_flash_off
                }
            )
        binding.flashButton.foregroundGravity = Gravity.CENTER

        binding.flashButton.contentDescription =
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "Flash Auto"
                ImageCapture.FLASH_MODE_ON -> "Flash On"
                else -> "Flash Off"
            }

        binding.flashButton.alpha =
            if (!hasFlash) {
                0.30f
            } else if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                0.62f
            } else {
                1f
            }

        binding.timerButton.text =
            if (timerSeconds == 0) {
                "⏱"
            } else {
                timerSeconds.toString()
            }

        binding.timerButton.contentDescription =
            if (timerSeconds == 0) {
                "Timer Off"
            } else {
                "Timer " + timerSeconds + " seconds"
            }

        binding.timerButton.alpha =
            if (timerSeconds == 0) 0.62f else 1f

        binding.gridButton.text = "▦"
        binding.gridButton.contentDescription =
            if (gridEnabled) "Grid On" else "Grid Off"
        binding.gridButton.alpha =
            if (gridEnabled) 1f else 0.62f

        binding.switchCameraButton.text = "⇄"
        binding.switchCameraButton.contentDescription =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                "Switch to front camera"
            } else {
                "Switch to rear camera"
            }

        binding.switchCameraButton.isEnabled = currentMode != "Macro"
        binding.switchCameraButton.alpha =
            if (currentMode == "Macro") 0.30f else 1f

        // Previously the slider stayed dimmed forever after visiting Macro.
        updateZoomControlsEnabled()

        binding.zoomLabel.text =
            String.format(
                Locale.US,
                "%.1fx",
                currentZoomRatio
            )

        refreshStatusText()

        binding.gridOverlay.alpha =
            if (gridEnabled) 1f else 0f

        updateLensShortcutAvailability()
        highlightNearestLensShortcut()
    }

    private fun refreshStatusText() {
        if (countdownActive || captureInProgress) return

        val facing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                "Rear"
            } else {
                "Front"
            }

        binding.statusText.text =
            facing + " • " + activeModeBackend
    }

    private fun onShutterPressed() {
        if (!hasCameraPermission()) {
            requestCameraPermissionOrOpenSettings()
            return
        }

        if (countdownActive) {
            // Second press during the self-timer cancels it.
            cancelCountdown()
            return
        }

        if (captureInProgress) return

        if (timerSeconds == 0) {
            takePhoto()
        } else {
            countdownActive = true
            runCountdown(timerSeconds)
        }
    }

    private fun runCountdown(seconds: Int) {
        if (!countdownActive) return

        if (seconds <= 0) {
            countdownActive = false
            takePhoto()
            return
        }

        binding.statusText.text =
            "Capturing in " + seconds + " • tap to cancel"

        mainHandler.postDelayed(
            { runCountdown(seconds - 1) },
            COUNTDOWN_TOKEN,
            1000L
        )
    }

    private fun cancelCountdown() {
        if (!countdownActive) return

        countdownActive = false
        mainHandler.removeCallbacksAndMessages(COUNTDOWN_TOKEN)
        refreshStatusText()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (captureInProgress) return

        // Blocks double-taps from queueing extra shots and stops mode/lens
        // changes from aborting a capture that is still processing.
        captureInProgress = true
        binding.captureButton.isEnabled = false
        binding.captureButton.alpha = 0.6f
        binding.statusText.text = "Capturing…"

        if (hiQEnabled) {
            captureHiQ(capture, lutFor(currentLook))
            return
        }

        val lut = lutFor(currentLook)
        if (lut != null) {
            capturePhotoWithLook(capture, lut, currentLook.label)
        } else {
            capturePhotoDirect(capture)
        }
    }

    // Original path: CameraX writes the JPEG straight to MediaStore (untouched).
    private fun capturePhotoDirect(capture: ImageCapture) {
        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            ).format(System.currentTimeMillis())

        val values =
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "S23U_" + timestamp + ".jpg"
                )
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "image/jpeg"
                )
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Pictures/S23UltraCamera"
                )
                // IS_PENDING is set and cleared by CameraX itself.
            }

        val options =
            ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                override fun onCaptureStarted() {
                    if (!isDestroyed) playShutterAnimation()
                }

                override fun onCaptureProcessProgressed(progress: Int) {
                    // Reported by OEM Night/Portrait processing.
                    if (!isDestroyed) {
                        binding.statusText.text =
                            "Processing " + progress + "%"
                    }
                }

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    if (isDestroyed) return

                    finishCapture()

                    outputFileResults.savedUri?.let { uri ->
                        rememberLastPhoto(uri)
                        loadThumbnailAsync(uri)
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

                    if (isDestroyed) return

                    finishCapture()

                    toastCaptureFailed()
                }
            }
        )
    }

    // Look path: capture in memory, apply the look's LUT, then save the JPEG.
    private fun capturePhotoWithLook(
        capture: ImageCapture,
        lut: LutLook,
        lookLabel: String
    ) {
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureStarted() {
                    if (!isDestroyed) playShutterAnimation()
                }

                override fun onCaptureProcessProgressed(progress: Int) {
                    if (!isDestroyed) {
                        binding.statusText.text =
                            "Processing " + progress + "%"
                    }
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees

                    val jpeg =
                        try {
                            jpegBytesFrom(image)
                        } catch (e: Exception) {
                            Log.e(TAG, "Reading captured image failed", e)
                            null
                        } finally {
                            image.close()
                        }

                    if (jpeg == null) {
                        if (!isDestroyed) {
                            finishCapture()
                            toastCaptureFailed()
                        }
                        return
                    }

                    if (!isDestroyed) {
                        binding.statusText.text = "Applying " + lookLabel + " look…"
                    }

                    try {
                        cameraExecutor.execute {
                            val uri =
                                try {
                                    processAndSave(jpeg, rotation, lut)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Look processing failed", e)
                                    null
                                }

                            runOnUiThread {
                                if (isDestroyed) return@runOnUiThread
                                finishCapture()
                                if (uri != null) {
                                    rememberLastPhoto(uri)
                                    loadThumbnailAsync(uri)
                                } else {
                                    toastCaptureFailed()
                                }
                            }
                        }
                    } catch (e: RejectedExecutionException) {
                        if (!isDestroyed) {
                            finishCapture()
                            toastCaptureFailed()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    if (isDestroyed) return
                    finishCapture()
                    toastCaptureFailed()
                }
            }
        )
    }

    private fun toastCaptureFailed() {
        Toast.makeText(this, "Capture failed.", Toast.LENGTH_LONG).show()
    }

    private fun jpegBytesFrom(image: ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    // Decode -> rotate upright -> apply LUT -> write JPEG (quality 100).
    // Runs on cameraExecutor, never the main thread.
    private fun processAndSave(
        jpeg: ByteArray,
        rotationDegrees: Int,
        lut: LutLook
    ): Uri? {
        val decoded =
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null

        val upright =
            if (rotationDegrees % 360 != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, m, true
                )
                if (rotated != decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }

        val looked = lut.applyToBitmap(upright)
        if (looked != upright) upright.recycle()

        return saveJpeg(looked)
    }

    // Writes the bitmap as a quality-100 JPEG to MediaStore and recycles it.
    // Runs on cameraExecutor, never the main thread.
    private fun saveJpeg(bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(System.currentTimeMillis())
            put(MediaStore.MediaColumns.DISPLAY_NAME, "S23U_" + timestamp + ".jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S23UltraCamera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run {
            bitmap.recycle()
            return null
        }

        try {
            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                contentResolver.delete(uri, null, null)
                bitmap.recycle()
                return null
            }
            stream.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Saving image failed", e)
            contentResolver.delete(uri, null, null)
            bitmap.recycle()
            return null
        }

        bitmap.recycle()

        val done = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        contentResolver.update(uri, done, null, null)

        return uri
    }

    // ---------------------------------------------------------------- Hi-Q

    private fun rearCameraSupportsRaw(): Boolean {
        val provider = cameraProvider ?: return false
        return try {
            val info =
                CameraSelector.DEFAULT_BACK_CAMERA
                    .filter(provider.availableCameraInfos)
                    .firstOrNull()
                    ?: return false
            ImageCapture.getImageCaptureCapabilities(info)
                .supportedOutputFormats
                .contains(ImageCapture.OUTPUT_FORMAT_RAW)
        } catch (e: Exception) {
            Log.w(TAG, "RAW support check failed", e)
            false
        }
    }

    private fun toggleHiQ() {
        if (captureInProgress || countdownActive) return

        if (hiQEnabled) {
            hiQEnabled = false
            pendingExposureIndex = currentExposureIndex
            updateHiQButton()
            updateUi()
            bindCamera()
            Toast.makeText(this, "Hi-Q off", Toast.LENGTH_SHORT).show()
            return
        }

        if (!rearCameraSupportsRaw()) {
            Toast.makeText(
                this,
                "This camera doesn't provide RAW, so Hi-Q isn't available.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        hiQEnabled = true
        currentMode = "Photo"
        lensFacing = CameraSelector.LENS_FACING_BACK
        flashMode = ImageCapture.FLASH_MODE_OFF
        currentZoomRatio = 1f
        pendingZoomRatio = null
        pendingExposureIndex = currentExposureIndex

        updateModeButtons()
        updateHiQButton()
        updateUi()
        bindCamera()

        Toast.makeText(
            this,
            "Hi-Q on: merges " + HIQ_FRAMES + " RAW frames. Hold still. Zoom to " +
                HIQ_MAX_ZOOM.toInt() + "×.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateHiQButton() {
        binding.hiQButton.text = "HQ"
        binding.hiQButton.alpha = if (hiQEnabled) 1f else 0.62f
        binding.hiQButton.setBackgroundResource(
            if (hiQEnabled) R.drawable.camera_circle_button_selected
            else R.drawable.camera_circle_button
        )
        binding.hiQButton.contentDescription =
            if (hiQEnabled) "Hi-Q on" else "Hi-Q off"
    }

    private fun captureHiQ(capture: ImageCapture, lut: LutLook?) {
        val statics = camera?.let { RawStatics.read(it.cameraInfo) }
        if (statics == null) {
            finishCapture()
            Toast.makeText(
                this,
                "Hi-Q couldn't read this camera's sensor details.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val processor = HiQProcessor(
            statics,
            captureResults,
            currentExposureIndex * exposureStep,
            currentZoomRatio.coerceIn(1f, HIQ_MAX_ZOOM)
        )
        captureHiQFrame(capture, processor, 0, lut)
    }

    // Takes one frame of the burst; each arriving frame is merged on the
    // camera executor while the next one is being captured.
    private fun captureHiQFrame(
        capture: ImageCapture,
        processor: HiQProcessor,
        index: Int,
        lut: LutLook?
    ) {
        if (isDestroyed) {
            processor.close()
            return
        }

        binding.statusText.text =
            "Hi-Q " + (index + 1) + "/" + HIQ_FRAMES + " • hold still"

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureStarted() {
                    if (index == 0 && !isDestroyed) playShutterAnimation()
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame =
                        try {
                            copyRawFrame(image)
                        } catch (e: Exception) {
                            Log.e(TAG, "Copying RAW frame failed", e)
                            null
                        } finally {
                            image.close()
                        }

                    if (frame == null) {
                        endHiQBurst(processor, lut, index)
                        return
                    }

                    try {
                        cameraExecutor.execute { processor.add(frame) }
                    } catch (e: RejectedExecutionException) {
                        processor.close()
                        return
                    }

                    if (index + 1 < HIQ_FRAMES) {
                        captureHiQFrame(capture, processor, index + 1, lut)
                    } else {
                        finishHiQ(processor, lut)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Hi-Q frame " + index + " failed", exception)
                    endHiQBurst(processor, lut, index)
                }
            }
        )
    }

    // A frame failed: develop the frames already captured, if any.
    private fun endHiQBurst(processor: HiQProcessor, lut: LutLook?, framesCaptured: Int) {
        if (framesCaptured > 0) {
            finishHiQ(processor, lut)
            return
        }
        processor.close()
        if (!isDestroyed) {
            finishCapture()
            toastCaptureFailed()
        }
    }

    private fun finishHiQ(processor: HiQProcessor, lut: LutLook?) {
        if (!isDestroyed) {
            binding.statusText.text = "Hi-Q • merging and developing…"
        }

        try {
            cameraExecutor.execute {
                val uri =
                    try {
                        val result = processor.finish(lut)
                        Log.i(
                            TAG,
                            "Hi-Q merged " + processor.frames + " frames, metadata from " +
                                processor.metadataSource
                        )
                        saveDeveloped(result)
                    } catch (t: Throwable) {
                        // Includes OutOfMemoryError: fail the shot, not the app.
                        Log.e(TAG, "Hi-Q processing failed", t)
                        null
                    } finally {
                        processor.close()
                    }

                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    finishCapture()
                    if (uri != null) {
                        rememberLastPhoto(uri)
                        loadThumbnailAsync(uri)
                    } else {
                        toastCaptureFailed()
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            processor.close()
            if (!isDestroyed) {
                finishCapture()
                toastCaptureFailed()
            }
        }
    }

    private fun saveDeveloped(result: HiQProcessor.Result): Uri? {
        var bitmap = Bitmap.createBitmap(
            result.pixels, result.width, result.height, Bitmap.Config.ARGB_8888
        )
        if (result.rotation % 360 != 0) {
            val m = Matrix().apply { postRotate(result.rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return saveJpeg(bitmap)
    }

    private fun applyPreviewLook() {
        // RuntimeShader (AGSL) is Android 13+. Below that, preview is
        // unfiltered; saved photos still get the look.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        binding.previewView.setRenderEffect(
            lutFor(currentLook)?.renderEffect
        )
    }

    // Cycles FZ55 -> G7X -> Off -> FZ55.
    private fun toggleLook() {
        val all = Look.values()
        currentLook = all[(currentLook.ordinal + 1) % all.size]
        updateLookButton()
        applyPreviewLook()
        Toast.makeText(
            this,
            if (currentLook == Look.OFF) "Look off" else currentLook.label + " look",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateLookButton() {
        binding.lookButton.text = currentLook.label
        binding.lookButton.alpha = if (currentLook == Look.OFF) 0.5f else 1f
        binding.lookButton.contentDescription =
            if (currentLook == Look.OFF) {
                "Camera look off"
            } else {
                currentLook.label + " look on"
            }
    }

    private fun finishCapture() {
        captureInProgress = false
        binding.captureButton.isEnabled = true
        binding.captureButton.alpha = 1f
        refreshStatusText()
    }

    private fun createShutterOverlay(): View {
        val overlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val params = ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = binding.previewView.id
            bottomToBottom = binding.previewView.id
            startToStart = binding.previewView.id
            endToEnd = binding.previewView.id
        }

        binding.root.addView(
            overlay,
            binding.root.indexOfChild(binding.previewView) + 1,
            params
        )

        return overlay
    }

    private fun playShutterAnimation() {
        val overlay = shutterOverlay

        overlay.animate().cancel()
        overlay.alpha = 0.85f
        overlay.visibility = View.VISIBLE

        overlay
            .animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun rememberLastPhoto(uri: Uri) {
        lastSavedUri = uri
        getPreferences(MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_PHOTO, uri.toString())
            .apply()
    }

    private fun restoreLastPhoto() {
        val saved =
            getPreferences(MODE_PRIVATE)
                .getString(PREF_LAST_PHOTO, null)
                ?: return

        val uri = Uri.parse(saved)
        lastSavedUri = uri
        loadThumbnailAsync(uri, forgetIfMissing = true)
    }

    private fun loadThumbnailAsync(
        uri: Uri,
        forgetIfMissing: Boolean = false
    ) {
        val sizePx =
            (binding.thumbnailImage.layoutParams.width * 2)
                .coerceIn(96, 512)

        try {
            cameraExecutor.execute {
                val bitmap =
                    try {
                        // Unlike BitmapFactory, loadThumbnail() applies the
                        // EXIF rotation, so portrait shots aren't sideways.
                        contentResolver.loadThumbnail(
                            uri,
                            Size(sizePx, sizePx),
                            null
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Thumbnail load failed for " + uri, e)
                        null
                    }

                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread

                    if (bitmap != null) {
                        binding.thumbnailImage.setImageBitmap(bitmap)
                    } else if (forgetIfMissing && lastSavedUri == uri) {
                        // Deleted, or no longer ours after a reinstall.
                        lastSavedUri = null
                        getPreferences(MODE_PRIVATE)
                            .edit()
                            .remove(PREF_LAST_PHOTO)
                            .apply()
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Thumbnail skipped, activity is closing", e)
        }
    }

    private fun openLastPhoto() {
        val uri = lastSavedUri ?: return

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    uri,
                    contentResolver.getType(uri) ?: "image/jpeg"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No gallery app found.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermissionOrOpenSettings() {
        val permanentlyDenied =
            cameraPermissionDenied &&
                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

        if (!permanentlyDenied) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // Android stops showing the dialog after two refusals.
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
            Toast.makeText(
                this,
                "Allow Camera under Permissions.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "App settings screen unavailable", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        (binding.root.display ?: windowManager.defaultDisplay)
            ?.rotation
            ?: Surface.ROTATION_0

    // Volume buttons (and Bluetooth selfie remotes, which send volume-up)
    // work as a shutter, like the Samsung camera.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode)) {
            if (event.repeatCount == 0) {
                onShutterPressed()
            }
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isShutterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA

    override fun onDestroy() {
        super.onDestroy()

        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "S23UltraCamera"
        private const val ZOOM_SLIDER_STEPS = 1000
        private const val PREF_LAST_PHOTO = "last_photo_uri"

        private const val STATE_LENS = "lens_facing"
        private const val STATE_MODE = "mode"
        private const val STATE_FLASH = "flash_mode"
        private const val STATE_TIMER = "timer_seconds"
        private const val STATE_GRID = "grid_enabled"
        private const val STATE_ZOOM = "zoom_ratio"
        private const val STATE_EV = "exposure_index"
        private const val STATE_LOOK = "fz55_look"
        private const val STATE_HIQ = "hiq"

        // Frames per Hi-Q burst: 4 roughly halves the noise.
        private const val HIQ_FRAMES = 4

        // Max Hi-Q digital zoom (RAW crop + multi-frame upscale). Beyond this a
        // main-sensor crop gets too soft even with merging.
        private const val HIQ_MAX_ZOOM = 4f

        private val COUNTDOWN_TOKEN = Any()
    }
}

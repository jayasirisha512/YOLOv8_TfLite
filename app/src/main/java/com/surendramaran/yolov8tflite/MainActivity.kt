package com.surendramaran.yolov8tflite
// This file defines the main activity of the app, which handles camera setup,
// receiving frames, running object detection using YOLOv8, and displaying results.

import android.Manifest // Permission for using camera
import android.content.pm.PackageManager // To check if permission is granted
import android.graphics.Bitmap // Used to hold camera frame data
import android.graphics.Matrix // Used to rotate/flip bitmap
import android.os.Bundle // Bundle for passing saved instance state
import android.util.Log // Used to log debug/error messages

// AndroidX libraries
import androidx.activity.result.contract.ActivityResultContracts// For requesting permissions
import androidx.appcompat.app.AppCompatActivity// Base class for activities
import androidx.camera.core.AspectRatio// CameraX core components
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider// Connects camera lifecycle to app lifecycle
import androidx.core.app.ActivityCompat// Used to request permissions
import androidx.core.content.ContextCompat// Used to get colors, check permissions


// Importing model constants and view binding
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH // Path to labels.txt
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH // Path to .tflite model
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding // Automatically generated binding class

// For running tasks on a background thread
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Main screen of the app. Implements DetectorListener to receive detection results.
class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    // Binding object to access layout views without findViewById()
    private lateinit var binding: ActivityMainBinding
    
    // Flag: should we use the front camera? false = use back camera
    private val isFrontCamera = false
    
    // CameraX components to display preview and process frames
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
     // Custom YOLOv8 object detector
    private var detector: Detector? = null
    // Executor for background thread (camera + inference)
    private lateinit var cameraExecutor: ExecutorService
    // Entry point of the activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
         // Inflate the layout file (activity_main.xml)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Initialize background thread to run camera and model
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Load the YOLO model on background thread to avoid UI lag
        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        }
        // If camera permission is granted, start camera. Else, ask for permission.
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
       // Attach event listeners (e.g., GPU toggle)
        bindListeners()
    }
    // Handles the GPU switch toggle button
    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }
        }
    }
    // Starts the CameraX camera provider and binds use cases
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }
    // Binds camera preview and image analysis to lifecycle
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation
        // Select back camera
        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        // Set up camera preview use case
        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()
        // Set up image analysis use case (for detection)
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        // What to do with each frame
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            // Convert imageProxy to Bitmap
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            // Copy pixels from imageProxy buffer to bitmap
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            // Close the image so next frame can be analyzed
            imageProxy.close()
            // Apply rotation/mirroring if needed
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }
            
            // Rotate bitmap to match screen orientation
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )
             // Run detection on this processed frame
            detector?.detect(rotatedBitmap)
        }
        // Clear old use cases and bind new ones
        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            // Set preview surface to our viewFinder
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }
    // Check if all required permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    // Modern permission request callback
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }
    // Cleanup when app is destroyed
    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }
    // When app resumes (e.g. returning from background)
    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }
    // Constants
    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }
    // Callback when no detection happens
    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }
    // Callback when detection happens
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }
}

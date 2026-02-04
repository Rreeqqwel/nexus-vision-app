package com.example.visionassist.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages CameraX initialization, preview, and image capture
 */
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var lastAnalyzedFrame: Bitmap? = null
    private val frameLock = Any()

    companion object {
        private const val TAG = "CameraManager"
    }

    /**
     * Starts the camera preview
     */
    fun startCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onCameraReady: (() -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Preview use case
                preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                // Image capture use case
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // Image analysis use case (for real-time frame access)
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Unbind all use cases before rebinding
                cameraProvider?.unbindAll()
                
                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
                
                Log.d(TAG, "Camera started successfully")
                onCameraReady?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Process image proxy for analysis
     */
    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            synchronized(frameLock) {
                lastAnalyzedFrame?.recycle()
                lastAnalyzedFrame = bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image proxy", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Converts ImageProxy to Bitmap
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val planes = imageProxy.planes
        val yBuffer: ByteBuffer = planes[0].buffer
        val uBuffer: ByteBuffer = planes[1].buffer
        val vBuffer: ByteBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, yuvImage.width, yuvImage.height),
            90,
            out
        )

        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        
        // Rotate bitmap based on image rotation
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                bitmap.recycle()
            }
        } else {
            bitmap
        }
    }

    /**
     * Captures the current camera frame
     */
    suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(frameLock) {
            lastAnalyzedFrame?.copy(lastAnalyzedFrame!!.config, true)
        }
    }

    /**
     * Takes a high-quality photo
     */
    suspend fun takePhoto(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val imageCapture = imageCapture ?: run {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        continuation.resume(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error capturing photo", e)
                        continuation.resume(null)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    continuation.resume(null)
                }
            }
        )
    }

    /**
     * Shuts down camera and releases resources
     */
    fun shutdown() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            synchronized(frameLock) {
                lastAnalyzedFrame?.recycle()
                lastAnalyzedFrame = null
            }
            Log.d(TAG, "Camera shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during camera shutdown", e)
        }
    }
}

package com.softbankrobotics.facemaskdetection.capturer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import java.util.concurrent.Executors

/*
 * CameraCapturer that uses Pepper's Tablet camera
 */
class BottomCameraCapturer(
    private val androidContext: Context,
    private val lifecycleOwner: LifecycleOwner):
    CameraCapturer {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var bitmapBuffer: Bitmap

    /** Declare and bind preview and analysis use cases */
    @SuppressLint("UnsafeExperimentalUsageError")
    override fun start(onPictureCb: (Bitmap, Long) -> Unit): Future<Unit> {
        val promise = Promise<Unit>()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(androidContext)
        cameraProviderFuture.addListener(Runnable {

            // Camera provider is now guaranteed to be available
            val cameraProvider = cameraProviderFuture.get()

            // Set up the image analysis use case which will process frames in real time
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val converter = YuvToRgbConverter(androidContext)

            imageAnalysis.setAnalyzer(executor, ImageAnalysis.Analyzer { image ->
                if (!::bitmapBuffer.isInitialized) {
                    // The image rotation and RGB image buffer are initialized only once
                    // the analyzer has started running
                    //imageRotationDegrees = image.imageInfo.rotationDegrees
                    bitmapBuffer = Bitmap.createBitmap(
                        image.width, image.height, Bitmap.Config.ARGB_8888)
                }
                val imageTime = System.currentTimeMillis()

                // Convert the image to RGB and place it in our shared buffer
                image.use { converter.yuvToRgb(image.image!!, bitmapBuffer) }
                onPictureCb(bitmapBuffer, imageTime)
            })

            promise.setOnCancel {
                imageAnalysis.clearAnalyzer()
                executor.shutdown()
                cameraProvider.unbindAll()
            }
            val cameraSelector = CameraSelector.Builder().build()
            // Apply declared configs to CameraX using the same lifecycle owner
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        }, ContextCompat.getMainExecutor(androidContext))

        return promise.future
    }
}

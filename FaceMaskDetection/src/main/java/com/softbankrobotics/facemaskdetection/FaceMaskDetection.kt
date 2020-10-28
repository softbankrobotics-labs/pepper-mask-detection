package com.softbankrobotics.facemaskdetection

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.aldebaran.qi.Future
import com.softbankrobotics.facemaskdetection.capturer.CameraCapturer
import com.softbankrobotics.facemaskdetection.detector.FaceMaskDetector
import com.softbankrobotics.facemaskdetection.utils.SingleThreadedGlobalScope
import com.softbankrobotics.facemaskdetection.utils.TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.actor
import org.opencv.core.CvException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class FaceMaskDetection(private val detector: FaceMaskDetector, private val cameraCapturer: CameraCapturer) {
    private var busy = false
    private var active = false

    private val detectionScope = CoroutineScope(
        ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(1), ThreadPoolExecutor.DiscardOldestPolicy()).asCoroutineDispatcher())

    sealed class Message {
        class FaceMaskDetect(
            val picture: Bitmap,
            val detectedFaces: CompletableDeferred<List<FaceMaskDetector.DetectedFace>>,
            val sentTime: Long
        ): Message()
    }

    private fun CoroutineScope.faceMaskDetectActor() = actor<Message>{
        for (msg in channel) {
            when (msg) {
                is Message.FaceMaskDetect -> {
                    try {
                        msg.detectedFaces.complete(detector.recognize(msg.picture))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error processing picture of ${msg.picture.height} x ${msg.picture.width} sent at ${msg.sentTime}")
                        msg.detectedFaces.completeExceptionally(e)
                    }
                }
            }
        }
    }

    private val actor = detectionScope.faceMaskDetectActor()

    fun start(onFaceDetected: (List<FaceMaskDetector.DetectedFace>) -> Unit): Future<Unit> {
        active = true
        val fut = cameraCapturer.start { picture, sentTime ->
            //Log.i(TAG, "Top camera width: ${picture.width} height: ${picture.height}")
            if (active && !busy) {
                SingleThreadedGlobalScope.async {
                    val detectedFacesDeferred = CompletableDeferred<List<FaceMaskDetector.DetectedFace>>()
                    busy = true
                    actor.send(Message.FaceMaskDetect(picture, detectedFacesDeferred, sentTime))
                    try {
                        val faces = detectedFacesDeferred.await()
                        onFaceDetected(faces)
                    }  catch (e:CvException) {
                        Log.w(TAG, "OpenCV Exception during face processing (sometimes happens), ignoring: $e")
                    }  catch (e:Throwable) {
                        Log.e(TAG, "Unexpected exception from face mask processing: $e")
                    } finally {
                        busy = false
                    }
                }
            }
        }
        fut.thenConsume {
            active = false
            Log.i(TAG, "Face mask detection future finished")
        }
        return fut
    }


    private fun saveImage(finalBitmap: Bitmap) {
        val root: String = Environment.getExternalStorageDirectory().toString()
        val myDir = File("$root/saved_images")
        myDir.mkdirs()
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val fname = "Shutta_$timeStamp.jpg"
        val file = File(myDir, fname)
        if (file.exists()) file.delete()
        try {
            val out = FileOutputStream(file)
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
            out.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}
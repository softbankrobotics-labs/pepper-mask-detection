package com.softbankrobotics.facemaskdetection.capturer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import com.softbankrobotics.facemaskdetection.utils.SingleThreadedGlobalScope
import com.softbankrobotics.facemaskdetection.utils.asyncFuture
import com.softbankrobotics.facemaskdetection.utils.await
import kotlinx.coroutines.isActive


class TopCameraCapturer(private val qiContext: QiContext):
    CameraCapturer {

    override fun start(onPictureCb: (Bitmap, Long) -> Unit): Future<Unit> = SingleThreadedGlobalScope.asyncFuture {
        val takePicture = TakePictureBuilder.with(qiContext).buildAsync().await()
        while (this.isActive) {
            val timestampedImageHandle = takePicture.async().run().await()
            val encodedImageHandle = timestampedImageHandle.image
            val encodedImage = encodedImageHandle.value

            // get the byte buffer and cast it to byte array
            val buffer = encodedImage.data
            buffer.rewind()
            val pictureBufferSize = buffer.remaining()
            val pictureArray = ByteArray(pictureBufferSize)
            buffer.get(pictureArray)

            val pictureBitmap = BitmapFactory.decodeByteArray(pictureArray, 0, pictureBufferSize)
            onPictureCb(pictureBitmap, timestampedImageHandle.time)
        }
    }

}


package com.softbankrobotics.facemaskdetection.capturer

import android.graphics.Bitmap
import com.aldebaran.qi.Future

// Abstract interface for camera capturer
interface CameraCapturer {
    fun start(onPictureCb: (Bitmap, Long) -> Unit): Future<Unit>
}
package com.softbankrobotics.facemaskdetection.utils

import android.content.Context
import android.util.Log
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat

object OpenCVUtils {

    fun createMat(row:Int, col:Int, vararg data: Double): Mat {
        val mat = Mat(row, col, CvType.CV_64F)
        mat.put(0, 0, *data)
        return mat
    }

    fun loadOpenCV(appContext: Context) {
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, appContext, object : BaseLoaderCallback(appContext) {
                override fun onManagerConnected(status: Int) {
                    when (status) {
                        LoaderCallbackInterface.SUCCESS -> {
                            Log.d(TAG, "OpenCV loaded successfully")
                        }
                        else -> {
                            super.onManagerConnected(status)
                        }
                    }
                }
            })
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!")
            Log.d(TAG, "OpenCV loaded successfully")
        }
    }
}
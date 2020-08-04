package com.softbankrobotics.facemaskdetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.softbankrobotics.facemaskdetection.utils.assetToAppStoragePath
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.CvType.CV_32F
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min


class AizooFaceMaskDetector(context: Context):
    FaceMaskDetector {

    private val caffeeModelPath = assetToAppStoragePath("aizoo/face_mask_detection.caffemodel", context)
    private val protoTxtPath = assetToAppStoragePath("aizoo/face_mask_detection.prototxt", context)
    private val anchorCenterXPath = assetToAppStoragePath("aizoo/anchor_centers_x.csv", context)
    private val anchorCenterYPath = assetToAppStoragePath("aizoo/anchor_centers_y.csv", context)
    private val anchorWPath = assetToAppStoragePath("aizoo/anchors_w.csv", context)
    private val anchorHPath = assetToAppStoragePath("aizoo/anchors_h.csv", context)

    private val OUTPUT_SIZE = 5972
    private val anchorCenterX: Mat = Mat(OUTPUT_SIZE, 1, CV_32F)
    private val anchorCenterY: Mat = Mat(OUTPUT_SIZE, 1, CV_32F)
    private val anchorsW: Mat = Mat(OUTPUT_SIZE, 1, CV_32F)
    private val anchorsH: Mat = Mat(OUTPUT_SIZE, 1, CV_32F)

    private val variances: Mat by lazy {
        val m = Mat(OUTPUT_SIZE, 4, CV_32F)
        val src = Mat(1, 4, CV_32F)
        src.put(0, 0, 0.1, 0.1, 0.2, 0.2)
        Core.repeat(src, OUTPUT_SIZE, 1, m)
        m
    }
    private var model: Net? = null

    init {
        loadAnchors()
        loadModel()
    }

    override fun recognize(picture: Bitmap): List<FaceMaskDetector.DetectedFace> {
        val frame = Mat(picture.width, picture.height, CvType.CV_8UC3)
        Utils.bitmapToMat(picture, frame)
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2RGB)
        val width = frame.width()
        val height = frame.height()
        val frameResized = Mat(260, 260, CV_32F)
        Imgproc.resize(frame, frameResized, Size(260.0, 260.0))
        val blob: Mat = Dnn.blobFromImage(frameResized, 1.0 / 255.0)
        model!!.setInput(blob)
        val output: List<Mat> = java.util.ArrayList()
        model!!.forward(output, arrayListOf("loc_branch_concat", "cls_branch_concat"))

        val boundingBoxes = output[0].reshape(1, output[0].total().toInt() / 4)
        val classes = output[1].reshape(1, output[1].total().toInt() / 2)

        decodeBoundingBox(boundingBoxes)

        val boundingBoxesMaxScore = Mat(OUTPUT_SIZE, 1, CV_32F)
        val boundingBoxesMaxScoreClasses = Mat(OUTPUT_SIZE, 1, CV_32F)

        for (i in 0 until boundingBoxes.rows()) {
            if (classes[i, 0][0] > classes[i, 1][0]) {
                boundingBoxesMaxScore.put(i, 0, classes[i, 0][0])
                boundingBoxesMaxScoreClasses.put(i, 0, 0.0)
            } else {
                boundingBoxesMaxScore.put(i, 0, classes[i, 1][0])
                boundingBoxesMaxScoreClasses.put(i, 0, 1.0)
            }
        }

        val keepIndexes = singleClassNonMaskSuppression(boundingBoxes, boundingBoxesMaxScore)
        val result = keepIndexes.map {
            val bbox = boundingBoxes.rowRange(it, it+1)
            val xmin = max(0.0, bbox[0, 0][0] * width)
            val ymin = max(0.0, bbox[0, 1][0] * height)
            val xmax = min(width.toDouble() - 1.0, bbox[0, 2][0] * width)
            val ymax = min(height.toDouble() - 1.0, bbox[0, 3][0] * height)
            val bb =
                FaceMaskDetector.BoundingBox(
                    xmin,
                    xmax,
                    ymin,
                    ymax
                )
            if ((xmin >= xmax) || ymin >= ymax) {
                null
            } else {
                val imageBB = bb.getSquare(width.toDouble(), height.toDouble(), 0.5)  // Ensure it's square for prettier display
                val face = frame.submat(Rect(Point(imageBB.left, imageBB.top),
                    Point(imageBB.right, imageBB.bottom)))
                val facePic = Bitmap.createBitmap(face.cols(), face.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(face, facePic)

                // Flip the image horizontally
                val faceWidth = (imageBB.right - imageBB.left).toInt()
                val faceHeight = (imageBB.bottom - imageBB.top).toInt()
                val matrix = Matrix().apply { postScale(-1f, 1f, faceWidth/2f, faceHeight/2f) }
                val flippedFacePic = Bitmap.createBitmap(facePic, 0, 0, faceWidth, faceHeight, matrix, true)

                FaceMaskDetector.DetectedFace(
                    boundingBoxesMaxScore[it, 0][0],
                    bb,
                    boundingBoxesMaxScoreClasses[it, 0][0].toInt() == 0,
                    flippedFacePic
                )
            }
        }.filterNotNull()

        boundingBoxesMaxScore.release()
        boundingBoxesMaxScoreClasses.release()
        // there is a bug where if there are more than one face, some faces appear twice
        // Until that's fixed, remove them as a post-processing step.

        val nonOverlappingResults = result.filter { !it.shadowedBy(result) }
        return nonOverlappingResults
    }

    private fun singleClassNonMaskSuppression(boundingBoxes: Mat,
                                              confidences: Mat,
                                              confThreshold:Double = 0.5,
                                              iouThreshold: Double = 0.4): List<Int> {
        var size = 0

        val confidencesKeepIndex = Mat(confidences.rows(), 1, CvType.CV_16U)
        for (i in 0 until confidences.rows()) {
            if (confidences[i, 0][0] > confThreshold) {
                confidences.row(i).copyTo(confidences.row(size))
                boundingBoxes.row(i).copyTo(boundingBoxes.row(size))
                confidencesKeepIndex.put(size, 0, i.toDouble())
                size++
            }
        }
        if (size == 0) return listOf()

        val confidencesR = confidences.submat(0, size, 0, 1)
        val boundingBoxesR = boundingBoxes.submat(0, size, 0, 4)
        val xMin = boundingBoxesR.colRange(0, 1)
        val yMin = boundingBoxesR.colRange(1, 2)
        val xMax = boundingBoxesR.colRange(2, 3)
        val yMax = boundingBoxesR.colRange(3, 4)

        val area = Mat(xMin.rows(), 1, CV_32F)
        val areaTmp = Mat(xMin.rows(), 1, CV_32F)
        Core.subtract(xMax, xMin, area)
        Core.add(area, Scalar(1e-3), area)
        Core.subtract(yMax, yMin, areaTmp)
        Core.add(areaTmp, Scalar(1e-3), areaTmp)
        Core.multiply(area, areaTmp, area)

        val idxs = IntRange(0, confidencesR.rows() - 1).sortedWith(Comparator<Int?> { i1, i2 ->
            confidencesR[i1!!, 0][0].compareTo(confidencesR[i2!!, 0][0])
        }).toMutableList()

        val tmp = Mat(idxs.size, 1, CV_32F)
        for (i in 0 until idxs.size) { tmp.put(i, 0, xMin[idxs[i], 0][0]) }
        tmp.copyTo(xMin)
        for (i in 0 until idxs.size) { tmp.put(i, 0, xMax[idxs[i], 0][0]) }
        tmp.copyTo(xMax)
        for (i in 0 until idxs.size) { tmp.put(i, 0, yMin[idxs[i], 0][0]) }
        tmp.copyTo(yMin)
        for (i in 0 until idxs.size) { tmp.put(i, 0, yMax[idxs[i], 0][0]) }
        tmp.copyTo(yMax)
        for (i in 0 until idxs.size) { tmp.put(i, 0, area[idxs[i], 0][0]) }
        tmp.copyTo(area)

        val pick = mutableListOf<Int>()
        while (idxs.size > 0) {
            val last = idxs.size - 1
            val i = idxs[last]
            pick.add(i)

            if (last == 0) break

            val overlapXMin = xMin.rowRange(0, last).clone()
            Core.max(overlapXMin, Scalar(xMin[last, 0][0]), overlapXMin)

            val overlapYMin = yMin.rowRange(0, last).clone()
            Core.max(overlapYMin, Scalar(yMin[last, 0][0]), overlapYMin)

            val overlapXMax = xMax.rowRange(0, last).clone()
            Core.min(overlapXMax, Scalar(xMax[last, 0][0]), overlapXMax)

            val overlapYMax = yMax.rowRange(0, last).clone()
            Core.min(overlapYMax, Scalar(yMax[last, 0][0]), overlapYMax)

            val overlapW = overlapXMax
            Core.subtract(overlapXMax, overlapXMin, overlapW)
            Core.max(overlapW, Scalar(0.0), overlapW)

            val overlapH = overlapYMax
            Core.subtract(overlapYMax, overlapYMin, overlapH)
            Core.max(overlapH, Scalar(0.0), overlapH)

            val overlapArea = overlapW
            Core.multiply(overlapH, overlapW, overlapArea)

            val overlapRatio = overlapH
            Core.add(area.rowRange(0, last), Scalar(area[last, 0][0]), overlapRatio)
            Core.subtract(overlapRatio, overlapArea, overlapRatio)
            Core.divide(overlapArea, overlapRatio, overlapRatio)

            idxs.removeAt(last)
            for (j in (last-1) downTo 0) {
                if (overlapRatio[j, 0][0] > iouThreshold) {
                    idxs.removeAt(j)
                }
            }
        }

        val result = mutableListOf<Int>()
        for (i in pick)
            result.add(confidencesKeepIndex[i, 0][0].toInt())

        confidencesR.release()
        boundingBoxesR.release()
        confidencesKeepIndex.release()

        return result
    }

    private fun decodeBoundingBox(boundingBoxes: Mat) {
        // raw_outputs_rescale
        Core.multiply(boundingBoxes, variances, boundingBoxes)

        val predictCenterX = Mat(boundingBoxes.rows(), 1, CV_32F)
        Core.multiply(boundingBoxes.colRange(0, 1), anchorsW, predictCenterX)
        Core.add(predictCenterX, anchorCenterX, predictCenterX)

        val predictCenterY = Mat(boundingBoxes.rows(), 1, CV_32F)
        Core.multiply(boundingBoxes.colRange(1, 2), anchorsH, predictCenterY)
        Core.add(predictCenterY, anchorCenterY, predictCenterY)

        val predictW = Mat(boundingBoxes.rows(), 1, CV_32F)
        Core.exp(boundingBoxes.colRange(2, 3), predictW)
        Core.multiply(predictW, anchorsW, predictW)

        val predictH = Mat(boundingBoxes.rows(), 1, CV_32F)
        Core.exp(boundingBoxes.colRange(3, 4), predictH)
        Core.multiply(predictH, anchorsH, predictH)

        Core.divide(predictW, Scalar(2.0), predictW)
        Core.divide(predictH, Scalar(2.0), predictH)

        Core.subtract(predictCenterX, predictW, boundingBoxes.colRange(0, 1))
        Core.add(predictCenterX, predictW, boundingBoxes.colRange(2, 3))

        Core.subtract(predictCenterY, predictH, boundingBoxes.colRange(1, 2))
        Core.add(predictCenterY, predictH, boundingBoxes.colRange(3, 4))

        predictCenterX.release()
        predictCenterY.release()
        predictW.release()
        predictH.release()
    }

    private fun loadAnchors() {
        File(anchorCenterXPath).readLines()
            .forEachIndexed { index, line -> anchorCenterX.put(index, 0, line.toDouble()) }
        File(anchorCenterYPath).readLines()
            .forEachIndexed { index, line -> anchorCenterY.put(index, 0, line.toDouble()) }
        File(anchorHPath).readLines()
            .forEachIndexed { index, line -> anchorsH.put(index, 0, line.toDouble()) }
        File(anchorWPath).readLines()
            .forEachIndexed { index, line -> anchorsW.put(index, 0, line.toDouble()) }
    }

    private fun loadModel() {
        model = Dnn.readNetFromCaffe(protoTxtPath, caffeeModelPath)
    }
}

package com.softbankrobotics.facemaskdetection.detector

import android.graphics.Bitmap
import kotlin.math.abs

/*
 *  Abstract interface for FaceMask detector
 */
interface FaceMaskDetector {
    data class BoundingBox(val left: Double, val right: Double, val top: Double, val bottom: Double) {

        /*
         * Checks whether this bounding box overlaps with another.
         */
        fun overlapsWith(otherBox : BoundingBox) : Boolean{
            return (left < otherBox.right) && (otherBox.left < right) &&
                   (top < otherBox.bottom) && (otherBox.top < bottom)
        }
        /*
         * returns the best square containing this bounding box (plus optional extra padding),
         * as centered as possible while still fitting inside the provided frame (specified by
         * frameWidth and frameHeight).
         */
        fun getSquare(frameWidth : Double, frameHeight: Double, extraPaddingFraction : Double = 0.0) : BoundingBox {
            var width = right - left
            var height = bottom - top
            var newLeft = left
            var newRight = right
            var newTop = top
            var newBottom = bottom
            // Make everything a bit bigger if necessary
            if (extraPaddingFraction > 0) {
                val horizontalPadding = width * extraPaddingFraction * 0.5
                newLeft -= horizontalPadding
                newRight += horizontalPadding
                val verticalPadding = height * extraPaddingFraction * 0.5
                newTop -= verticalPadding
                newBottom += verticalPadding
                width = newRight - newLeft
                height = newBottom - newTop
            }
            //Make sure the shape is a square if necessary
            if (height > width) {
                val margin = (height - width) / 2
                newLeft -= margin
                newRight += margin
            } else {
                val margin = (width - height) / 2
                newTop -= margin
                newBottom += margin
            }
            // Now we need to make sure the returned box fits in the frame
            // Adjust horizontally
            if (newLeft < 0) {
                // Move everything rightwards
                newRight = newRight + (- newLeft)
                newLeft = 0.0
            } else if (newRight > frameWidth) {
                // Move everything leftwards
                newLeft = newLeft - (newRight - frameWidth)
                newRight = frameWidth
            }
            // Adjust Vertically
            if (newTop < 0) {
                // Move everything downwards
                newBottom = minOf(newBottom - newTop, frameHeight)
                newTop = 0.0
            } else if (newBottom > frameHeight) {
                // Move everything upwards
                newTop = newTop - (newBottom - frameHeight) // Clamp to be sure
                newBottom = frameHeight
            }
            return BoundingBox(newLeft, newRight, newTop, newBottom)
        }
    }

    data class DetectedFace(val confidence: Double, val bb: BoundingBox, val hasMask: Boolean = false, val picture: Bitmap) {
        fun overlapsWith(otherFace: DetectedFace): Boolean {
            return bb.overlapsWith(otherFace.bb)
        }
        // Returns whether there are any other overlapping faces with higher confidence
        fun shadowedBy(faces : List<DetectedFace>): Boolean {
            return faces.any { (it != this) && overlapsWith(it) && (it.confidence > confidence) }
        }

        fun size(): Double {
            return abs(bb.bottom - bb.top)
        }
    }

    fun recognize(picture: Bitmap): List<DetectedFace>
}
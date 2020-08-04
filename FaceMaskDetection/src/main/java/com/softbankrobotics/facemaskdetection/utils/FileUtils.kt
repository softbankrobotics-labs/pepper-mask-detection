package com.softbankrobotics.facemaskdetection.utils

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// Upload file to storage and return a path.
fun assetToAppStoragePath(file: String, context: Context): String {
    val assetManager: AssetManager = context.assets
    var inputStream: BufferedInputStream? = null
    try {

        // Read data from assets.
        inputStream = BufferedInputStream(assetManager.open(file))
        val data = ByteArray(inputStream.available())
        inputStream.read(data)
        inputStream.close()
        // Create copy file in storage.
        val outFile = File(context.filesDir, file.substringAfterLast("/"))
        val os = FileOutputStream(outFile)
        os.write(data)
        os.close()

        // Return a path to file which may be read in common way.
        return outFile.absolutePath
    } catch (ex: IOException) {
        Log.i("assetToAppStoragePath", "Failed to upload a file: $ex")
    }
    return ""
}

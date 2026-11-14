package ru.toinet.android.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter

object DiskUtils {

    @Throws(IOException::class)
    fun readFileFromAssets(assetFilename: String, context: Context): String {
        return context.assets.open(assetFilename).bufferedReader().use { it.readText() }
    }

    @JvmStatic
    fun createWriteFileIntent(filename: String, mimeType: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, filename)
        }

    @JvmStatic
    fun createReadFileIntent(mimeType: String): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
    }

    @JvmStatic
    fun readFileFromInputStream(contentResolver: ContentResolver, file: Uri): String {
        val input = contentResolver.openInputStream(file) ?: throw IOException("Cannot open input stream for $file")
        return input.bufferedReader().use { it.readText() }
    }

    @JvmStatic
    fun recursivelyDeleteDirectory(directory: File): Boolean {
        val contents = directory.listFiles()
        contents?.forEach { recursivelyDeleteDirectory(it) }
        return directory.delete()
    }

    fun readInputStreamAsString(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun flushTextToFile(file: File, text: String, append: Boolean) {
        PrintWriter(FileWriter(file, append)).use { ps ->
            ps.print(text)
            ps.flush()
        }
    }

}
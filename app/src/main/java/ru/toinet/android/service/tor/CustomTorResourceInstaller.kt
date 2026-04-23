package ru.toinet.android.service.tor

import android.content.Context
import ru.toinet.android.service.OrbotConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class CustomTorResourceInstaller(private val context: Context, private val installFolder: File) {

    // Extract the Tor resources from the APK file using ZIP
    @Throws(IOException::class)
    fun installGeoIP() {
        if (!installFolder.exists()) installFolder.mkdirs()
        assetToFile(OrbotConstants.GEOIP_ASSET_KEY, OrbotConstants.GEOIP_ASSET_KEY)
        assetToFile(OrbotConstants.GEOIP6_ASSET_KEY, OrbotConstants.GEOIP6_ASSET_KEY)
    }

    // Reads file from assetPath/assetKey writes it to the install folder
    @Throws(IOException::class)
    private fun assetToFile(assetPath: String, assetKey: String) {
        val inputStream = context.assets.open(assetPath)
        val outFile = File(installFolder, assetKey)
        streamToFile(inputStream, outFile)
    }

    companion object {

        // Write the InputStream contents to the file
        @Throws(IOException::class)
        private fun streamToFile(stm: InputStream, outFile: File) {
            val buffer = ByteArray(1024)
            var byteCount: Int
            val stmOut: OutputStream = FileOutputStream(outFile.absolutePath, false)

            while ((stm.read(buffer).also { byteCount = it }) > 0) {
                stmOut.write(buffer, 0, byteCount)
            }

            stmOut.close()
            stm.close()
        }
    }
}
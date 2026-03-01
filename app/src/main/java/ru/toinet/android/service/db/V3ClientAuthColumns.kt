package ru.toinet.android.service.db

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import androidx.core.net.toUri
import ru.toinet.android.service.OrbotConstants
import java.io.File
import java.io.FileOutputStream

object V3ClientAuthColumns : BaseColumns {
    const val DOMAIN: String = "domain"
    const val HASH: String = "hash"
    const val ENABLED: String = "enabled"

    val V3_CLIENT_AUTH_PROJECTION: Array<String> = arrayOf(BaseColumns._ID, DOMAIN, HASH, ENABLED)

    @JvmStatic
    fun addClientAuthToTorrc(
        torrc: StringBuffer,
        context: Context,
        v3AuthBasePath: File
    ) {
        val uri = getContentUri(context)
        val v3auths: Cursor? = context.applicationContext.contentResolver.query(
            uri,
            V3_CLIENT_AUTH_PROJECTION,
            "$ENABLED=1",
            null,
            null
        )
        if (v3auths == null) return
        v3AuthBasePath.walkTopDown().forEach {
            if (!it.isDirectory) it.delete()
        }
        var i = 0
        try {
            while (v3auths.moveToNext()) {
                val domainIndex = v3auths.getColumnIndex(DOMAIN)
                val hashIndex = v3auths.getColumnIndex(HASH)
                // Ensure that are have all the indexes before trying to use them
                if (domainIndex < 0 || hashIndex < 0) continue
                val domain = v3auths.getString(domainIndex)
                val hash = v3auths.getString(hashIndex)
                val authFile = File(v3AuthBasePath, "${i++}.auth_private")
                authFile.createNewFile()
                val fos = FileOutputStream(authFile)
                fos.write(buildV3ClientAuthFile(domain, hash).toByteArray())
                fos.close()
            }
            if (i > 0)
                torrc.append("ClientOnionAuthDir " + v3AuthBasePath.absolutePath).append('\n')

        } catch (e: Exception) {
            Log.e("V3ClientAuthColumns", "error adding v3 client auth...")
        } finally {
            v3auths.close()
        }
    }

    @JvmStatic
    fun createV3AuthDir(contextWrapper: ContextWrapper): File {
        val baseDir = File(contextWrapper.filesDir.absolutePath, OrbotConstants.V3_CLIENT_AUTH_DIR)
        if (!baseDir.isDirectory) baseDir.mkdirs()
        return baseDir
    }

    @JvmStatic
    fun buildV3ClientAuthFile(domain: String, keyHash: String): String {
        return "$domain:descriptor:x25519:$keyHash"
    }

    private fun getContentUri(context: Context) : Uri{
        return "content://${context.applicationContext.packageName}.ui.v3onionservice.clientauth/v3auth".toUri()
    }

}

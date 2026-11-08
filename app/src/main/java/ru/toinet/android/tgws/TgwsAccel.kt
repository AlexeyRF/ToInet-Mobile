package ru.toinet.android.tgws

import android.util.Log

object TgwsAccel {
    private const val TAG = "TgwsAccel"
    var isAvailable = false
        private set

    init {
        try {
            System.loadLibrary("tgws")
            isAvailable = true
            Log.i(TAG, "Native TGWS C++ acceleration loaded successfully")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load native libtgws: ${e.message}")
            isAvailable = false
        }
    }

    external fun nativeWsMaskInplace(data: ByteArray, len: Int, mask: Int)
    external fun nativeWsMaskCopy(src: ByteArray, dst: ByteArray, len: Int, mask: Int)
    external fun nativeFindPackets(
        protoType: Int,
        plainBuf: ByteArray,
        bufLen: Int,
        startOffset: Int,
        outOffsets: IntArray,
        outLengths: IntArray,
        maxPackets: Int
    ): Int
}

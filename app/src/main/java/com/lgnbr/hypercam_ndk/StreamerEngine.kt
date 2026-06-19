package com.lgnbr.hypercam_ndk

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class StreamerEngine {
    companion object {
        private const val TAG = "HyperCamEngine"
    }

    val width = 1280
    val height = 720

    private var outputStream: OutputStream? = null
    private var imageReader: ImageReader? = null

    // Guarantees a stable, always-available handler tied to Android's main loop architecture
    private val mainHandler = Handler(Looper.getMainLooper())

    private val compressionExecutor = Executors.newSingleThreadExecutor()
    private val sizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
    private val isProcessingFrame = AtomicBoolean(false)
    @Volatile private var isRunning = false

    val inputSurface: Surface?
        get() = imageReader?.surface

    fun assignSocket(socket: Socket) {
        this.outputStream = socket.getOutputStream()
    }

    fun initImageReader() {
        imageReader?.close()
        // Safely allocate image stream memory slots
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
    }

    fun startProcessing() {
        isRunning = true

        // Pass the guaranteed mainHandler. The actual image processing work
        // is offloaded off-thread instantly inside compressionExecutor.
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            if (!isRunning) {
                image.close()
                return@setOnImageAvailableListener
            }

            if (isProcessingFrame.compareAndSet(false, true)) {
                compressionExecutor.execute {
                    try {
                        val planes = image.planes
                        val yBuffer = planes[0].buffer
                        val uBuffer = planes[1].buffer
                        val vBuffer = planes[2].buffer

                        val ySize = yBuffer.remaining()
                        val uSize = uBuffer.remaining()
                        val vSize = vBuffer.remaining()

                        val nv21Bytes = ByteArray(ySize + uSize + vSize)
                        yBuffer.get(nv21Bytes, 0, ySize)
                        vBuffer.get(nv21Bytes, ySize, vSize)
                        uBuffer.get(nv21Bytes, ySize + vSize, uSize)

                        val outStream = ByteArrayOutputStream()
                        val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
                        yuvImage.compressToJpeg(Rect(0, 0, width, height), 60, outStream)

                        val jpegBytes = outStream.toByteArray()

                        synchronized(sizeBuffer) {
                            if (isRunning) {
                                sizeBuffer.clear()
                                sizeBuffer.putInt(jpegBytes.size)
                                outputStream?.write(sizeBuffer.array())
                                outputStream?.write(jpegBytes)
                                outputStream?.flush()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Socket write error: ${e.message}")
                    } finally {
                        image.close()
                        isProcessingFrame.set(false)
                    }
                }
            } else {
                image.close()
            }
        }, mainHandler)
    }

    fun stopProcessing() {
        isRunning = false
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
    }

    fun releaseGlobalResources() {
        stopProcessing()
        compressionExecutor.shutdownNow()
    }
}
package com.lgnbr.hypercam_ndk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class StreamerService : Service() {

    companion object {
        private const val TAG = "HyperCamService"
        private const val CHANNEL_ID = "HyperCamServiceChannel"
        private const val PORT = 5001

        private const val CMD_START = 1
        private const val CMD_STOP = 2
        private const val CMD_FOCUS = 3
    }

    private lateinit var streamerEngine: StreamerEngine
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    @Volatile private var isNetworkLoopRunning = false

    // Main thread marshal handler for service pipeline control
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    private var lastRequestBuilder: CaptureRequest.Builder? = null

    override fun onCreate() {
        super.onCreate()
        streamerEngine = StreamerEngine()
        streamerEngine.initImageReader()

        createNotificationChannel()
        startForeground(1, createNotification())
        listenForPythonClient()
    }

    private fun listenForPythonClient() {
        isNetworkLoopRunning = true
        thread(start = true, isDaemon = true) {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Headless server waiting on port $PORT...")

                while (isNetworkLoopRunning) {
                    clientSocket = serverSocket?.accept()

                    clientSocket?.tcpNoDelay = true          // Disable Nagle's algorithm to send frames instantly
                    clientSocket?.sendBufferSize = 512 * 1024 // Expand the internal buffer size to 512KB

                    inputStream = clientSocket?.getInputStream()
                    streamerEngine.assignSocket(clientSocket!!)
                    Log.d(TAG, "Python client connected to headless host!")

                    handleControlCommands()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network listener error: ${e.message}")
            }
        }
    }

    private fun handleControlCommands() {
        val stream = inputStream ?: return
        try {
            while (isNetworkLoopRunning) {
                val command = stream.read()
                if (command == -1) break

                when (command) {
                    CMD_START -> {
                        Log.d(TAG, "Remote Command Received: START")
                        mainThreadHandler.post { startCameraCapture() }
                    }
                    CMD_STOP -> {
                        Log.d(TAG, "Remote Command Received: STOP")
                        mainThreadHandler.post { stopCameraCapture() }
                    }
                    CMD_FOCUS -> {
                        // Python must send a second byte right after CMD_FOCUS (value between 0 and 100)
                        val focusPercentage = stream.read()
                        if (focusPercentage != -1) {
                            mainThreadHandler.post { applyManualFocus(focusPercentage) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control channel error: ${e.message}")
        } finally {
            mainThreadHandler.post { stopCameraCapture() }
        }
    }

    private fun startCameraCapture() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            streamerEngine.initImageReader()
            val surface = streamerEngine.inputSurface ?: return

            cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera

                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface)

                                    // FORCE AUTO FOCUS OFF
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                }
                                lastRequestBuilder = requestBuilder

                                session.setRepeatingRequest(requestBuilder.build(), null, mainThreadHandler)
                                streamerEngine.startProcessing()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error setting up repeating request: ${e.message}")
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Camera pipeline configuration failure.")
                        }
                    }, mainThreadHandler)
                }

                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, mainThreadHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing camera permissions: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera capture: ${e.message}")
        }
    }

    private fun applyManualFocus(percentage: Int) {
        val session = captureSession ?: return
        val builder = lastRequestBuilder ?: return
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val characteristics = cameraManager.getCameraCharacteristics("0")
            // Find the minimum focus distance (which is actually the closest macro distance, e.g., 10.0)
            val minFocusDistance = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f

            if (minFocusDistance == 0.0f) {
                Log.w(TAG, "Fixed focus lens detected. Manual focus unavailable.")
                return
            }

            // Map 0-100% to 0.0f -> minFocusDistance diopters
            // 0% = 0.0f (Infinity), 100% = minFocusDistance (Closest Macro focus)
            val diopterValue = (percentage / 100.0f) * minFocusDistance

            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopterValue)
            session.setRepeatingRequest(builder.build(), null, mainThreadHandler)
            Log.d(TAG, "Applied Focus Diopter: $diopterValue ($percentage%)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply manual focus: ${e.message}")
        }
    }

    private fun stopCameraCapture() {
        Log.d(TAG, "Stopping camera and flushing active engines...")
        streamerEngine.stopProcessing()
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {}
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isNetworkLoopRunning = false
        stopCameraCapture()
        streamerEngine.releaseGlobalResources()
        inputStream?.close()
        clientSocket?.close()
        serverSocket?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "HyperCam Headless Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("HyperCam Service Active")
            .setContentText("Listening for background remote control...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}
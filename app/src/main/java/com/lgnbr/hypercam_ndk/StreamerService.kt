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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
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
                        // FIX: Force the hardware execution to process on the main lifecycle context
                        mainThreadHandler.post { startCameraCapture() }
                    }
                    CMD_STOP -> {
                        Log.d(TAG, "Remote Command Received: STOP")
                        mainThreadHandler.post { stopCameraCapture() }
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
                                }
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
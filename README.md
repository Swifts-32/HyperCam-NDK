# HyperCam NDK

A high-performance, headless Android background streaming service that captures camera frames, compresses them into JPEG format via worker pools, and pipes them over a local socket bridge.

## 🌿 Overview

HyperCam NDK is designed to eliminate the overhead of traditional Android camera preview interfaces. By moving the entire hardware capture pipeline into a persistent **Foreground Service** and utilizing a centralized thread pool layout, the application can run completely detached from any visual UI. It waits silently for upstream network control bytes (`START` / `STOP`) to wake up or put the camera hardware to sleep.

## 🏗️ Architecture

* **MainActivity**: Handles raw system camera permissions on initial launch, triggers the background service, and immediately closes itself to remain headless.
* **StreamerService**: A persistent foreground component running a dual-channel TCP server loop on port `5001`. It listens for incoming runtime control bytes over ADB.
* **StreamerEngine**: Tracks and processes image buffers. It uses Android's native image queues and offloads heavy frame conversions into an optimized thread pipeline to prevent frame drops.

## 🛠️ Requirements

* Android SDK 26 (Android 8.0 Oreo) or higher
* Physical Android Device with an accessible back camera (`Camera ID 0`)
* USB Debugging enabled

## 🚀 Getting Started

1. Clone or import this repository into **Android Studio**.
2. Connect your target physical test device via USB.
3. Compile and run the application. 
4. The application window will flash for a split second to clear runtime permissions, dismiss itself, and launch a persistent status tray notification. The server is now waiting for an inbound connection on port `5001`.

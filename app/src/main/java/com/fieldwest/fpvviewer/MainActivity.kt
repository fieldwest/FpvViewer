package com.fieldwest.fpvviewer

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

import android.widget.TextView
import java.net.NetworkInterface
import java.net.Inet4Address
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.net.wifi.WifiInfo
import android.view.View

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val PORT = 5000
    private var codec: MediaCodec? = null
    private var running = AtomicBoolean(false)
    private var recvThread: Thread? = null

    private lateinit var debugText: TextView

    private var frameCount = 0
    private var lastTime = System.currentTimeMillis()
    private var receivedBytes = 0L

    private var localIp = ""

    private lateinit var gestureDetector: GestureDetector

    private var lastNalType = -1
    @Volatile
    private var specFreq = -1

    private lateinit var statusText: TextView

    private var frameTimestamp = 0L
    private val frameDurationUs = 1_000_000L / 60 // 60fps想定

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        debugText = findViewById(R.id.debugText)
        surfaceView.holder.addCallback(this)

        debugText.visibility = android.view.View.GONE

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleHud()
                return true
            }
        })
        surfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        val dm = resources.displayMetrics
        debugText.y = dm.heightPixels * 0.05f

        statusText = findViewById(R.id.statusText)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiving()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startReceiving(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopReceiving()
    }

    private fun startReceiving(holder: SurfaceHolder) {
        if (currentSsid()?.startsWith("FPV-AP-") == false) {
            showStatus("WiFiをFPV-AP-XXXXに接続してくだだい")
            return
        } else {
            showStatus(null)
        }

        running.set(true)
        recvThread = Thread {
            val socket = DatagramSocket(PORT)
            socket.receiveBufferSize = 4 * 1024 * 1024
            android.util.Log.i("FPV", "actual recv buf = ${socket.receiveBufferSize}")
            val buf = ByteArray(65536)
            val packet = DatagramPacket(buf, buf.size)

            // NAL組み立て用バッファ(使い回す、伸長のみ)
            var nalBuf = ByteArray(65536)
            var nalLen = 0
            var zeroRun = 0 // 連続する0x00の数(スタートコード検出用)
            var mediaCodecStarted = false

            fun ensureCapacity(extra: Int) {
                if (nalLen + extra > nalBuf.size) {
                    nalBuf = nalBuf.copyOf(maxOf(nalBuf.size * 2, nalLen + extra))
                }
            }

            fun tryFeedNal(src: ByteArray, len: Int) {
                if (len <= 0) return
                val nalType = src[0].toInt() and 0x1F
                lastNalType = nalType

                if (!mediaCodecStarted) {
                    // 最初のSPS(7)を見つけたらcodec初期化
                    if (nalType == 7) {
                        val format = MediaFormat.createVideoFormat("video/avc", 320, 240)
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        codec = MediaCodec.createDecoderByType("video/avc")
                        codec?.configure(format, holder.surface, null, 0)
                        codec?.start()
                        mediaCodecStarted = true
                    } else {
                        return
                    }
                }

                val c = codec ?: return
                val inIndex = c.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = c.getInputBuffer(inIndex)
                    inputBuffer?.clear()
                    // スタートコード(0x00000001)を付けて投入
                    inputBuffer?.put(byteArrayOf(0, 0, 0, 1))
                    inputBuffer?.put(src, 0, len)
                    c.queueInputBuffer(inIndex, 0, (len + 4), frameTimestamp, 0)
                    frameTimestamp += frameDurationUs
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    updateFPS()
                    c.releaseOutputBuffer(outIndex, true)
                    outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            while (running.get()) {
                try {
                    packet.setLength(buf.size)
                    socket.receive(packet)
                    val offset = packet.offset
                    val length = packet.length
                    receivedBytes += length

                    var i = 0
                    while (i < length) {
                        val b = buf[offset + i]

                        if (b.toInt() == 0) {
                            zeroRun++
                        } else {
                            if (b.toInt() == 1 && zeroRun >= 3) {
                                // スタートコード(00 00 00 01)を検出
                                // すでにnalBufに溜まっている分(スタートコード直前まで)を確定
                                val nalLenWithoutStart = nalLen - 3
                                if (nalLenWithoutStart > 0) {
                                    tryFeedNal(nalBuf, nalLenWithoutStart)
                                }
                                nalLen = 0
                                zeroRun = 0
                                i++
                                continue
                            }
                            zeroRun = 0
                        }

                        ensureCapacity(1)
                        nalBuf[nalLen++] = b
                        i++
                    }
                } catch (e: Exception) {
                    if (running.get()) e.printStackTrace()
                }
            }
            socket.close()
        }
        recvThread?.start()
    }

    private fun stopReceiving() {
        running.set(false)
        recvThread?.interrupt()
        codec?.stop()
        codec?.release()
        codec = null
    }
    private fun updateDebugHud(text: String) {
        debugText.text = text
    }
    private fun getLocalIp(): String {
        if(localIp == "") {
            localIp = "0.0.0.0"
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                val addrs = iface.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        localIp = addr.hostAddress
                        return localIp
                    }
                }
            }
        }
        return localIp
    }
    private fun getWifiRssi(): Int {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return Int.MIN_VALUE // 権限なし
        }

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return wm.connectionInfo?.rssi ?: Int.MIN_VALUE
    }

    private fun updateFPS() {
        if(debugText.visibility == android.view.View.GONE){
            return;
        }

        // FPS計測
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastTime >= 1000) {
            val fps = frameCount
            val elapsedMs = now - lastTime
            val elapsedSec = elapsedMs / 1000.0
            val bitrateMbps = receivedBytes * 8 / 1_000_000.0 / elapsedSec

            frameCount = 0
            receivedBytes = 0
            lastTime = now
            val rssi = getWifiRssi()
            val rssiText = if (rssi == Int.MIN_VALUE) "N/A" else "$rssi dBm"
            runOnUiThread {
                updateDebugHud(
                    """
                        SSID: ${currentSsid()}
                        CH: ${channelText()}
                        IP: ${getLocalIp()}
                        FPS: $fps
                        Bitrate: ${"%.2f".format(bitrateMbps)} Mbps
                        RSSI: $rssiText
                        NAL: ${nalTypeToString(lastNalType)}
                        Codec: READY
                        """.trimIndent()
                )
            }
        }
    }

    private fun channelText(): String {
        val f = if (specFreq > 0) specFreq else (wifiInfo()?.frequency ?: -1)
        val ch = when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2407) / 5
            f in 5170..5825 -> (f - 5000) / 5
            else -> -1
        }
        specFreq = f
        return if (ch < 0) "N/A" else "$ch (${f}MHz)"
    }

    private fun toggleHud() {
        debugText.visibility =
            if (debugText.visibility == android.view.View.VISIBLE)
                android.view.View.GONE
            else
                android.view.View.VISIBLE
    }
    private fun nalTypeToString(type: Int): String {
        return when (type) {
            1 -> "P"
            5 -> "IDR"
            7 -> "SPS"
            8 -> "PPS"
            else -> "NAL$type"
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(): WifiInfo? {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return wm.connectionInfo
    }

    private fun currentSsid(): String? {
        var ssid = wifiInfo()?.ssid?.trim('"') ?: return ""
        return if (ssid == "<unknown ssid>" || ssid.isEmpty()) "" else ssid
    }

    private fun showStatus(msg: String?) = runOnUiThread {
        if (msg == null) {
            statusText.visibility = View.GONE
        } else {
            statusText.text = msg
            statusText.visibility = View.VISIBLE
        }
    }
}

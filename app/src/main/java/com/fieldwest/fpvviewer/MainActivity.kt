package com.fieldwest.fpvviewer

import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val PORT = 5000

    // フレーム出力がこれ以上途切れたら「固まり」として記録する
    private val STALL_MS = 100.0

    private var codec: MediaCodec? = null
    private var running = AtomicBoolean(false)
    private var recvThread: Thread? = null

    @Volatile
    private var socket: DatagramSocket? = null

    private lateinit var debugText: TextView
    private lateinit var statusText: TextView
    private lateinit var gestureDetector: GestureDetector

    private var localIp = ""
    private var lastNalType = -1

    @Volatile
    private var specFreq = -1

    private var frameTimestamp = 0L
    private val frameDurationUs = 1_000_000L / 60 // 60fps想定
    private val startCode = byteArrayOf(0, 0, 0, 1)

    // ------------------------------------------------------------
    // 計測用(受信スレッドだけが更新する)
    // ------------------------------------------------------------
    private var startNs = 0L
    private var winStartMs = 0L
    private var lastFrameNs = 0L

    // 1秒ウィンドウの集計
    private var frames = 0
    private var rxBytes = 0L
    private var cntIdr = 0
    private var cntP = 0
    private var cntSps = 0
    private var cntPps = 0
    private var cntDrop = 0      // 壊れたNALとして捨てた数
    private var cntInDrop = 0    // デコーダの入力バッファが取れず捨てた数
    private var maxFrameGapMs = 0.0
    private var maxWaitMs = 0.0  // receive()でデータ待ちしていた最大時間
    private var maxProcMs = 0.0  // デコーダへの投入にかかった最大時間
    private var stallCount = 0

    // 直近のフレーム出力からの累計(固まり記録用)
    private var gapMaxWait = 0.0
    private var gapMaxProc = 0.0
    private var gapPkts = 0
    private var gapNals = 0
    private var gapIdr = 0
    private var gapInDrop = 0

    // 固まり記録のときにバインダー呼び出しを避けるためのキャッシュ
    @Volatile
    private var lastRssi = Int.MIN_VALUE

    @Volatile
    private var lastBitrate = 0.0

    // ------------------------------------------------------------
    // ログ(stall.log / stall.old.log の2ファイルで回す)
    // ------------------------------------------------------------
    private val LOG_MAX_BYTES = 500_000L        // stall.logの上限
    private var logDir: File? = null
    private var logWritten = 0L                 // stall.logに書いた量(書き込みスレッドだけが更新)
    private val logExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var logOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        debugText = findViewById(R.id.debugText)
        surfaceView.holder.addCallback(this)

        debugText.visibility = View.GONE

        // ダブルタップ: HUD表示切替 / 長押し: ログON/OFF切替
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleHud()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                toggleLog()
            }
        })
        surfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        val dm = resources.displayMetrics
        debugText.y = dm.heightPixels * 0.05f

        statusText = findViewById(R.id.statusText)

        // /sdcard/Android/data/com.fieldwest.fpvviewer/files/stall.log
        logDir = getExternalFilesDir(null)
        logDir?.let { d ->
            val f = File(d, "stall.log")
            logWritten = if (f.exists()) f.length() else 0L
        }
        logOn = getSharedPreferences("fpv", MODE_PRIVATE).getBoolean("logOn", true)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiving()
        logExecutor.shutdown()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startReceiving(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopReceiving()
    }

    // ------------------------------------------------------------
    // 受信・デコード
    // ------------------------------------------------------------

    private fun startReceiving(holder: SurfaceHolder) {
        if (currentSsid()?.startsWith("FPV-AP-") == false) {
            showStatus("WiFiをFPV-AP-XXXXに接続してください")
            return
        } else {
            showStatus(null)
        }

        resetStats()
        running.set(true)
        appendLog("---- session start ${nowText()} ----")

        recvThread = Thread {
            val s = try {
                DatagramSocket(PORT)
            } catch (e: Exception) {
                e.printStackTrace()
                running.set(false)
                showStatus("ポート$PORT を開けません。アプリを再起動してください")
                return@Thread
            }
            socket = s
            s.receiveBufferSize = 4 * 1024 * 1024
            s.soTimeout = 200 // データが来なくてもHUDを更新できるように定期的に起きる
            Log.i("FPV", "actual recv buf = ${s.receiveBufferSize}")

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

                // forbidden_zero_bit が立っているNALは壊れているので捨てる
                if ((src[0].toInt() and 0x80) != 0) {
                    cntDrop++
                    return
                }

                val nalType = src[0].toInt() and 0x1F
                lastNalType = nalType
                gapNals++
                when (nalType) {
                    5 -> { cntIdr++; gapIdr++ }
                    1 -> cntP++
                    7 -> cntSps++
                    8 -> cntPps++
                }

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
                val tp0 = System.nanoTime()
                val inIndex = c.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = c.getInputBuffer(inIndex)
                    inputBuffer?.clear()
                    // スタートコード(0x00000001)を付けて投入
                    inputBuffer?.put(startCode)
                    inputBuffer?.put(src, 0, len)
                    c.queueInputBuffer(inIndex, 0, (len + 4), frameTimestamp, 0)
                    frameTimestamp += frameDurationUs
                } else {
                    cntInDrop++
                    gapInDrop++
                }
                val feedMs = (System.nanoTime() - tp0) / 1e6
                if (feedMs > maxProcMs) maxProcMs = feedMs
                if (feedMs > gapMaxProc) gapMaxProc = feedMs

                val bufferInfo = MediaCodec.BufferInfo()
                var outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    onFrameOut()
                    c.releaseOutputBuffer(outIndex, true)
                    outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            while (running.get()) {
                val t0 = System.nanoTime()
                try {
                    packet.setLength(buf.size)
                    s.receive(packet)
                } catch (e: SocketTimeoutException) {
                    noteWait((System.nanoTime() - t0) / 1e6)
                    tick(System.currentTimeMillis())
                    continue
                } catch (e: Exception) {
                    if (!running.get() || s.isClosed) break
                    e.printStackTrace()
                    continue
                }
                noteWait((System.nanoTime() - t0) / 1e6)
                gapPkts++

                val offset = packet.offset
                val length = packet.length
                rxBytes += length

                try {
                    var i = 0
                    while (i < length) {
                        val b = buf[offset + i]

                        if (b.toInt() == 0) {
                            zeroRun++
                        } else {
                            if (b.toInt() == 1 && zeroRun >= 3) {
                                // スタートコード(00 00 00 01)を検出
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
                    if (running.get()) Log.e("FPV", "decode error", e)
                }

                tick(System.currentTimeMillis())
            }
            try {
                s.close()
            } catch (e: Exception) {
            }
            socket = null
        }
        recvThread?.start()
    }

    private fun stopReceiving() {
        running.set(false)
        // receive()でブロック中のスレッドを起こすため、ソケットを閉じる
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        recvThread = null
        try {
            codec?.stop()
        } catch (e: Exception) {
        }
        try {
            codec?.release()
        } catch (e: Exception) {
        }
        codec = null
    }

    // ------------------------------------------------------------
    // 計測
    // ------------------------------------------------------------

    private fun resetStats() {
        startNs = System.nanoTime()
        winStartMs = 0L
        lastFrameNs = 0L
        frames = 0
        rxBytes = 0L
        cntIdr = 0
        cntP = 0
        cntSps = 0
        cntPps = 0
        cntDrop = 0
        cntInDrop = 0
        maxFrameGapMs = 0.0
        maxWaitMs = 0.0
        maxProcMs = 0.0
        stallCount = 0
        gapMaxWait = 0.0
        gapMaxProc = 0.0
        gapPkts = 0
        gapNals = 0
        gapIdr = 0
        gapInDrop = 0
    }

    private fun noteWait(ms: Double) {
        if (ms > maxWaitMs) maxWaitMs = ms
        if (ms > gapMaxWait) gapMaxWait = ms
    }

    // フレームが1枚出力されるたびに呼ぶ
    private fun onFrameOut() {
        val now = System.nanoTime()
        frames++
        if (lastFrameNs != 0L) {
            val gapMs = (now - lastFrameNs) / 1e6
            if (gapMs > maxFrameGapMs) maxFrameGapMs = gapMs
            if (gapMs > STALL_MS) {
                stallCount++
                if (logOn) {
                    logStall(gapMs, now)
                }
            }
        }
        lastFrameNs = now

        gapMaxWait = 0.0
        gapMaxProc = 0.0
        gapPkts = 0
        gapNals = 0
        gapIdr = 0
        gapInDrop = 0
    }

    // 固まりの原因を後から切り分けるための記録
    //  wait  : receive()でデータ待ちしていた最大時間(大きい→データが来ていない=Pi/電波側)
    //  proc  : デコーダへの投入にかかった最大時間(大きい→スマホ側の処理遅れ)
    //  pkts  : 固まっている間に届いたパケット数(多いのにフレームが出ない→IDR待ちなど)
    //  nals  : 同じくNAL数、idr : そのうちIDRの数、inDrop : デコーダ入力が詰まって捨てた数
    private fun logStall(gapMs: Double, nowNs: Long) {
        if (!logOn) return
        val up = (nowNs - startNs) / 1e9
        val rssiText = if (lastRssi == Int.MIN_VALUE) "NA" else lastRssi.toString()
        val line = String.format(
            Locale.US,
            "%s STALL gap=%.0fms up=%.1fs wait=%.0fms proc=%.0fms pkts=%d nals=%d idr=%d inDrop=%d rssi=%s br=%.2f",
            nowText(), gapMs, up, gapMaxWait, gapMaxProc,
            gapPkts, gapNals, gapIdr, gapInDrop, rssiText, lastBitrate
        )
        Log.w("FPVSTALL", line)
        appendLog(line)
    }

    // ログへの追記は別スレッドで行う。受信スレッドはキューに投げるだけ。
    // stall.log が上限に達したら、stall.old.log を消して stall.log をリネームし、
    // 同じ名前の stall.log を新しく作って書き始める。
    private fun appendLog(line: String) {
        if (!logOn) return
        val dir = logDir ?: return
        val bytes = line.length + 1L
        try {
            logExecutor.execute {
                try {
                    val cur = File(dir, "stall.log")
                    val old = File(dir, "stall.old.log")
                    if (logWritten + bytes > LOG_MAX_BYTES) {
                        if (old.exists()) old.delete()  // 古い退避分を消す
                        cur.renameTo(old)               // 今のログを退避
                        logWritten = 0L                 // 同じ名前で新しく書き始める
                    }
                    cur.appendText(line + "\n")
                    logWritten += bytes
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun toggleLog() {
        logOn = !logOn
        getSharedPreferences("fpv", MODE_PRIVATE).edit().putBoolean("logOn", logOn).apply()
        Toast.makeText(this, if (logOn) "ログ: ON" else "ログ: OFF", Toast.LENGTH_SHORT).show()
    }

    private fun nowText(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    // 1秒ごとにウィンドウを締めて、HUDを更新する
    private fun tick(nowMs: Long) {
        if (winStartMs == 0L) {
            winStartMs = nowMs
            return
        }
        val el = nowMs - winStartMs
        if (el < 1000) return

        val sec = el / 1000.0
        val fps = frames / sec
        val br = rxBytes * 8 / 1_000_000.0 / sec
        lastBitrate = br

        val i = cntIdr
        val p = cntP
        val sps = cntSps
        val pps = cntPps
        val drop = cntDrop
        val inDrop = cntInDrop
        val waitMax = maxWaitMs
        val feedMax = maxProcMs
        val stalls = stallCount
        val upSec = ((System.nanoTime() - startNs) / 1e9).toInt()

        // 今まさに途切れている時間も含める
        val ongoing = if (lastFrameNs != 0L) (System.nanoTime() - lastFrameNs) / 1e6 else 0.0
        val gapMax = maxOf(maxFrameGapMs, ongoing)

        frames = 0
        rxBytes = 0L
        cntIdr = 0
        cntP = 0
        cntSps = 0
        cntPps = 0
        cntDrop = 0
        cntInDrop = 0
        maxFrameGapMs = 0.0
        maxWaitMs = 0.0
        maxProcMs = 0.0
        winStartMs = nowMs

        val hudVisible = debugText.visibility == View.VISIBLE
        if (!hudVisible) return

        runOnUiThread {
            if (debugText.visibility != View.VISIBLE) return@runOnUiThread
            val rssi = getWifiRssi()
            lastRssi = rssi
            val rssiText = if (rssi == Int.MIN_VALUE) "N/A" else "$rssi dBm"
            updateDebugHud(
                """
                    SSID: ${currentSsid()}
                    CH: ${channelText()}  RSSI: $rssiText
                    IP: ${getLocalIp()}
                    FPS: ${"%.1f".format(fps)}  GapMax: ${"%.0f".format(gapMax)}ms
                    Bitrate: ${"%.2f".format(br)} Mbps
                    NAL/s  I:$i P:$p S:$sps PP:$pps
                    DROP: $drop  INDROP: $inDrop
                    Wait: ${"%.0f".format(waitMax)}ms  Feed: ${"%.0f".format(feedMax)}ms
                    Stalls: $stalls  Up: ${upSec / 60}:${"%02d".format(upSec % 60)}
                    NAL: ${nalTypeToString(lastNalType)}  Log: ${if (logOn) "ON" else "OFF"}
                    """.trimIndent()
            )
        }
    }

    // ------------------------------------------------------------
    // HUD・Wi-Fi情報
    // ------------------------------------------------------------

    private fun updateDebugHud(text: String) {
        debugText.text = text
    }

    private fun getLocalIp(): String {
        if (localIp == "") {
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
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Int.MIN_VALUE // 権限なし
        }
        return wifiInfo()?.rssi ?: Int.MIN_VALUE
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
            if (debugText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
        val ssid = wifiInfo()?.ssid?.trim('"') ?: return ""
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

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onResume() {
        super.onResume()
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "fpv").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onPause() {
        super.onPause()
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

}

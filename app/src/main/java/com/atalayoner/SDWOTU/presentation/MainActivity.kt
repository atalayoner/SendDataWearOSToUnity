package com.atalayoner.SDWOTU

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var lightSensor: Sensor? = null

    private lateinit var connectionStatusTextView: TextView
    private lateinit var deviceIpTextView: TextView
    private lateinit var currentHeartRateTextView: TextView
    private lateinit var averageHeartRateTextView: TextView
    private lateinit var targetIpEditText: EditText

    private lateinit var audioRecord: AudioRecord
    private val bufferSize = AudioRecord.getMinBufferSize(
        44100,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private var isRecording = false

    private var currentLightLevel: Float = 0.0f
    private var currentHeartRate: Float = 0.0f
    private lateinit var currentHeartSensorStatus : String
    private var currentSoundLevel: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkPermissions()

        connectionStatusTextView = findViewById(R.id.connectionStatusTextView)
        targetIpEditText = findViewById(R.id.targetIpEditText)
        deviceIpTextView = findViewById(R.id.deviceIpTextView)
        currentHeartRateTextView = findViewById(R.id.currentHeartRateTextView)
        averageHeartRateTextView = findViewById(R.id.averageHeartRateTextView)
        val deviceIP = getLocalIPAddress()
        deviceIpTextView.text = deviceIP

        val syncButton: Button = findViewById(R.id.syncButton)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (heartRateSensor == null) {
            Toast.makeText(this, "Heart rate sensor not available!", Toast.LENGTH_SHORT).show()
            return
        }
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            Toast.makeText(this, "Light sensor not available!", Toast.LENGTH_SHORT).show()
        }
        syncButton.setOnClickListener {
            val ipAddress = targetIpEditText.text.toString()
            connectToServer(ipAddress)
        }
    }
    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECORD_AUDIO
        )
        ActivityCompat.requestPermissions(this, permissions, 1)
    }
    override fun onResume() {
        super.onResume()
        heartRateSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
        lightSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        startRecording()
    }
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopRecording()
    }
    private fun getLocalIPAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (inetAddress in addresses) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "IP bulunamadı"
    }
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            currentHeartRate = event.values[0]
            currentHeartRateTextView.text = "CUR: ${currentHeartRate.toInt()} bpm"
        }
        else if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            currentLightLevel = event.values[0]
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                currentHeartSensorStatus = "UNRELIABLE"
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                currentHeartSensorStatus = "LOW"
            }
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                currentHeartSensorStatus = "MEDIUM"
            }
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                currentHeartSensorStatus = "HIGH"
            }
        }
    }
    private fun connectToServer(targetIp: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = Socket(targetIp, 8080)
                withContext(Dispatchers.Main) {
                    connectionStatusTextView.text = "Bağlandı: $targetIp"
                }
                sendData(socket)
            } catch (e: IOException) {
                Log.e("Connection Error", "IO Exception: ${e.message}")
            }
        }
    }
    private suspend fun sendData(socket: Socket) {
        val outputStream = socket.getOutputStream()
        val writer = PrintWriter(OutputStreamWriter(outputStream), true)

        while (true) {
            if(socket.isClosed) return
            val dataToSend = "$currentHeartRate/$currentHeartSensorStatus/$currentLightLevel/$currentSoundLevel"
            writer.println(dataToSend)
            Log.d("SyncActivity", "Sent data: $dataToSend")
            delay(1000)
        }
    }
    private fun calculateRMS(buffer: ShortArray, readCount: Int): Double {
        var sum: Long = 0
        for (i in 0 until readCount) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum.toDouble() / readCount)
    }
    private fun startRecording() {
        // audioRecord'un başlatılmasını kontrol et
        if (!::audioRecord.isInitialized) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            // audioRecord nesnesini başlat
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            // audioRecord nesnesini başlat
            audioRecord.startRecording()
            isRecording = true

            CoroutineScope(Dispatchers.Default).launch {
                val buffer = ShortArray(bufferSize)
                while (isRecording) {
                    val readCount = audioRecord.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        val rms = calculateRMS(buffer, readCount)
                        currentSoundLevel = if (rms > 0) {
                            val maxAmplitude = 32767.0
                            val soundLevel = 20 * log10(rms / maxAmplitude + 1e-10)
                            soundLevel + 90 // dB düzeltmesi
                        } else {
                            0.0 // Sessizlik
                        }
                    }
                }
            }
        }
    }
    private fun stopRecording() {
        if (::audioRecord.isInitialized && isRecording) {
            isRecording = false
            audioRecord.stop()
            audioRecord.release()
        }
    }
}

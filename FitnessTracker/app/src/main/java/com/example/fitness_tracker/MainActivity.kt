package com.example.fitness_tracker

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitness_tracker.ui.theme.Fitness_TrackerTheme
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.fitness_tracker.ActivityClassifier

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val sensorDataWindow = mutableListOf<FloatArray>()
    private val SENSOR_SAMPLES_PER_WINDOW = 50
    private val SAMPLING_PERIOD_US = 20000
    private var lastSampleTime: Long = 0

    private lateinit var activityClassifier: ActivityClassifier
    private val MODEL_FILE_NAME = "fitness_model2.tflite"

    private var currentRawDataText = mutableStateOf("Данные акселерометра: ожидание...")
    private var classifiedActivityText = mutableStateOf("Активность: ожидание...")

    private var isRecording = mutableStateOf(false)
    private var currentActivityToRecord = mutableStateOf("still")
    private var fileWriter: FileWriter? = null
    private var currentCsvFile: File? = null
    private val activityTypes = listOf("still", "walk", "jump", "run")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity_Lifecycle", "onCreate")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            Log.e("MainActivity_Sensor", "Акселерометр не найден!")
        }

        try {
            activityClassifier = ActivityClassifier(assets, MODEL_FILE_NAME)
            Log.d("MainActivity_CNN", "ActivityClassifier '$MODEL_FILE_NAME' initialized.")
        } catch (e: Exception) {
            Log.e("MainActivity_CNN", "Error initializing ActivityClassifier: ${e.message}", e)
            classifiedActivityText.value = "Ошибка инициализации классификатора"
        }

        setContent {
            Fitness_TrackerTheme {
                MainScreenWithRecording(
                    rawData = currentRawDataText.value,
                    classifiedActivity = classifiedActivityText.value,
                    isRecording = isRecording.value,
                    selectedActivity = currentActivityToRecord.value,
                    activityTypes = activityTypes,
                    onActivitySelected = { currentActivityToRecord.value = it },
                    onStartRecording = { startActivityRecording() },
                    onStopRecording = { stopActivityRecording() }
                )
            }
        }
    }

    private fun startActivityRecording() {
        if (isRecording.value) {
            Toast.makeText(this, "Запись уже идет", Toast.LENGTH_SHORT).show()
            return
        }

        val activityName = currentActivityToRecord.value
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(Date())
        val fileName = "sensor_data_${activityName}_${timestamp}.csv"

        val directory = getExternalFilesDir(null)
        if (directory == null) {
            Log.e("CSV", "Не удалось получить доступ к каталогу для записи.")
            Toast.makeText(this, "Ошибка: не удалось получить доступ к каталогу", Toast.LENGTH_LONG).show()
            return
        }
        currentCsvFile = File(directory, fileName)

        try {
            fileWriter = FileWriter(currentCsvFile)
            fileWriter?.append("timestamp,accX,accY,accZ\n")
            isRecording.value = true
            Log.i("CSV", "Начата запись в файл: ${currentCsvFile?.absolutePath}")
            Toast.makeText(this, "Запись начата: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Log.e("CSV", "Ошибка при создании FileWriter: ${e.message}", e)
            Toast.makeText(this, "Ошибка записи: ${e.message}", Toast.LENGTH_LONG).show()
            isRecording.value = false
            fileWriter = null
            currentCsvFile = null
        }
    }

    private fun stopActivityRecording() {
        if (!isRecording.value) {
            Toast.makeText(this, "Запись не активна", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            fileWriter?.flush()
            fileWriter?.close()
            Log.i("CSV", "Запись в файл ${currentCsvFile?.name} завершена.")
            Toast.makeText(this, "Запись остановлена: ${currentCsvFile?.name}", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Log.e("CSV", "Ошибка при закрытии FileWriter: ${e.message}", e)
            Toast.makeText(this, "Ошибка при остановке записи: ${e.message}", Toast.LENGTH_LONG).show()
        }
        isRecording.value = false
        fileWriter = null
        currentCsvFile = null
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { acc ->
            sensorManager.registerListener(this, acc, SAMPLING_PERIOD_US)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (isRecording.value) {
            stopActivityRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::activityClassifier.isInitialized) {
            activityClassifier.close()
        }
        if (isRecording.value) {
            stopActivityRecording()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val systemCurrentTimeMillis = System.currentTimeMillis()
            val nanoTime = System.nanoTime()

            if (nanoTime - lastSampleTime >= SAMPLING_PERIOD_US * 1000L || lastSampleTime == 0L) {
                lastSampleTime = nanoTime

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                currentRawDataText.value = String.format("X: %.2f, Y: %.2f, Z: %.2f", x, y, z)

                if (isRecording.value && fileWriter != null) {
                    try {
                        fileWriter?.append("$systemCurrentTimeMillis,$x,$y,$z\n")
                    } catch (e: IOException) {
                        Log.e("CSV", "Ошибка при записи данных в CSV: ${e.message}")
                    }
                }

                synchronized(sensorDataWindow) {
                    sensorDataWindow.add(floatArrayOf(x, y, z))
                    while (sensorDataWindow.size > SENSOR_SAMPLES_PER_WINDOW) {
                        sensorDataWindow.removeAt(0)
                    }
                    if (sensorDataWindow.size == SENSOR_SAMPLES_PER_WINDOW) {
                        if (::activityClassifier.isInitialized) {
                            val windowToClassify = ArrayList(sensorDataWindow)
                            processAndClassifySensorData(windowToClassify)
                        }
                    }
                }
            }
        }
    }

    private fun processAndClassifySensorData(windowData: List<FloatArray>) {
        if (!::activityClassifier.isInitialized) return

        val prediction = activityClassifier.classifyWindow(windowData)
        runOnUiThread {
            classifiedActivityText.value = prediction ?: "Ошибка предсказания"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@OptIn(ExperimentalMaterial3Api::class) // Для TopAppBar и других компонентов M3
@Composable
fun MainScreenWithRecording(
    rawData: String,
    classifiedActivity: String,
    isRecording: Boolean,
    selectedActivity: String,
    activityTypes: List<String>,
    onActivitySelected: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding) // Важно применить padding от Scaffold
                .padding(16.dp), // Дополнительный общий padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp) // Пространство между основными блоками
        ) {

            // Секция отображения данных
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Sensor data:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        rawData,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Recognized activity:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        classifiedActivity,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Секция управления записью CSV
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Recording training data",
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Выпадающий список для выбора активности
                    DropdownMenuWithSelection( // Ваша рабочая реализация
                        items = activityTypes,
                        selectedItem = selectedActivity,
                        onItemSelected = onActivitySelected
                    )

                    // Кнопки управления записью
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        Button(
                            onClick = onStartRecording,
                            enabled = !isRecording,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FiberManualRecord, contentDescription = "Start recording", modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Start")
                        }
                        Button(
                            onClick = onStopRecording,
                            enabled = isRecording,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.StopCircle, contentDescription = "Stop recording", modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Stop")
                        }
                    }

                    if (isRecording) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Recording in progress",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Recording in progress...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f)) // Заполнитель, чтобы прижать контент вверх, если его мало
        }
    }
}

// Ваш рабочий DropdownMenuWithSelection остается без изменений:
@Composable
fun DropdownMenuWithSelection(
    items: List<String>,
    selectedItem: String,
    onItemSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val buttonModifier = Modifier.defaultMinSize(minWidth = 150.dp) // Сделаем кнопку чуть шире

    Box {
        Button(
            onClick = { expanded = true },
            modifier = buttonModifier
        ) {
            Text(selectedItem)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 150.dp) // Чтобы меню было не уже кнопки
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}


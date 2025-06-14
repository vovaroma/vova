package com.example.fitness_tracker

import android.content.res.AssetManager
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ActivityClassifier(assetManager: AssetManager, modelPath: String) {

    private var interpreter: Interpreter
    private var inputBuffer: ByteBuffer

    companion object {
        const val MODEL_INPUT_WINDOW_SIZE = 50 // Соответствует window_size=50 в Python
        const val MODEL_INPUT_CHANNELS = 3    // accX, accY, accZ

        // !!! ОБНОВИТЕ ЭТИ МЕТКИ В СООТВЕТСТВИИ С НОВЫМ PYTHON СКРИПТОМ !!!
        // 0 -> still, 1 -> walk, 2 -> jump
        val CLASS_LABELS: Array<String> = arrayOf(
            "still", // Метка 0
            "walk",  // Метка 1
            "jump"   // Метка 2
        )

        val NUM_CLASSES: Int = CLASS_LABELS.size // Автоматически будет 3

        // Стандартизация НЕ используется, как и в Python скрипте
    }

    init {
        val modelOptions = Interpreter.Options()
        // modelOptions.setNumThreads(4)
        try {
            interpreter = Interpreter(loadModelFile(assetManager, modelPath), modelOptions)
            inputBuffer = ByteBuffer.allocateDirect(1 * MODEL_INPUT_WINDOW_SIZE * MODEL_INPUT_CHANNELS * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            Log.d("ActivityClassifier", "TFLite Interpreter initialized successfully for '$modelPath'.")
            Log.d("ActivityClassifier", "Model expects input shape: [1, $MODEL_INPUT_WINDOW_SIZE, $MODEL_INPUT_CHANNELS]")
            Log.d("ActivityClassifier", "Number of output classes: $NUM_CLASSES")
            if (NUM_CLASSES != CLASS_LABELS.size) { // Проверка на всякий случай
                Log.w("ActivityClassifier", "Warning: NUM_CLASSES (${NUM_CLASSES}) does not match CLASS_LABELS size (${CLASS_LABELS.size}).")
            }
        } catch (e: Exception) {
            Log.e("ActivityClassifier", "Error initializing TensorFlow Lite Interpreter: ${e.message}", e)
            throw RuntimeException("Error initializing TensorFlow Lite Interpreter", e)
        }
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): ByteBuffer {
        val assetFileDescriptor = assetManager.openFd(modelPath)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun classifyWindow(sensorDataWindow: List<FloatArray>): String? {
        if (sensorDataWindow.size != MODEL_INPUT_WINDOW_SIZE) {
            Log.e("ActivityClassifier", "Input window size is ${sensorDataWindow.size}, but model expects $MODEL_INPUT_WINDOW_SIZE.")
            return "Ошибка: неверный размер окна (${sensorDataWindow.size})"
        }

        inputBuffer.rewind()

        for (frame in sensorDataWindow) {
            if (frame.size == MODEL_INPUT_CHANNELS) {
                // Данные подаются "сырыми", без стандартизации, как в Python скрипте
                inputBuffer.putFloat(frame[0]) // accX
                inputBuffer.putFloat(frame[1]) // accY
                inputBuffer.putFloat(frame[2]) // accZ
            } else {
                Log.e("ActivityClassifier", "Invalid frame size: ${frame.size}, expected $MODEL_INPUT_CHANNELS")
                return "Ошибка: неверный размер кадра (${frame.size})"
            }
        }
        inputBuffer.rewind()

        val outputProbabilities = Array(1) { FloatArray(NUM_CLASSES) }

        try {
            interpreter.run(inputBuffer, outputProbabilities)
        } catch (e: Exception) {
            Log.e("ActivityClassifier", "Error running model inference: ${e.message}", e)
            return "Ошибка выполнения модели"
        }

        var maxProbability = -1f
        var predictedClassIndex = -1
        for (i in outputProbabilities[0].indices) {
            if (outputProbabilities[0][i] > maxProbability) {
                maxProbability = outputProbabilities[0][i]
                predictedClassIndex = i
            }
        }

        return if (predictedClassIndex != -1 && predictedClassIndex < CLASS_LABELS.size) {
            val predictedLabel = CLASS_LABELS[predictedClassIndex]
            val confidence = (maxProbability * 100).toInt()
            Log.d("ActivityClassifier", "Prediction: $predictedLabel ($confidence%), Index: $predictedClassIndex, Prob: ${String.format("%.4f",maxProbability)}")
            "$predictedLabel ($confidence%)"
        } else {
            Log.e("ActivityClassifier", "Invalid predicted class index: $predictedClassIndex. MaxProb: $maxProbability. Output: ${outputProbabilities[0].joinToString()}")
            "Неизвестно ($predictedClassIndex)"
        }
    }

    fun close() {
        interpreter.close()
        Log.d("ActivityClassifier", "TFLite Interpreter closed.")
    }
}

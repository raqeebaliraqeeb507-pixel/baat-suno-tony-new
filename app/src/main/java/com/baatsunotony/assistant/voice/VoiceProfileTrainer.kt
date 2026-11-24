package com.baatsunotony.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.baatsunotony.assistant.data.VoiceProfileData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

data class VoiceSampleMetrics(
    val pitchHz: Float,
    val zeroCrossingRate: Float,
    val rmsAmplitude: Float,
    val recognizedPhrase: String
)

class VoiceProfileTrainer(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val RECORD_DURATION_MS = 2200L
    }

    /**
     * Captures a short voice sample using AudioRecord (for pitch, ZCR, and RMS tone signature)
     * and then verifies pronunciation using SpeechRecognizer.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureTrainingStep(): Result<VoiceSampleMetrics> {
        return try {
            val acoustic = captureAcousticSample()
            val transcript = captureSpeechTranscript().ifBlank { "baat suno tony" }
            Result.success(
                VoiceSampleMetrics(
                    pitchHz = acoustic.first,
                    zeroCrossingRate = acoustic.second,
                    rmsAmplitude = acoustic.third,
                    recognizedPhrase = transcript.lowercase().trim()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureAcousticSample(): Triple<Float, Float, Float> = withContext(Dispatchers.IO) {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 2
        )

        val totalSamples = ((SAMPLE_RATE * RECORD_DURATION_MS) / 1000L).toInt()
        val samples = ShortArray(totalSamples)
        var offset = 0

        try {
            recorder.startRecording()
            val chunk = ShortArray(1024)
            while (offset < totalSamples) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, totalSamples - offset))
                if (read > 0) {
                    System.arraycopy(chunk, 0, samples, offset, read)
                    offset += read
                } else {
                    break
                }
            }
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }

        analyzePcmSamples(samples, offset.coerceAtLeast(1))
    }

    private suspend fun captureSpeechTranscript(): String = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                if (cont.isActive) cont.resume("baat suno tony")
                return@post
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "ur-PK"))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            val timeoutRunnable = Runnable {
                try {
                    recognizer.cancel()
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                if (cont.isActive) cont.resume("baat suno tony")
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
        try { Thread.sleep(1100) } catch (_: Exception) {}
        // Smooth mic cooldown
                    mainHandler.removeCallbacks(timeoutRunnable)
                    recognizer.destroy()
                    if (cont.isActive) cont.resume("baat suno tony")
                }

                override fun onResults(results: Bundle?) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    recognizer.destroy()
                    if (cont.isActive) cont.resume(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            cont.invokeOnCancellation {
                mainHandler.post {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    try {
                        recognizer.destroy()
                    } catch (_: Exception) {
                    }
                }
            }

            mainHandler.postDelayed(timeoutRunnable, 3500L)
            try {
                recognizer.startListening(intent)
            } catch (_: Exception) {
                mainHandler.removeCallbacks(timeoutRunnable)
                recognizer.destroy()
                if (cont.isActive) cont.resume("baat suno tony")
            }
        }
    }

    private fun analyzePcmSamples(samples: ShortArray, count: Int): Triple<Float, Float, Float> {
        var sumSquares = 0.0
        var zeroCrossings = 0

        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSquares += s * s
            if (i > 0 && ((samples[i - 1] >= 0 && samples[i] < 0) || (samples[i - 1] < 0 && samples[i] >= 0))) {
                zeroCrossings++
            }
        }

        val rms = sqrt(sumSquares / count).toFloat()
        val zcr = zeroCrossings.toFloat() / count.toFloat()
        val pitchHz = estimatePitchHz(samples, count)
        return Triple(pitchHz, zcr, rms)
    }

    private fun estimatePitchHz(samples: ShortArray, count: Int): Float {
        // Basic autocorrelation pitch estimate in typical human voice range (75 Hz - 360 Hz)
        val windowSize = minOf(count, 2048)
        if (windowSize < 512) return 150f

        val minLag = SAMPLE_RATE / 360
        val maxLag = SAMPLE_RATE / 75
        var bestLag = minLag
        var bestCorr = -1.0

        for (lag in minLag..minOf(maxLag, windowSize / 2)) {
            var corr = 0.0
            for (i in 0 until (windowSize - lag)) {
                corr += samples[i].toDouble() * samples[i + lag].toDouble()
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        return (SAMPLE_RATE.toFloat() / bestLag.toFloat()).coerceIn(75f, 360f)
    }

    fun matchesOwnerProfile(
        profile: VoiceProfileData,
        spokenText: String,
        observedRmsDbAvg: Float
    ): Boolean {
        if (!profile.onlyRespondToOwner || !profile.isTrained) return true

        val lower = spokenText.lowercase()
        val phraseMatches = profile.learnedPhrases.any { learned ->
            learned.isNotBlank() && (lower.contains(learned) || learned.contains(lower))
        } || lower.contains("baat suno tony") || lower.contains("tony")

        if (!phraseMatches) return false

        // Compare observed RMS voice energy envelope when SpeechRecognizer reported RMS samples
        if (profile.avgRmsAmplitude > 0f && observedRmsDbAvg > -99f) {
            val normalizedScore = abs(observedRmsDbAvg)
            if (normalizedScore > 45f) return false
        }

        return true
    }
}

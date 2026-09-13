package r3.graffiti

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test

class GenerateSoundsTest {

	private val sampleRate = 44100f

	private fun saveWav(file: File, samples: FloatArray) {
		val pcm = ByteArray(samples.size * 2)
		for (i in samples.indices) {
			val clamped = samples[i].coerceIn(-1.0f, 1.0f)
			val intVal = (clamped * 32767.0f).toInt()
			pcm[i * 2] = (intVal and 0xFF).toByte()
			pcm[i * 2 + 1] = ((intVal shr 8) and 0xFF).toByte()
		}
		val format = AudioFormat(sampleRate, 16, 1, true, false)
		val bais = ByteArrayInputStream(pcm)
		val ais = AudioInputStream(bais, format, samples.size.toLong())
		file.parentFile?.mkdirs()
		AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file)
	}

	@Test
	fun generateAllSounds() {
		val outputDir = File("src/main/resources/web/sounds")
		outputDir.mkdirs()

		// 1. Chime: dual-tone marimba chime (C6 -> G6)
		val chimeLen = (sampleRate * 0.75).toInt()
		val chimeSamples = FloatArray(chimeLen)
		for (i in 0 until chimeLen) {
			val t = i / sampleRate
			var s = 0.0
			// Tone 1: C6 (1046.5 Hz)
			if (t >= 0.0) {
				val dt = t
				val env = exp(-7.0 * dt)
				s += env * (0.6 * sin(2 * PI * 1046.5 * dt) + 0.2 * sin(2 * PI * 2093.0 * dt) + 0.08 * sin(2 * PI * 3139.5 * dt))
			}
			// Tone 2: G6 (1567.98 Hz)
			if (t >= 0.12) {
				val dt = t - 0.12
				val env = exp(-7.0 * dt)
				s += env * (0.65 * sin(2 * PI * 1567.98 * dt) + 0.22 * sin(2 * PI * 3135.96 * dt) + 0.08 * sin(2 * PI * 4703.94 * dt))
			}
			chimeSamples[i] = s.toFloat()
		}
		saveWav(File(outputDir, "chime.wav"), chimeSamples)

		// 2. Knock: subtle double wooden tap
		val knockLen = (sampleRate * 0.45).toInt()
		val knockSamples = FloatArray(knockLen)
		for (i in 0 until knockLen) {
			val t = i / sampleRate
			var s = 0.0
			// Tap 1
			if (t in 0.0..0.15) {
				val dt = t
				val env = exp(-32.0 * dt)
				s += env * (0.8 * sin(2 * PI * 240.0 * dt) + 0.3 * sin(2 * PI * 480.0 * dt) + 0.15 * sin(2 * PI * 110.0 * dt))
			}
			// Tap 2
			if (t >= 0.11 && t <= 0.35) {
				val dt = t - 0.11
				val env = exp(-32.0 * dt)
				s += env * (0.9 * sin(2 * PI * 270.0 * dt) + 0.35 * sin(2 * PI * 540.0 * dt) + 0.18 * sin(2 * PI * 125.0 * dt))
			}
			knockSamples[i] = s.toFloat()
		}
		saveWav(File(outputDir, "knock.wav"), knockSamples)

		// 3. Ping: pure crystal glass bell (A6 1760 Hz)
		val pingLen = (sampleRate * 0.85).toInt()
		val pingSamples = FloatArray(pingLen)
		for (i in 0 until pingLen) {
			val t = i / sampleRate
			val env = exp(-4.5 * t)
			val s = env * (0.75 * sin(2 * PI * 1760.0 * t) + 0.22 * sin(2 * PI * 3520.0 * t) + 0.07 * sin(2 * PI * 5280.0 * t))
			pingSamples[i] = s.toFloat()
		}
		saveWav(File(outputDir, "ping.wav"), pingSamples)

		// 4. Harp: ascending soft triad (E5 -> A5 -> E6)
		val harpLen = (sampleRate * 0.95).toInt()
		val harpSamples = FloatArray(harpLen)
		for (i in 0 until harpLen) {
			val t = i / sampleRate
			var s = 0.0
			// Note 1: E5 (659.25 Hz)
			if (t >= 0.0) {
				val dt = t
				val env = exp(-5.0 * dt)
				s += env * (0.5 * sin(2 * PI * 659.25 * dt) + 0.15 * sin(2 * PI * 1318.5 * dt))
			}
			// Note 2: A5 (880.00 Hz)
			if (t >= 0.11) {
				val dt = t - 0.11
				val env = exp(-5.0 * dt)
				s += env * (0.55 * sin(2 * PI * 880.0 * dt) + 0.18 * sin(2 * PI * 1760.0 * dt))
			}
			// Note 3: E6 (1318.51 Hz)
			if (t >= 0.22) {
				val dt = t - 0.22
				val env = exp(-4.5 * dt)
				s += env * (0.65 * sin(2 * PI * 1318.51 * dt) + 0.2 * sin(2 * PI * 2637.02 * dt))
			}
			harpSamples[i] = s.toFloat()
		}
		saveWav(File(outputDir, "harp.wav"), harpSamples)
	}
}

package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SoundDebouncerTest {

	@Test
	fun testSingleMessageTriggersSoundImmediately() {
		var currentTime = 10_000L
		val debouncer = SoundDebouncer(debounceMs = 1000L, timeProvider = { currentTime })

		var triggered = 0
		debouncer.tryTrigger { triggered++ }

		assertEquals(1, triggered)
	}

	@Test
	fun testBurstOfMessagesTriggersOnlyOnce() {
		var currentTime = 10_000L
		val debouncer = SoundDebouncer(debounceMs = 1000L, timeProvider = { currentTime })

		var triggered = 0
		// First message
		debouncer.tryTrigger { triggered++ }
		assertEquals(1, triggered)

		// 50 rapid messages arriving every 20ms (total 1000ms span)
		for (i in 1..50) {
			currentTime += 20L
			debouncer.tryTrigger { triggered++ }
		}

		// Still only 1 trigger because each message resets/extends the quiet window
		assertEquals(1, triggered)

		// Wait for debounce quiet window to pass (1000ms after last message)
		currentTime += 1000L
		debouncer.tryTrigger { triggered++ }

		assertEquals(2, triggered)
	}

	@Test
	fun testContinuousStreamSuppressesSoundUntilQuietPeriod() {
		var currentTime = 10_000L
		val debouncer = SoundDebouncer(debounceMs = 1000L, timeProvider = { currentTime })

		var triggered = 0
		// Messages arriving every 500ms for 5 seconds (10 messages)
		for (i in 1..10) {
			debouncer.tryTrigger { triggered++ }
			currentTime += 500L
		}

		// Only the very first message should have triggered
		assertEquals(1, triggered)

		// After 1000ms of quiet, next message should trigger
		currentTime += 1000L
		debouncer.tryTrigger { triggered++ }
		assertEquals(2, triggered)
	}

	@Test
	fun testMessagesSpacedFurtherThanDebounceTriggerEachTime() {
		var currentTime = 10_000L
		val debouncer = SoundDebouncer(debounceMs = 1000L, timeProvider = { currentTime })

		var triggered = 0
		debouncer.tryTrigger { triggered++ }
		assertEquals(1, triggered)

		currentTime += 1500L
		debouncer.tryTrigger { triggered++ }
		assertEquals(2, triggered)

		currentTime += 2000L
		debouncer.tryTrigger { triggered++ }
		assertEquals(3, triggered)
	}

	@Test
	fun testConcurrentTriggersInBurst() {
		val debouncer = SoundDebouncer(debounceMs = 1000L)
		val threads = 10
		val iterationsPerThread = 50
		val executor = Executors.newFixedThreadPool(threads)
		val startLatch = CountDownLatch(1)
		val doneLatch = CountDownLatch(threads)
		val triggerCount = AtomicInteger(0)

		for (i in 0 until threads) {
			executor.submit {
				startLatch.await()
				for (j in 0 until iterationsPerThread) {
					debouncer.tryTrigger {
						triggerCount.incrementAndGet()
					}
				}
				doneLatch.countDown()
			}
		}

		startLatch.countDown()
		assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
		executor.shutdown()

		// In a tight concurrent burst of 500 triggers without delay, only 1 trigger should occur
		assertEquals(1, triggerCount.get())
	}

	@Test
	fun testClockJumpBackwardsResetsDebounce() {
		var currentTime = 100_000L
		val debouncer = SoundDebouncer(debounceMs = 1000L, timeProvider = { currentTime })

		var triggered = 0
		debouncer.tryTrigger { triggered++ }
		assertEquals(1, triggered)

		// Clock moves backward by 10 seconds
		currentTime = 90_000L
		debouncer.tryTrigger { triggered++ }
		assertEquals(2, triggered)
	}
}

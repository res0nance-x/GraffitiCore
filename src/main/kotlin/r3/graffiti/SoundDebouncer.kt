package r3.graffiti

import java.util.concurrent.atomic.AtomicLong

class SoundDebouncer(
	val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
	private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
	companion object {
		const val DEFAULT_DEBOUNCE_MS = 1000L
	}

	private val lastMessageTime = AtomicLong(0L)

	/**
	 * Returns true if the sound should be triggered for this message event,
	 * while extending the debounce quiet window on every event.
	 */
	fun shouldTrigger(): Boolean {
		val now = timeProvider()
		while (true) {
			val prev = lastMessageTime.get()
			val elapsed = now - prev
			val trigger = prev == 0L || elapsed >= debounceMs || elapsed < -5000L
			val next = if (trigger) now else maxOf(prev, now)
			if (lastMessageTime.compareAndSet(prev, next)) {
				return trigger
			}
		}
	}

	/**
	 * Executes [action] only if the debounce window allows triggering.
	 */
	inline fun tryTrigger(action: () -> Unit): Boolean {
		return if (shouldTrigger()) {
			action()
			true
		} else {
			false
		}
	}

	fun reset() {
		lastMessageTime.set(0L)
	}
}

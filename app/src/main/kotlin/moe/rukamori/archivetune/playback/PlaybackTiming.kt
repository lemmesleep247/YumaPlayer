package moe.rukamori.archivetune.playback

import android.os.SystemClock
import moe.rukamori.archivetune.BuildConfig
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

internal object PlaybackTiming {
    const val TAG = "PlaybackTiming"
    private const val MAX_TRACKED_ATTEMPTS = 64

    private val attemptCounter = AtomicLong(0L)
    private val prefetchAttemptCounter = AtomicLong(0L)

    private val userAttempts = ConcurrentHashMap<String, Long>()
    private val prefetchAttempts = ConcurrentHashMap<String, Long>()
    private val userAttemptKeys = ConcurrentLinkedQueue<String>()

    @Volatile var processStartNanos: Long = 0L
    @Volatile var lastClickAttemptId: Long = 0L
    @Volatile var lastClickNanos: Long = 0L
    @Volatile var lastClickMediaId: String? = null

    fun nextAttemptId(): Long = attemptCounter.incrementAndGet()

    fun nextPrefetchAttemptId(): Long = prefetchAttemptCounter.incrementAndGet()

    fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

    fun markProcessStart(): Long {
        val t = nowNanos()
        processStartNanos = t
        return t
    }

    private fun recordUserAttempt(mediaId: String, id: Long) {
        userAttempts[mediaId] = id
        userAttemptKeys.offer(mediaId)
        while (userAttemptKeys.size > MAX_TRACKED_ATTEMPTS) {
            val oldest = userAttemptKeys.poll()
            if (oldest != null && oldest != lastClickMediaId && !userAttemptKeys.contains(oldest)) {
                userAttempts.remove(oldest)
            }
        }
    }

    fun markClick(mediaId: String?): Long {
        val id = nextAttemptId()
        lastClickAttemptId = id
        lastClickNanos = nowNanos()
        lastClickMediaId = mediaId
        if (mediaId != null) {
            recordUserAttempt(mediaId, id)
        }
        return id
    }

    fun markPrefetchStart(mediaId: String): Long {
        val id = nextPrefetchAttemptId()
        if (prefetchAttempts.size > MAX_TRACKED_ATTEMPTS) {
            prefetchAttempts.clear()
        }
        prefetchAttempts[mediaId] = id
        return id
    }

    fun markPrefetchEnd(mediaId: String) {
        prefetchAttempts.remove(mediaId)
    }

    fun isPrefetch(mediaId: String?): Boolean =
        mediaId != null && !userAttempts.containsKey(mediaId) && prefetchAttempts.containsKey(mediaId)

    fun getAttemptId(mediaId: String?, isPrefetch: Boolean = false): Long {
        if (mediaId == null) return lastClickAttemptId.takeIf { it > 0L } ?: nextAttemptId()
        if (isPrefetch) {
            prefetchAttempts[mediaId]?.let { return it }
            return nextPrefetchAttemptId()
        }
        userAttempts[mediaId]?.let { return it }
        prefetchAttempts[mediaId]?.let { return it }
        if (lastClickMediaId == mediaId && lastClickAttemptId > 0L) return lastClickAttemptId
        val id = nextAttemptId()
        recordUserAttempt(mediaId, id)
        return id
    }

    fun log(
        event: String,
        attemptId: Long = 0L,
        mediaId: String? = null,
        startNanos: Long = 0L,
        extra: String? = null,
        isPrefetch: Boolean = false,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = nowNanos()
        val durPart = if (startNanos > 0L) {
            val durNs = now - startNanos
            " durNs=$durNs durMs=${durNs / 1_000_000}"
        } else {
            ""
        }
        val prefetch = isPrefetch || (mediaId != null && isPrefetch(mediaId))
        val attemptPart = if (attemptId > 0L) " attemptId=$attemptId" else ""
        val prefetchPart = if (prefetch) " isPrefetch=true" else ""
        val mediaPart = if (!mediaId.isNullOrBlank()) " mediaId=$mediaId" else ""
        val extraPart = if (!extra.isNullOrBlank()) " $extra" else ""
        Timber.tag(TAG).d("$event$attemptPart$prefetchPart$mediaPart tNs=$now$durPart$extraPart")
    }
}

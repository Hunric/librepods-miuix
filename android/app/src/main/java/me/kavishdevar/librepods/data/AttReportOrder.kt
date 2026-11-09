package me.kavishdevar.librepods.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Number real ATT values on receipt; the owner serializes edit/write fence operations. */
internal class AttReportOrder<K : Any> {
    class Report internal constructor(val sequence: Long, value: ByteArray) {
        private val bytes = value.copyOf()
        val value: ByteArray get() = bytes.copyOf()
    }

    private data class WriteFence(val revision: Long, val requestSequence: Long, val received: Long)

    private val received = AtomicLong()
    private val latest = ConcurrentHashMap<K, Report>()
    private val edits = mutableMapOf<K, Long>()
    private val writes = mutableMapOf<K, WriteFence>()

    fun record(key: K, value: ByteArray): Report {
        val report = Report(received.incrementAndGet(), value)
        latest.compute(key) { _, old -> if (old == null || old.sequence < report.sequence) report else old }
        return report
    }

    fun latest(key: K): Report? = latest[key]

    fun markEdit(key: K) {
        edits[key] = received.get()
        writes.remove(key)
    }

    /** Called inside the output lock immediately before attempting the setting write. */
    fun markWrite(key: K, revision: Long, requestSequence: Long) {
        writes[key] = WriteFence(revision, requestSequence, received.get())
    }

    fun isFresh(key: K, report: Report): Boolean =
        latest[key] === report && report.sequence > maxOf(edits[key] ?: 0L, writes[key]?.received ?: 0L)

    fun isFreshForWrite(key: K, report: Report, revision: Long, requestSequence: Long): Boolean {
        val fence = writes[key] ?: return false
        return fence.revision == revision && fence.requestSequence == requestSequence && isFresh(key, report)
    }
}

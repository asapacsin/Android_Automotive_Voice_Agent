package com.novadrive.ingress.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class WorkStatus {
    QUEUED,
    RUNNING,
    PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class WorkSnapshot(
    val id: String,
    val payload: String,
    val status: WorkStatus,
    val progress: String? = null,
    val result: String? = null,
    val error: String? = null,
    val revision: Int = 1,
    val delivered: Boolean = false,
)

class WorkHandle internal constructor(
    val id: String,
    private val coordinator: WorkCoordinator,
) {
    fun progress(message: String): WorkSnapshot? = coordinator.updateProgress(id, message)
}

/**
 * Asynchronous work that must not block audio. Speech interruption never cancels work.
 * Completed results are delivered once, only at a safe conversation point.
 */
class WorkCoordinator(
    var onTerminal: () -> Unit = {},
) {
    private val jobs = linkedMapOf<String, WorkSnapshot>()
    private val executions = mutableMapOf<String, Job>()
    private val inFlight = mutableSetOf<String>()
    private val lock = Any()

    fun submit(id: String, payload: String): WorkSnapshot =
        synchronized(lock) {
            val snap = WorkSnapshot(id = id, payload = payload, status = WorkStatus.RUNNING)
            jobs[id] = snap
            snap
        }

    fun submit(
        scope: CoroutineScope,
        id: String,
        payload: String,
        block: suspend WorkHandle.() -> String,
    ): WorkSnapshot {
        val snap = submit(id, payload)
        val handle = WorkHandle(id, this)
        val job =
            scope.launch {
                try {
                    val result = handle.block()
                    complete(id, result)
                } catch (ex: CancellationException) {
                    cancel(id)
                    throw ex
                } catch (ex: Exception) {
                    fail(id, ex.message ?: "work failed")
                }
            }
        synchronized(lock) { executions[id] = job }
        return snap
    }

    fun refine(id: String, payload: String): WorkSnapshot =
        synchronized(lock) {
            val current = jobs[id] ?: return submit(id, payload)
            val snap =
                current.copy(
                    payload = payload,
                    revision = current.revision + 1,
                    status = WorkStatus.RUNNING,
                    progress = "refined",
                    delivered = false,
                )
            jobs[id] = snap
            inFlight.remove(id)
            snap
        }

    fun updateProgress(id: String, message: String): WorkSnapshot? =
        synchronized(lock) {
            val current = jobs[id] ?: return null
            val snap = current.copy(status = WorkStatus.PROGRESS, progress = message)
            jobs[id] = snap
            snap
        }

    fun complete(id: String, result: String): WorkSnapshot? {
        val snap =
            synchronized(lock) {
                val current = jobs[id] ?: return null
                if (current.status == WorkStatus.CANCELLED) return current
                val updated = current.copy(status = WorkStatus.COMPLETED, result = result)
                jobs[id] = updated
                executions.remove(id)
                updated
            }
        onTerminal()
        return snap
    }

    fun fail(id: String, error: String): WorkSnapshot? {
        val snap =
            synchronized(lock) {
                val current = jobs[id] ?: return null
                if (current.status == WorkStatus.CANCELLED) return current
                val updated = current.copy(status = WorkStatus.FAILED, error = error)
                jobs[id] = updated
                executions.remove(id)
                updated
            }
        onTerminal()
        return snap
    }

    fun cancel(id: String): WorkSnapshot? {
        val job =
            synchronized(lock) {
                executions.remove(id)
            }
        job?.cancel()
        return synchronized(lock) {
            val current = jobs[id] ?: return null
            val snap = current.copy(status = WorkStatus.CANCELLED)
            jobs[id] = snap
            inFlight.remove(id)
            snap
        }
    }

    fun cancelAll(): List<WorkSnapshot> {
        val runningJobs =
            synchronized(lock) {
                val copied = executions.values.toList()
                executions.clear()
                copied
            }
        runningJobs.forEach { it.cancel() }
        return synchronized(lock) {
            val updated = ArrayList<WorkSnapshot>(jobs.size)
            for ((id, current) in jobs) {
                val snap =
                    when (current.status) {
                        WorkStatus.COMPLETED, WorkStatus.FAILED, WorkStatus.CANCELLED -> current
                        else -> {
                            inFlight.remove(id)
                            current.copy(status = WorkStatus.CANCELLED)
                        }
                    }
                jobs[id] = snap
                updated += snap
            }
            updated
        }
    }

    fun snapshot(id: String): WorkSnapshot? = synchronized(lock) { jobs[id] }

    fun all(): List<WorkSnapshot> = synchronized(lock) { jobs.values.toList() }

    fun claimForDelivery(safeConversationPoint: Boolean): WorkSnapshot? =
        synchronized(lock) {
            if (!safeConversationPoint) return null
            val ready =
                jobs.values.firstOrNull {
                    !it.delivered &&
                        it.id !in inFlight &&
                        it.status in setOf(WorkStatus.COMPLETED, WorkStatus.FAILED)
                } ?: return null
            inFlight += ready.id
            ready
        }

    fun acknowledgeDelivery(id: String): WorkSnapshot? =
        synchronized(lock) {
            val current = jobs[id] ?: return null
            val marked = current.copy(delivered = true)
            jobs[id] = marked
            inFlight.remove(id)
            marked
        }

    fun releaseDeliveryClaim(id: String) {
        synchronized(lock) { inFlight.remove(id) }
    }
}

package app.dereel.core

import app.dereel.core.grant.GrantStore
import app.dereel.core.grant.TemporaryGrant
import app.dereel.core.nativeapp.Bounds
import app.dereel.core.nativeapp.UiNode
import app.dereel.core.time.DeviceClock
import app.dereel.core.usage.SegmentState
import app.dereel.core.usage.UsageSegment
import app.dereel.core.usage.UsageStore
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A clock driven by an elapsed-time source (usually the coroutine test scheduler). Tests can
 * shift the wall clock or zone independently to simulate user clock changes.
 */
class TestClock(
    private val elapsed: () -> Long,
    var wallStartMs: Long = wallAt(2026, 11, 19, 10, 0),
    var zoneId: ZoneId = ZoneOffset.UTC,
    var boot: String = "boot-1",
) : DeviceClock {
    var monotonicBaseMs = 5_000_000L
    var wallShiftMs = 0L

    override fun monotonicMs(): Long = monotonicBaseMs + elapsed()
    override fun wallMs(): Long = wallStartMs + elapsed() + wallShiftMs
    override fun zone(): ZoneId = zoneId
    override fun bootId(): String = boot

    companion object {
        fun wallAt(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, zone: ZoneId = ZoneOffset.UTC): Long =
            LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
    }
}

/** Manual elapsed-time source for tests without coroutines. */
class ManualTime(var nowMs: Long = 0) {
    fun advance(ms: Long) {
        nowMs += ms
    }
}

class InMemoryUsageStore : UsageStore {
    val rows = LinkedHashMap<String, UsageSegment>()

    override suspend fun upsert(segment: UsageSegment) {
        rows[segment.id] = segment
    }

    override suspend fun openSegments(): List<UsageSegment> = rows.values.filter { it.state == SegmentState.OPEN }
    override suspend fun segmentsFromDay(fromDay: Long): List<UsageSegment> = rows.values.filter { it.day >= fromDay }

    override suspend fun deleteBeforeDay(day: Long): Int {
        val doomed = rows.values.filter { it.day < day }.map { it.id }
        doomed.forEach { rows.remove(it) }
        return doomed.size
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

class InMemoryGrantStore : GrantStore {
    val rows = LinkedHashMap<String, TemporaryGrant>()
    override suspend fun loadActive(): List<TemporaryGrant> = rows.values.filter { it.state == app.dereel.core.grant.GrantState.ACTIVE }
    override suspend fun save(grant: TemporaryGrant) {
        rows[grant.id] = grant
    }
    override suspend fun deleteAll() {
        rows.clear()
    }
}

/** Builder for synthetic accessibility trees used as detector fixtures. */
class FakeNode(
    override val viewId: String? = null,
    override val bounds: Bounds = Bounds(0, 0, 1080, 2400),
    override val isVisibleToUser: Boolean = true,
    override val isSelected: Boolean = false,
    val children: List<FakeNode> = emptyList(),
) : UiNode {
    override val childCount: Int get() = children.size
    override fun child(index: Int): UiNode? = children.getOrNull(index)
}

/** Wraps a fixture tree so it supports in-process ID lookup, like a device node. */
class SearchableNode(private val node: FakeNode) : UiNode by node {
    override fun findByViewId(fullId: String): List<UiNode> {
        val found = ArrayList<UiNode>()
        fun visit(n: FakeNode) {
            if (n.viewId == fullId) found += n
            n.children.forEach(::visit)
        }
        visit(node)
        return found
    }
}

val PHONE_WINDOW = Bounds(0, 0, 1080, 2400)

class SequentialIds(private val prefix: String = "id") : () -> String {
    private var next = 0
    override fun invoke(): String = "$prefix-${next++}"
}

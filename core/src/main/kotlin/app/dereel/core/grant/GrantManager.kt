package app.dereel.core.grant

import app.dereel.core.model.Platform
import app.dereel.core.time.DeviceClock
import app.dereel.core.util.SerialWriter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface GrantStore {
    suspend fun loadActive(): List<TemporaryGrant>
    suspend fun save(grant: TemporaryGrant)
    suspend fun deleteAll()
}

data class GrantIssued(val grant: TemporaryGrant, val replaced: TemporaryGrant?)

/** Issues, replaces, ends and expires temporary grants (FR-29). */
class GrantManager(
    private val store: GrantStore,
    private val clock: DeviceClock,
    private val writer: SerialWriter,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val _active = MutableStateFlow<Map<Platform, TemporaryGrant>>(emptyMap())

    /** Grants in the ACTIVE state. One may have run out without being marked expired yet. */
    val active: StateFlow<Map<Platform, TemporaryGrant>> = _active.asStateFlow()

    suspend fun load() {
        val loaded = store.loadActive()
        synchronized(lock) {
            val now = clock.read()
            val boot = clock.bootId()
            val map = HashMap<Platform, TemporaryGrant>()
            for (grant in loaded.sortedBy { it.issuedWallMs }) {
                if (grant.isActive(now, boot)) {
                    map[grant.platform]?.let { older -> persist(older.copy(state = GrantState.REPLACED)) }
                    map[grant.platform] = grant
                } else {
                    persist(grant.copy(state = GrantState.EXPIRED))
                }
            }
            _active.value = map
        }
    }

    fun activeGrant(platform: Platform): TemporaryGrant? = synchronized(lock) {
        _active.value[platform]?.takeIf { it.isActive(clock.read(), clock.bootId()) }
    }

    fun remainingMs(platform: Platform): Long = synchronized(lock) {
        _active.value[platform]?.remainingMs(clock.read(), clock.bootId()) ?: 0
    }

    /**
     * Issues a new five-minute grant. Any active grant for the platform is replaced. The UI must
     * say so before calling this (FR-29).
     */
    fun request(platform: Platform): GrantIssued = synchronized(lock) {
        val now = clock.read()
        val existing = activeGrant(platform)
        if (existing != null) persist(existing.copy(state = GrantState.REPLACED))
        val grant = TemporaryGrant(
            id = newId(),
            platform = platform,
            issuedWallMs = now.wallMs,
            expiresWallMs = now.wallMs + TemporaryGrant.DURATION_MS,
            issuedMonotonicMs = now.monotonicMs,
            bootId = clock.bootId(),
        )
        persist(grant)
        _active.value = _active.value + (platform to grant)
        GrantIssued(grant, existing)
    }

    /** Ends a grant early. Returns false if there was nothing to end. */
    fun end(platform: Platform): Boolean = synchronized(lock) {
        val grant = _active.value[platform] ?: return false
        persist(grant.copy(state = GrantState.ENDED_EARLY))
        _active.value = _active.value - platform
        true
    }

    /** Marks grants that have run out as expired. Returns the affected platforms. */
    fun expireDue(): Set<Platform> = synchronized(lock) {
        val now = clock.read()
        val boot = clock.bootId()
        val expired = _active.value.values.filterNot { it.isActive(now, boot) }
        if (expired.isEmpty()) return emptySet()
        expired.forEach { persist(it.copy(state = GrantState.EXPIRED)) }
        _active.value = _active.value - expired.map { it.platform }.toSet()
        expired.map { it.platform }.toSet()
    }

    fun clearAll() = synchronized(lock) {
        _active.value = emptyMap()
        writer.submit { store.deleteAll() }
    }

    private fun persist(grant: TemporaryGrant) {
        writer.submit { store.save(grant) }
    }
}

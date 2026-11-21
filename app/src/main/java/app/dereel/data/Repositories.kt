package app.dereel.data

import app.dereel.core.grant.GrantStore
import app.dereel.core.grant.TemporaryGrant
import app.dereel.core.model.AppPreferences
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import app.dereel.core.pacing.PacingState
import app.dereel.core.pacing.PacingStore
import app.dereel.data.db.PacingDao
import app.dereel.core.usage.UsageSegment
import app.dereel.core.usage.UsageStore
import app.dereel.data.db.GrantDao
import app.dereel.data.db.SettingsDao
import app.dereel.data.db.UsageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform settings and app preferences. Saving notifies active journeys (POL-06). */
class SettingsRepository(private val dao: SettingsDao, scope: CoroutineScope) {
    private val writeLock = Mutex()

    /** Empty until the first database read completes. */
    val configs: StateFlow<Map<Platform, PlatformConfig>> = dao.observeConfigs()
        .map { rows ->
            val stored = rows.mapNotNull { it.toModel() }.associateBy { it.platform }
            Platform.entries.associateWith { stored[it] ?: PlatformConfig.defaultFor(it) }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Null until the first database read completes. */
    val preferences: StateFlow<AppPreferences?> = dao.observePreferences()
        .map { it?.toModel() ?: AppPreferences() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun awaitLoaded() {
        configs.first { it.isNotEmpty() }
        preferences.first { it != null }
    }

    suspend fun updatePlatform(platform: Platform, transform: (PlatformConfig) -> PlatformConfig): PlatformConfig =
        writeLock.withLock {
            val current = dao.config(platform.id)?.toModel() ?: PlatformConfig.defaultFor(platform)
            val next = transform(current).copy(platform = platform, revision = current.revision + 1)
            dao.upsertConfig(next.toEntity())
            next
        }

    suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences): AppPreferences =
        writeLock.withLock {
            val current = dao.preferences()?.toModel() ?: AppPreferences()
            val next = transform(current)
            if (next != current) dao.upsertPreferences(next.toEntity())
            next
        }

    suspend fun resetAll() = writeLock.withLock {
        dao.deleteConfigs()
        dao.deletePreferences()
    }
}

class RoomUsageStore(private val dao: UsageDao) : UsageStore {
    override suspend fun upsert(segment: UsageSegment) = dao.upsert(segment.toEntity())
    override suspend fun openSegments(): List<UsageSegment> = dao.openSegments().mapNotNull { it.toModel() }
    override suspend fun segmentsFromDay(fromDay: Long): List<UsageSegment> = dao.fromDay(fromDay).mapNotNull { it.toModel() }
    override suspend fun deleteBeforeDay(day: Long): Int = dao.deleteBeforeDay(day)
    override suspend fun deleteAll() = dao.deleteAll()
}

class RoomGrantStore(private val dao: GrantDao) : GrantStore {
    override suspend fun loadActive(): List<TemporaryGrant> = dao.active().mapNotNull { it.toModel() }
    override suspend fun save(grant: TemporaryGrant) = dao.upsert(grant.toEntity())
    override suspend fun deleteAll() = dao.deleteAll()
}

class RoomPacingStore(private val dao: PacingDao) : PacingStore {
    override suspend fun load(): List<PacingState> = dao.all().mapNotNull { it.toModel() }
    override suspend fun save(state: PacingState) = dao.upsert(state.toEntity())
    override suspend fun deleteAll() = dao.deleteAll()
}

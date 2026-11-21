package app.dereel.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM platform_config")
    fun observeConfigs(): Flow<List<PlatformConfigEntity>>

    @Query("SELECT * FROM platform_config WHERE platformId = :platformId")
    suspend fun config(platformId: String): PlatformConfigEntity?

    @Upsert
    suspend fun upsertConfig(entity: PlatformConfigEntity)

    @Query("SELECT * FROM app_preferences WHERE id = 1")
    fun observePreferences(): Flow<AppPreferencesEntity?>

    @Query("SELECT * FROM app_preferences WHERE id = 1")
    suspend fun preferences(): AppPreferencesEntity?

    @Upsert
    suspend fun upsertPreferences(entity: AppPreferencesEntity)

    @Query("DELETE FROM platform_config")
    suspend fun deleteConfigs()

    @Query("DELETE FROM app_preferences")
    suspend fun deletePreferences()
}

@Dao
interface UsageDao {
    @Upsert
    suspend fun upsert(entity: UsageSegmentEntity)

    @Query("SELECT * FROM usage_segment WHERE state = 'OPEN'")
    suspend fun openSegments(): List<UsageSegmentEntity>

    @Query("SELECT * FROM usage_segment WHERE day >= :fromDay")
    suspend fun fromDay(fromDay: Long): List<UsageSegmentEntity>

    @Query("DELETE FROM usage_segment WHERE day < :day")
    suspend fun deleteBeforeDay(day: Long): Int

    @Query("DELETE FROM usage_segment")
    suspend fun deleteAll()
}

@Dao
interface GrantDao {
    @Query("SELECT * FROM temporary_grant WHERE state = 'ACTIVE'")
    suspend fun active(): List<TemporaryGrantEntity>

    @Upsert
    suspend fun upsert(entity: TemporaryGrantEntity)

    @Query("DELETE FROM temporary_grant")
    suspend fun deleteAll()
}

@Dao
interface PacingDao {
    @Query("SELECT * FROM pacing_state")
    suspend fun all(): List<PacingStateEntity>

    @Upsert
    suspend fun upsert(entity: PacingStateEntity)

    @Query("DELETE FROM pacing_state")
    suspend fun deleteAll()
}

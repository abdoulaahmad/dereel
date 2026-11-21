package app.dereel.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Platform configuration record (Section 7.1). Interval columns were added in schema 2 (S-02). */
@Entity(tableName = "platform_config")
data class PlatformConfigEntity(
    @PrimaryKey val platformId: String,
    val selected: Boolean,
    val openingMethod: String?,
    val blockShortVideos: Boolean,
    val allowSharedVideos: Boolean,
    val allowanceEnabled: Boolean,
    val allowanceMinutes: Int?,
    val browserControls: String,
    val revision: Long,
    @ColumnInfo(defaultValue = "0") val intervalEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "10") val intervalWatchMinutes: Int = 10,
    @ColumnInfo(defaultValue = "30") val intervalBreakMinutes: Int = 30,
)

/** App-wide preferences. Sleep, alert and timer columns were added in schema 2 (S-02). */
@Entity(tableName = "app_preferences")
data class AppPreferencesEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val onboardingComplete: Boolean,
    val temporaryAccessEnabled: Boolean,
    val nativeEverConnected: Boolean,
    @ColumnInfo(defaultValue = "0") val sleepEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "1320") val sleepStartMinute: Int = 1320,
    @ColumnInfo(defaultValue = "420") val sleepEndMinute: Int = 420,
    @ColumnInfo(defaultValue = "0") val protectionAlerts: Boolean = false,
    @ColumnInfo(defaultValue = "1") val liveTimer: Boolean = true,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/**
 * Viewing session segment (Section 7.1, FR-32). It contains no URLs, page text, message content
 * or account identifiers.
 */
@Entity(tableName = "usage_segment", indices = [Index("day"), Index("state")])
data class UsageSegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val platformId: String,
    val method: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val elapsedMs: Long,
    val day: Long,
    val outcome: String,
    val state: String,
)

/** Temporary grant record (Section 7.1). */
@Entity(tableName = "temporary_grant", indices = [Index("state")])
data class TemporaryGrantEntity(
    @PrimaryKey val id: String,
    val platformId: String,
    val issuedWallMs: Long,
    val expiresWallMs: Long,
    val issuedMonotonicMs: Long,
    val bootId: String,
    val state: String,
)

/** Interval-timer progress for one platform (S-02): durations and times only. Added in schema 2. */
@Entity(tableName = "pacing_state")
data class PacingStateEntity(
    @PrimaryKey val platformId: String,
    val usedMs: Long,
    val breakUntilWallMs: Long,
    val lastViewedWallMs: Long,
)

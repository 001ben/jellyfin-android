package org.jellyfin.mobile.downloads.smart

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.sdk.model.api.BaseItemDto

@Entity(tableName = "account")
data class SmartOfflineAccount(
    @PrimaryKey val key: String,
    val serverId: Long,
    val userId: Long,
    val enabled: Boolean = false,
    val nextCount: Int = 3,
    val capGiB: Int = 10,
    val wifiOnly: Boolean = true,
    val cleanup: Boolean = false,
    val remoteFallback: Boolean = false,
    val lanUrl: String = "",
    val serverIdentity: String = "",
    val status: String = "",
)

@Entity(tableName = "series")
data class SmartOfflineSeries(@PrimaryKey val key: String, val account: String, val seriesId: String, val name: String)

@Entity(tableName = "managed")
data class SmartOfflineManaged(
    @PrimaryKey val key: String,
    val account: String,
    val item: BaseItemDto,
    val downloadId: Long? = null,
    val etag: String? = null,
    val bytes: Long = 0,
)

/** Durable outbox: successful synchronization deletes only the exact revision that was sent. */
@Entity(tableName = "progress")
data class OfflineProgress(
    @PrimaryKey val downloadId: Long,
    val serverId: Long,
    val userId: Long,
    val itemId: java.util.UUID,
    val position: Long,
    val finished: Boolean,
    val revision: String,
    val generation: String,
    val sequence: Long,
    val stopReported: Boolean = false,
)

@Dao
interface SmartOfflineDao {
    @Query("SELECT * FROM account")
    suspend fun accounts(): List<SmartOfflineAccount>

    @Query("SELECT * FROM account WHERE `key` = :key")
    suspend fun account(key: String): SmartOfflineAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(account: SmartOfflineAccount)

    @Query("UPDATE account SET status = :status WHERE `key` = :key")
    suspend fun status(key: String, status: String)

    @Query("UPDATE account SET serverIdentity = :identity WHERE `key` = :key AND serverIdentity = ''")
    suspend fun pinIdentity(key: String, identity: String)

    @Query("SELECT * FROM series WHERE account = :account ORDER BY name")
    suspend fun series(
        account: String
    ): List<SmartOfflineSeries>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun subscribe(series: SmartOfflineSeries)

    @Query("DELETE FROM series WHERE `key` = :key")
    suspend fun unsubscribe(key: String)

    @Query("SELECT * FROM managed WHERE account = :account")
    suspend fun managed(
        account: String
    ): List<SmartOfflineManaged>

    @Query("SELECT * FROM managed WHERE downloadId = :id")
    suspend fun owned(id: Long): SmartOfflineManaged?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(managed: SmartOfflineManaged)

    @Query("DELETE FROM managed WHERE `key` = :key")
    suspend fun forget(key: String)

    @Query("SELECT * FROM progress")
    suspend fun pending(): List<OfflineProgress>

    @Query("SELECT * FROM progress WHERE downloadId = :id")
    suspend fun progress(id: Long): OfflineProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: OfflineProgress)

    @Query("DELETE FROM progress WHERE downloadId = :id AND revision = :revision")
    suspend fun acknowledged(
        id: Long,
        revision: String
    )

    @Query("UPDATE progress SET stopReported = 1 WHERE downloadId = :id AND revision = :revision")
    suspend fun stopped(id: Long, revision: String)
}

@Database(
    entities = [SmartOfflineAccount::class, SmartOfflineSeries::class, SmartOfflineManaged::class, OfflineProgress::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(JellyfinDatabase.Converters::class)
abstract class SmartOfflineDatabase : RoomDatabase() {
    abstract val smartOfflineDao: SmartOfflineDao
}

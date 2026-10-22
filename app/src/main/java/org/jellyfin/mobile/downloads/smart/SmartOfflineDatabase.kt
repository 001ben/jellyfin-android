package org.jellyfin.mobile.downloads.smart

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
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
    @ColumnInfo(defaultValue = "''") val trustedHttpLanUrl: String = "",
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

// Room generates one cohesive database contract; splitting these thin queries adds no independent policy boundary.
@Dao
@Suppress("TooManyFunctions")
interface SmartOfflineDao {
    @Query("SELECT * FROM account")
    suspend fun accounts(): List<SmartOfflineAccount>

    @Query("SELECT * FROM account WHERE `key` = :key")
    suspend fun account(key: String): SmartOfflineAccount?

    @Query("SELECT * FROM account WHERE `key` = :key")
    fun observeAccount(key: String): Flow<SmartOfflineAccount?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(account: SmartOfflineAccount)

    @Transaction
    suspend fun saveSettings(settings: SmartOfflineAccount): SmartOfflineAccount {
        val previous = account(settings.key)
        val saved = settings.copy(
            serverIdentity = previous?.serverIdentity.orEmpty(),
            status = previous?.status.orEmpty(),
        )
        save(saved)
        return saved
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccountIfMissing(account: SmartOfflineAccount): Long

    @Query("UPDATE account SET status = :status WHERE `key` = :key")
    suspend fun status(key: String, status: String)

    @Query("UPDATE account SET serverIdentity = :identity WHERE `key` = :key AND serverIdentity = ''")
    suspend fun pinIdentity(key: String, identity: String)

    @Query("SELECT * FROM series WHERE account = :account ORDER BY name")
    suspend fun series(
        account: String
    ): List<SmartOfflineSeries>

    @Query("SELECT * FROM series WHERE account = :account ORDER BY name")
    fun observeSeries(account: String): Flow<List<SmartOfflineSeries>>

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
    entities = [
        SmartOfflineAccount::class,
        SmartOfflineSeries::class,
        SmartOfflineManaged::class,
        OfflineProgress::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(JellyfinDatabase.Converters::class)
abstract class SmartOfflineDatabase : RoomDatabase() {
    abstract val smartOfflineDao: SmartOfflineDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing accounts have no HTTP trust; consent is never inferred during an upgrade.
                db.execSQL("ALTER TABLE account ADD COLUMN trustedHttpLanUrl TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

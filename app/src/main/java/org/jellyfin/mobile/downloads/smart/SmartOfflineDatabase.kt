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

private const val CATALOG_DATABASE_VERSION = 3

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
data class SmartOfflineSeries(
    @PrimaryKey val key: String,
    val account: String,
    val seriesId: String,
    val name: String,
    val item: BaseItemDto? = null,
)

@Entity(tableName = "managed")
data class SmartOfflineManaged(
    @PrimaryKey val key: String,
    val account: String,
    val item: BaseItemDto,
    val downloadId: Long? = null,
    val etag: String? = null,
    val bytes: Long = 0,
    @ColumnInfo(defaultValue = "'QUEUED'") val phase: String = "QUEUED",
    @ColumnInfo(defaultValue = "''") val reason: String = "",
    val expectedBytes: Long? = null,
    @ColumnInfo(defaultValue = "0") val serverPlayed: Boolean = false,
)

/** Successful local removal history has no file ownership or playback authority. */
@Entity(tableName = "removal")
data class SmartOfflineRemoval(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val account: String,
    val item: BaseItemDto,
    val removedAt: Long,
    val automatic: Boolean,
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

    @Query("SELECT EXISTS(SELECT 1 FROM series WHERE account = :account AND seriesId = :series)")
    suspend fun isSubscribed(account: String, series: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun subscribe(series: SmartOfflineSeries)

    @Query("DELETE FROM series WHERE `key` = :key")
    suspend fun unsubscribe(key: String)

    @Query("SELECT * FROM managed WHERE account = :account")
    suspend fun managed(
        account: String
    ): List<SmartOfflineManaged>

    @Query("SELECT * FROM managed WHERE account = :account")
    fun observeManaged(account: String): Flow<List<SmartOfflineManaged>>

    @Query("SELECT * FROM managed WHERE downloadId = :id")
    suspend fun owned(id: Long): SmartOfflineManaged?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(managed: SmartOfflineManaged)

    @Query("UPDATE managed SET phase = :phase, reason = :reason WHERE `key` = :key")
    suspend fun lifecycle(key: String, phase: String, reason: String = "")

    @Query("UPDATE managed SET etag = :etag WHERE `key` = :key")
    suspend fun validator(key: String, etag: String?)

    @Query(
        "UPDATE managed SET downloadId = :id, bytes = :bytes, expectedBytes = :bytes, " +
            "etag = NULL, phase = 'COMPLETE', reason = '' WHERE `key` = :key"
    )
    suspend fun published(key: String, id: Long, bytes: Long)

    @Query("UPDATE managed SET serverPlayed = :played WHERE `key` = :key")
    suspend fun confirmedWatched(key: String, played: Boolean)

    @Query("UPDATE managed SET serverPlayed = 0 WHERE account = :account")
    suspend fun invalidateWatchConfirmation(account: String)

    @Query("DELETE FROM managed WHERE `key` = :key")
    suspend fun forget(key: String)

    @Query("SELECT * FROM removal WHERE account = :account ORDER BY removedAt DESC, id DESC")
    fun observeRemovals(account: String): Flow<List<SmartOfflineRemoval>>

    @Insert
    suspend fun insertRemoval(removal: SmartOfflineRemoval)

    @Query(
        "DELETE FROM removal WHERE account = :account AND id NOT IN " +
            "(SELECT id FROM removal WHERE account = :account ORDER BY removedAt DESC, id DESC LIMIT 100)"
    )
    suspend fun trimRemovals(account: String)

    @Transaction
    suspend fun removed(entry: SmartOfflineManaged, automatic: Boolean, at: Long) {
        insertRemoval(
            SmartOfflineRemoval(account = entry.account, item = entry.item, removedAt = at, automatic = automatic)
        )
        trimRemovals(entry.account)
        forget(entry.key)
    }

    @Query("SELECT * FROM progress")
    suspend fun pending(): List<OfflineProgress>

    @Query("SELECT * FROM progress WHERE serverId = :server AND userId = :user")
    fun observePending(server: Long, user: Long): Flow<List<OfflineProgress>>

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
        SmartOfflineRemoval::class,
    ],
    version = CATALOG_DATABASE_VERSION,
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

        val MIGRATION_2_3 = object : Migration(2, CATALOG_DATABASE_VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series ADD COLUMN item TEXT")
                db.execSQL("ALTER TABLE managed ADD COLUMN phase TEXT NOT NULL DEFAULT 'QUEUED'")
                db.execSQL("ALTER TABLE managed ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE managed ADD COLUMN expectedBytes INTEGER")
                db.execSQL("ALTER TABLE managed ADD COLUMN serverPlayed INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS removal (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "account TEXT NOT NULL, item TEXT NOT NULL, " +
                        "removedAt INTEGER NOT NULL, automatic INTEGER NOT NULL)"
                )
            }
        }
    }
}

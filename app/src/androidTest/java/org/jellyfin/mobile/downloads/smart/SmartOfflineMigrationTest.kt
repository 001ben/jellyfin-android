package org.jellyfin.mobile.downloads.smart

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SmartOfflineMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, SmartOfflineDatabase::class.java)

    private val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "Migration episode")
    private val itemJson = requireNotNull(JellyfinDatabase.Converters().fromBaseItemDto(item))
    private val lan = URI(
        "http",
        null,
        listOf(192, 168, 1, 2).joinToString("."),
        8096,
        "/jellyfin",
        null,
        null,
    ).toASCIIString()

    @Test
    fun upgradePreservesSettingsOwnershipAndPendingPlaybackWithoutGrantingHttpConsent() = runBlocking<Unit> {
        instrumentation.targetContext.deleteDatabase(DATABASE_NAME)
        helper.createDatabase(DATABASE_NAME, OLD_VERSION).use(::seedVersionOne)
        helper.runMigrationsAndValidate(DATABASE_NAME, NEW_VERSION, true, SmartOfflineDatabase.MIGRATION_1_2).close()
        val database = Room.databaseBuilder(
            instrumentation.targetContext,
            SmartOfflineDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(SmartOfflineDatabase.MIGRATION_1_2).build()
        try {
            val dao = database.smartOfflineDao
            val first = requireNotNull(dao.account("1-2"))
            assertEquals(lan, first.lanUrl)
            assertEquals("server-A", first.serverIdentity)
            assertEquals("pending", first.status)
            assertEquals("", first.trustedHttpLanUrl)
            assertTrue(first.enabled)
            assertTrue(first.cleanup)
            assertEquals(5, first.nextCount)
            assertEquals(12, first.capGiB)
            assertEquals("Migration series", dao.series("1-2").single().name)
            val managed = dao.managed("1-2").single()
            assertEquals(item.id, managed.item.id)
            assertEquals(DOWNLOAD_ID, managed.downloadId)
            assertEquals("\"migration-etag\"", managed.etag)
            assertEquals(PARTIAL_BYTES, managed.bytes)
            val progress = requireNotNull(dao.progress(DOWNLOAD_ID))
            assertEquals(item.id, progress.itemId)
            assertEquals(POSITION_TICKS, progress.position)
            assertTrue(progress.finished)
            assertFalse(progress.stopReported)
            assertEquals("pending-revision", progress.revision)
            assertEquals("generation", progress.generation)
            assertEquals(8L, progress.sequence)
            assertEquals("", requireNotNull(dao.account("3-4")).trustedHttpLanUrl)
            assertFalse(SmartOfflinePolicy.isTrustedHttpLan(first))
            dao.saveSettings(first.copy(trustedHttpLanUrl = SmartOfflinePolicy.httpConsentUrl(lan, true)))
            assertTrue(SmartOfflinePolicy.isTrustedHttpLan(requireNotNull(dao.account("1-2"))))
            assertFalse(SmartOfflinePolicy.isTrustedHttpLan(requireNotNull(dao.account("3-4"))))
        } finally {
            database.close()
        }
    }

    private fun seedVersionOne(database: SupportSQLiteDatabase) {
        insertAccount(database, "1-2", 1, 2)
        insertAccount(database, "3-4", 3, 4)
        database.execSQL(
            "INSERT INTO series (`key`, account, seriesId, name) VALUES (?, ?, ?, ?)",
            arrayOf<Any>("series-key", "1-2", item.id.toString(), "Migration series"),
        )
        database.execSQL(
            "INSERT INTO managed (`key`, account, item, downloadId, etag, bytes) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>("1-2-${item.id}", "1-2", itemJson, DOWNLOAD_ID, "\"migration-etag\"", PARTIAL_BYTES),
        )
        database.execSQL(
            "INSERT INTO progress (downloadId, serverId, userId, itemId, position, finished, revision, " +
                "generation, sequence, stopReported) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                DOWNLOAD_ID,
                1,
                2,
                item.id.toString(),
                POSITION_TICKS,
                1,
                "pending-revision",
                "generation",
                8,
                0,
            ),
        )
    }

    private fun insertAccount(database: SupportSQLiteDatabase, key: String, server: Long, user: Long) {
        database.execSQL(
            "INSERT INTO account (`key`, serverId, userId, enabled, nextCount, capGiB, wifiOnly, cleanup, " +
                "remoteFallback, lanUrl, serverIdentity, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(key, server, user, 1, 5, 12, 1, 1, 0, lan, "server-A", "pending"),
        )
    }

    companion object {
        private const val DATABASE_NAME = "smart-offline-migration-test"
        private const val OLD_VERSION = 1
        private const val NEW_VERSION = 2
        private const val DOWNLOAD_ID = 17L
        private const val PARTIAL_BYTES = 123L
        private const val POSITION_TICKS = 34000L
    }
}

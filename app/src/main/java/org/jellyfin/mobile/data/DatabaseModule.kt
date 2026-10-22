package org.jellyfin.mobile.data

import androidx.room.Room
import org.jellyfin.mobile.downloads.smart.SmartOfflineDatabase
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(androidApplication(), JellyfinDatabase::class.java, "jellyfin")
            .fallbackToDestructiveMigrationFrom(true, 1)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }
    single { get<JellyfinDatabase>().serverDao }
    single { get<JellyfinDatabase>().userDao }
    single { get<JellyfinDatabase>().downloadDao }
    single {
        Room.databaseBuilder(androidApplication(), SmartOfflineDatabase::class.java, "smart-offline")
            .addMigrations(SmartOfflineDatabase.MIGRATION_1_2, SmartOfflineDatabase.MIGRATION_2_3)
            .build()
    }
    single { get<SmartOfflineDatabase>().smartOfflineDao }
}

package com.maxrave.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.maxrave.common.DB_NAME
import com.maxrave.data.io.getHomeFolderPath
import java.io.File

actual fun getDatabaseBuilder(
    converters: Converters
): RoomDatabase.Builder<MusicDatabase> {
    return Room.databaseBuilder<MusicDatabase>(
        name = getDatabasePath()
    ).addTypeConverter(converters)
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    super.onOpen(connection)
                    runCatching {
                        connection.execSQL("PRAGMA journal_mode = WAL")
                        connection.execSQL("PRAGMA synchronous = NORMAL")
                        connection.execSQL("PRAGMA temp_store = MEMORY")
                        connection.execSQL("PRAGMA cache_size = -16000")
                    }
                }
            },
        )
}

actual fun getDatabasePath(): String {
    val dbFile = File(getHomeFolderPath(listOf(".simpmusic", "db")), DB_NAME)
    return dbFile.absolutePath
}
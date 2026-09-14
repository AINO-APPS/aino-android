package app.aino.mobile.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AinoDatabase : RoomDatabase() {
    abstract fun dao(): AinoDao

    companion object {
        @Volatile private var instance: AinoDatabase? = null

        fun get(context: Context): AinoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AinoDatabase::class.java,
                "aino.db",
            ).build().also { instance = it }
        }
    }
}
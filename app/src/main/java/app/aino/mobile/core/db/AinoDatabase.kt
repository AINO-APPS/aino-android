package app.aino.mobile.core.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = AinoDatabase.DropMessageDeliveryState::class),
    ],
)
abstract class AinoDatabase : RoomDatabase() {
    abstract fun dao(): AinoDao

    /** `deliveryState` was never read: the tick is derived from `delivered_to`. */
    @DeleteColumn(tableName = "messages", columnName = "deliveryState")
    class DropMessageDeliveryState : AutoMigrationSpec

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
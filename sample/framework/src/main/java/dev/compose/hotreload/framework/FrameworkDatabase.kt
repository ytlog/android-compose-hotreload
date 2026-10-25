package dev.compose.hotreload.framework
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drafts")
data class Draft(@PrimaryKey val id: Int, val text: String)

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(draft: Draft)
    @Query("SELECT text FROM drafts WHERE id = :id")
    suspend fun read(id: Int): String?
    @Query("SELECT text FROM drafts WHERE id = :id")
    fun observe(id: Int): Flow<String?>
}

@Database(entities = [Draft::class], version = 1, exportSchema = false)
abstract class FrameworkDatabase : RoomDatabase() {
    abstract fun drafts(): DraftDao
}

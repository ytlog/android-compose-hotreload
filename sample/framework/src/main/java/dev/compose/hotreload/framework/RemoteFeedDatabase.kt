package dev.compose.hotreload.framework

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "feed_rows")
data class RemoteFeedRow(@PrimaryKey val id: Int, val label: String)

@Dao
interface RemoteFeedDao {
    @Query("SELECT * FROM feed_rows ORDER BY id")
    fun pages(): PagingSource<Int, RemoteFeedRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun store(rows: List<RemoteFeedRow>)
    @Query("DELETE FROM feed_rows")
    suspend fun clear()
    @Query("SELECT MAX(id) FROM feed_rows")
    suspend fun lastId(): Int?
    @Query("SELECT COUNT(*) FROM feed_rows")
    fun count(): Flow<Int>
    @Query("SELECT label FROM feed_rows ORDER BY id DESC LIMIT 1")
    fun tail(): Flow<String?>
}

@Database(entities = [RemoteFeedRow::class], version = 1, exportSchema = false)
abstract class RemoteFeedDatabase : RoomDatabase() {
    abstract fun feed(): RemoteFeedDao
}

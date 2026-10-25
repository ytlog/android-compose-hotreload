package dev.compose.hotreload.framework
import android.content.Context
import androidx.room.Room
import dagger.Binds
import javax.inject.Inject
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

interface Formatter { fun format(text: String): String }
class OriginalFormatter @Inject constructor() : Formatter { override fun format(text: String) = "binding-v1:$text" }
class AlternateFormatter @Inject constructor() : Formatter { override fun format(text: String) = "binding-v2:$text" }

@Module
@InstallIn(SingletonComponent::class)
object FrameworkBindings {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): FrameworkDatabase =
        Room.inMemoryDatabaseBuilder(context, FrameworkDatabase::class.java).build()
    @Provides @Singleton
    fun dao(database: FrameworkDatabase): DraftDao = database.drafts()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FormatterBindings {
    @Binds
    abstract fun formatter(implementation: OriginalFormatter): Formatter
}

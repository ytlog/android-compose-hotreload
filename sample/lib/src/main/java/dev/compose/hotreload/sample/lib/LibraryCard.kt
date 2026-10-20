package dev.compose.hotreload.sample.lib

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Lives in a library module: changes are hot reloaded as well (upstream 'hot' classpath) */
fun libraryText(): String = "Library module"

@Composable
fun LibraryCard() {
    Text(libraryText())
}

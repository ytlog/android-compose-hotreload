package dev.compose.hotreload.framework

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.navigation
import kotlinx.coroutines.launch

@Composable
fun StackScreen() {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val owner = remember { token() }
    Column {
        Text("Stack owner: $owner:${System.identityHashCode(nav)}", fontSize = 10.sp)
        Text("Stack route: ${current?.destination?.route}", fontSize = 10.sp)
        Row {
            listOf("feed", "saved").forEach { tab ->
                Button(onClick = {
                    nav.navigate(tab) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) { Text("Tab $tab") }
            }
            Button(onClick = { nav.popBackStack() }) { Text("Stack pop") }
        }
        NavHost(nav, startDestination = "feed", modifier = Modifier.weight(1f),
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
            listOf("feed", "saved").forEach { tab ->
                navigation(startDestination = "$tab/home", route = tab) {
                    composable("$tab/home") { entry ->
                        StackPage("$tab/home", entry.id) { nav.navigate("$tab/detail") }
                    }
                    composable("$tab/detail") { entry ->
                        StackPage("$tab/detail", entry.id) { nav.popBackStack() }
                    }
                }
            }
        }
    }
}

@Composable
private fun StackPage(route: String, entry: String, next: () -> Unit) {
    // Navigation disposes inactive destinations: saveable state is the real API contract.
    // A separate ordinary remember token detects replacement of the active composition on reload.
    val live = remember { token() }
    val saved = rememberSaveable { token() }
    var draft by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val callback = remember { { count += 1 } }
    Column {
        Text("Stack page: $entry:$saved", fontSize = 10.sp)
        Text("Stack live: $live:${System.identityHashCode(callback)}", fontSize = 10.sp)
        Text("Stack state: $draft:$count:${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}", fontSize = 10.sp)
        Text(stackLabel(route))
        TextField(draft, { draft = it }, label = { Text("Stack draft") })
        Row {
            Button(onClick = { draft = "$route-draft"; scope.launch { list.scrollToItem(12, 7) } }) { Text("Seed stack") }
            Button(onClick = callback) { Text("Stack count") }
            Button(onClick = next) { Text("Stack next") }
        }
        LazyColumn(state = list, modifier = Modifier.height(150.dp)) {
            items((0..49).toList(), key = { it }) { item ->
                Text("Stack row $item", Modifier.fillMaxWidth().height(36.dp))
            }
        }
    }
}

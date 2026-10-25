package dev.compose.hotreload.sample

import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun repositoryContainer() = koinApplication {
    modules(module {
        single<EditRepository> { EditRepositoryImpl("di-v1") }
        factory { RepositoryClient(get()) }
    })
}

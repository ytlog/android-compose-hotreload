/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

internal enum class Os {
    Windows, MacOs, Linux;

    companion object {
        @JvmStatic
        fun currentOrNull(): Os? {
            val os = System.getProperty("os.name")
            return when {
                os.startsWith("Mac", ignoreCase = true) -> MacOs
                os.startsWith("Win", ignoreCase = true) -> Windows
                os.startsWith("Linux", ignoreCase = true) -> Linux
                else -> null
            }
        }

        @JvmStatic
        fun current(): Os = currentOrNull()
            ?: error("Could not determine current OS: ${System.getProperty("os.name")}")
    }
}

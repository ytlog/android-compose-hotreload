/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

fun command(vararg args: String) : String {
    val process = ProcessBuilder(*args).inheritIO()
        .redirectErrorStream(true)
        .start()

    val input = process.inputStream.reader().readText()
    if (process.waitFor() != 0) error("${args.joinToString(" ")} failed\n" + input)
    return input
}

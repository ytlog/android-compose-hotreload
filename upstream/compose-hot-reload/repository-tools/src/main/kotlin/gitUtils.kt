/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */


fun ensureCleanWorkingDirectory() {
    val gitStatus = ProcessBuilder("git", "status", "--porcelain")
        .redirectErrorStream(true)
        .start()
        .inputStream
        .bufferedReader()
        .readText()

    if (gitStatus.isNotBlank()) {
        error(
            "Your git working directory is not clean. " +
                "Please commit or stash changes before running the build.\n$gitStatus"
        )
    }

}

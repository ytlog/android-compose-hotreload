/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.gradle.idea

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.pathString

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

private val prettyJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = true
}

@Serializable(with = IdeaComposeHotReloadModelSerializer::class)
public interface IdeaComposeHotReloadModel : java.io.Serializable {
    public val version: String?
    public val runTasks: List<IdeaComposeHotRunTask>
}

public fun IdeaComposeHotReloadModel(
    version: String? = null,
    runTasks: List<IdeaComposeHotRunTask> = emptyList(),
): IdeaComposeHotReloadModel = IdeaComposeHotReloadModelImpl(
    version = version,
    runTasks = runTasks,
)

public fun IdeaComposeHotReloadModel.copy(): IdeaComposeHotReloadModel = IdeaComposeHotReloadModelImpl(
    version = version,
    runTasks = runTasks.map { it.copy() },
)

@Serializable
internal data class IdeaComposeHotReloadModelImpl(
    override val version: String? = null,
    override val runTasks: List<IdeaComposeHotRunTask> = emptyList(),
) : IdeaComposeHotReloadModel {
    internal companion object {
        const val serialVersionUID: Long = 0L
    }

    internal class Surrogate(val binary: ByteArray) : java.io.Serializable {
        companion object {
            const val serialVersionUID: Long = 0L
        }

        @Suppress("unused")
        private fun readResolve(): Any {
            val string = binary.decodeToString()
            return json.decodeFromString<IdeaComposeHotReloadModelImpl>(string)
        }
    }

    @Suppress("unused")
    private fun writeReplace(): Any {
        return Surrogate(json.encodeToString(this).encodeToByteArray())
    }

    override fun toString(): String {
        return prettyJson.encodeToString(this)
    }
}

@Serializable(with = IdeaComposeHotRunTaskSerializer::class)
public interface IdeaComposeHotRunTask : java.io.Serializable {
    public val taskName: String?
    public val taskClass: String?
    public val targetName: String?
    public val compilationName: String?
    public val sourceSets: List<String>
    public val argFile: Path?
    public val argFileTaskName: String?
}

public fun IdeaComposeHotRunTask(
    taskName: String? = null,
    taskClass: String? = null,
    targetName: String? = null,
    compilationName: String? = null,
    sourceSets: List<String> = emptyList(),
    argFile: Path? = null,
    argFileTaskName: String? = null,
): IdeaComposeHotRunTask = IdeaComposeHotRunTaskImpl(
    taskName = taskName,
    taskClass = taskClass,
    targetName = targetName,
    compilationName = compilationName,
    sourceSets = sourceSets,
    argFile = argFile,
    argFileTaskName = argFileTaskName,
)

public fun IdeaComposeHotRunTask.copy(): IdeaComposeHotRunTask = IdeaComposeHotRunTaskImpl(
    taskName = taskName,
    taskClass = taskClass,
    targetName = targetName,
    compilationName = compilationName,
    sourceSets = sourceSets.toList(),
    argFile = argFile,
    argFileTaskName = argFileTaskName,
)

@Serializable
internal data class IdeaComposeHotRunTaskImpl(
    override val taskName: String? = null,
    override val taskClass: String? = null,
    override val targetName: String? = null,
    override val compilationName: String? = null,
    override val sourceSets: List<String> = emptyList(),
    @Serializable(with = PathSerializer::class)
    override val argFile: Path? = null,
    override val argFileTaskName: String? = null,
) : IdeaComposeHotRunTask

private class PathSerializer : KSerializer<Path> {
    override val descriptor = PrimitiveSerialDescriptor("Path", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Path) =
        encoder.encodeString(value.pathString)

    override fun deserialize(decoder: Decoder): Path =
        Path(decoder.decodeString())
}

private class IdeaComposeHotReloadModelSerializer : KSerializer<IdeaComposeHotReloadModel> {
    override val descriptor = IdeaComposeHotReloadModelImpl.serializer().descriptor
    override fun serialize(encoder: Encoder, value: IdeaComposeHotReloadModel) {
        value as? IdeaComposeHotReloadModelImpl ?: error("Can't serialize ${value::class}")
        IdeaComposeHotReloadModelImpl.serializer().serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): IdeaComposeHotReloadModel {
        return IdeaComposeHotReloadModelImpl.serializer().deserialize(decoder)
    }
}

private class IdeaComposeHotRunTaskSerializer : KSerializer<IdeaComposeHotRunTask> {
    override val descriptor = IdeaComposeHotReloadModelImpl.serializer().descriptor
    override fun serialize(encoder: Encoder, value: IdeaComposeHotRunTask) {
        value as? IdeaComposeHotRunTaskImpl ?: error("Can't serialize ${value::class}")
        IdeaComposeHotRunTaskImpl.serializer().serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): IdeaComposeHotRunTask {
        return IdeaComposeHotRunTaskImpl.serializer().deserialize(decoder)
    }
}

/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.compose.devtools.widgets

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.devtools.theme.DtTextStyles


@Composable
fun DtHeader1(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier,
        style = DtTextStyles.header1
    )
}

@Composable
fun DtHeader2(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier,
        style = DtTextStyles.header2
    )
}

@Composable
fun DtText(
    text: String, modifier: Modifier = Modifier,
    style: TextStyle = DtTextStyles.default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    color: ColorProducer? = null
) {
    BasicText(
        text, modifier,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        style = style,
        color = color,
    )
}

@Composable
fun DtSmallText(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier,
        style = DtTextStyles.small
    )
}

@Composable
fun DtCode(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier,
        style = DtTextStyles.code
    )
}

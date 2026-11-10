package dev.vivian.mobilebc.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal fun chatHeaderText(label: String?, identity: String?, time: String, identityColor: Color?) =
    buildAnnotatedString {
        if (label != null) {
            append(label)
            val start = identity?.takeIf(String::isNotEmpty)?.let(label::lastIndexOf) ?: -1
            if (start >= 0 && identityColor != null) {
                addStyle(SpanStyle(color = identityColor), start, start + checkNotNull(identity).length)
            }
            append(" · ")
        }
        append(time)
    }

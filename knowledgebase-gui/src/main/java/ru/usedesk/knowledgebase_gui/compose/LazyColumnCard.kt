package ru.usedesk.knowledgebase_gui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import ru.usedesk.knowledgebase_gui.R

@Composable
internal fun LazyColumnCard(content: LazyListScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.card(),
            content = content
        )
    }
}

@Composable
internal fun Modifier.card() = fillMaxWidth()
    .padding(
        start = 16.dp,
        end = 16.dp,
        bottom = 16.dp,
    )
    .clip(RoundedCornerShape(10.dp))
    .background(color = colorResource(R.color.usedesk_white_1))
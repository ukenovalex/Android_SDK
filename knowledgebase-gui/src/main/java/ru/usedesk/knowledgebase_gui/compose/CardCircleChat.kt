package ru.usedesk.knowledgebase_gui.compose

import androidx.compose.animation.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ru.usedesk.knowledgebase_gui.screen.UsedeskKnowledgeBaseTheme

@Composable
internal fun BoxScope.CardCircleChat(
    theme: UsedeskKnowledgeBaseTheme,
    visible: Boolean,
    onClicked: () -> Unit
) {
    AnimatedVisibility(
        modifier = Modifier.align(Alignment.BottomEnd),
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it }
    ) {
        Surface(
            shape = CircleShape,
            modifier = Modifier
                .padding(20.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape
                )
                .clickableItem(onClick = onClicked),
            color = theme.colors.black2
        ) {
            Icon(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
                    .size(24.dp),
                painter = painterResource(theme.drawables.iconIdSupport),
                contentDescription = null,
                tint = Color.Unspecified
            )
        }
    }
}
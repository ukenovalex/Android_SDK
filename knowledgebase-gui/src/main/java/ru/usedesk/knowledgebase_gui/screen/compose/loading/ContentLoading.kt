package ru.usedesk.knowledgebase_gui.screen.compose.loading

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ru.usedesk.knowledgebase_gui._entity.ContentState
import ru.usedesk.knowledgebase_gui.compose.*
import ru.usedesk.knowledgebase_gui.screen.UsedeskKnowledgeBaseTheme

internal const val LOADING_KEY = "article"

@Composable
internal fun ContentLoading(
    theme: UsedeskKnowledgeBaseTheme,
    viewModelStoreFactory: ViewModelStoreFactory,
    tryAgain: () -> Unit
) {
    val viewModel = kbUiViewModel(
        viewModelStoreOwner = remember { { viewModelStoreFactory.get(LOADING_KEY) } }
    ) { kbUiComponent -> LoadingViewModel(kbUiComponent.interactor) }
    val state by viewModel.modelFlow.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = state.contentState) { contentState ->
            when (contentState) {
                is ContentState.Empty,
                is ContentState.Loaded -> Box(modifier = Modifier.fillMaxSize())
                is ContentState.Error -> ScreenNotLoaded(
                    theme = theme,
                    tryAgain = if (!state.loading) tryAgain else null
                )
            }
        }
        CardCircleProgress(
            theme = theme,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(theme.dimensions.loadingPadding),
            loading = state.loading
        )
    }
}
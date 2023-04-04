package ru.usedesk.knowledgebase_gui.screen.blocks

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import ru.usedesk.knowledgebase_gui.compose.SearchBar
import ru.usedesk.knowledgebase_gui.compose.ViewModelStoreFactory
import ru.usedesk.knowledgebase_gui.screen.RootViewModel
import ru.usedesk.knowledgebase_gui.screen.RootViewModel.Event
import ru.usedesk.knowledgebase_gui.screen.RootViewModel.State
import ru.usedesk.knowledgebase_gui.screen.UsedeskKnowledgeBaseCustomization
import ru.usedesk.knowledgebase_gui.screen.blocks.articles.ContentArticles
import ru.usedesk.knowledgebase_gui.screen.blocks.categories.ContentCategories
import ru.usedesk.knowledgebase_gui.screen.blocks.search.ContentSearch
import ru.usedesk.knowledgebase_gui.screen.blocks.sections.ContentSections

internal const val SECTIONS_KEY = "sections"
internal const val CATEGORIES_KEY = "categories"
internal const val ARTICLES_KEY = "articles"
internal const val SEARCH_KEY = "search"

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ContentBlocks(
    customization: UsedeskKnowledgeBaseCustomization,
    viewModelStoreFactory: ViewModelStoreFactory,
    viewModel: RootViewModel,
    supportButtonVisible: MutableState<Boolean>,
    onEvent: (Event) -> Unit
) {
    val state by viewModel.modelFlow.collectAsState()
    val blocksState = state.blocksState

    val forwardTransitionSpec = remember {
        slideInHorizontally(
            spring(
                stiffness = Spring.StiffnessLow,
                visibilityThreshold = IntOffset.VisibilityThreshold
            )
        ) { it } with slideOutHorizontally(
            spring(
                stiffness = Spring.StiffnessLow,
                visibilityThreshold = IntOffset.VisibilityThreshold
            )
        ) { -it }
    }
    val backwardTransitionSpec = slideInHorizontally(
        spring(
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = IntOffset.VisibilityThreshold
        )
    ) { -it } with slideOutHorizontally(
        spring(
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = IntOffset.VisibilityThreshold
        )
    ) { it }
    val noneTransitionSpec = fadeIn() with fadeOut()

    Column(modifier = Modifier) {
        SearchBar(
            customization = customization,
            value = blocksState.searchText,
            onClearClick = remember { { onEvent(Event.SearchClearClicked) } },
            onCancelClick = when (blocksState.block) {
                is State.BlocksState.Block.Search -> remember { { onEvent(Event.SearchCancelClicked) } }
                else -> null
            },
            onValueChange = remember { { onEvent(Event.SearchTextChanged(it)) } },
            onSearch = remember { { onEvent(Event.SearchClicked) } }
        )
        AnimatedContent(
            targetState = blocksState.block,
            transitionSpec = {
                when (targetState.transition(initialState)) {
                    State.Transition.FORWARD -> forwardTransitionSpec
                    State.Transition.BACKWARD -> backwardTransitionSpec
                    else -> noneTransitionSpec
                }
            }
        ) { block ->
            when (block) {
                State.BlocksState.Block.Sections -> {
                    ContentSections(
                        customization = customization,
                        viewModelStoreOwner = remember {
                            { viewModelStoreFactory.get(SECTIONS_KEY) }
                        },
                        supportButtonVisible = supportButtonVisible,
                        onSectionClicked = remember { { onEvent(Event.SectionClicked(it)) } }
                    )
                }
                is State.BlocksState.Block.Categories -> {
                    DisposableEffect(Unit) {
                        onDispose {
                            when (viewModel.modelFlow.value.blocksState.block) {
                                is State.BlocksState.Block.Articles,
                                is State.BlocksState.Block.Categories,
                                is State.BlocksState.Block.Search -> Unit
                                State.BlocksState.Block.Sections ->
                                    viewModelStoreFactory.clear(CATEGORIES_KEY)
                            }
                        }
                    }
                    ContentCategories(
                        customization = customization,
                        viewModelStoreOwner = remember {
                            { viewModelStoreFactory.get(CATEGORIES_KEY) }
                        },
                        sectionId = block.sectionId,
                        supportButtonVisible = supportButtonVisible,
                        onCategoryClick = remember { { onEvent(Event.CategoryClicked(it)) } }
                    )
                }
                is State.BlocksState.Block.Articles -> {
                    DisposableEffect(Unit) {
                        onDispose {
                            when (viewModel.modelFlow.value.blocksState.block) {
                                is State.BlocksState.Block.Articles,
                                is State.BlocksState.Block.Search -> Unit
                                is State.BlocksState.Block.Categories,
                                State.BlocksState.Block.Sections ->
                                    viewModelStoreFactory.clear(ARTICLES_KEY)
                            }
                        }
                    }
                    ContentArticles(
                        customization = customization,
                        viewModelStoreOwner = remember { { viewModelStoreFactory.get(ARTICLES_KEY) } },
                        categoryId = block.categoryId,
                        supportButtonVisible = supportButtonVisible,
                        onArticleClick = remember {
                            { onEvent(Event.ArticleClicked(it.id, it.title)) }
                        }
                    )
                }
                is State.BlocksState.Block.Search -> {
                    DisposableEffect(Unit) {
                        onDispose {
                            when (viewModel.modelFlow.value.blocksState.block) {
                                is State.BlocksState.Block.Search -> Unit
                                is State.BlocksState.Block.Articles,
                                is State.BlocksState.Block.Categories,
                                State.BlocksState.Block.Sections ->
                                    viewModelStoreFactory.clear(SEARCH_KEY)
                            }
                        }
                    }
                    ContentSearch(
                        customization = customization,
                        viewModelStoreOwner = remember { { viewModelStoreFactory.get(SEARCH_KEY) } },
                        supportButtonVisible = supportButtonVisible,
                        onArticleClick = remember {
                            {
                                onEvent(
                                    Event.ArticleClicked(
                                        it.id,
                                        it.title
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryCategoriesViewModel
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryEvent
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.library.canonicalLibraryDestination
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.presentation.tsuzuki.library.CanonicalLibraryScreen as CanonicalLibraryScreenContent

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = metroViewModel<CanonicalLibraryScreenModel>()
        val categoriesViewModel = metroViewModel<CanonicalLibraryCategoriesViewModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val categories by categoriesViewModel.categories.collectAsStateWithLifecycle()

        LaunchedEffect(screenModel) {
            screenModel.events.collect { event ->
                when (event) {
                    is CanonicalLibraryEvent.OpenReader -> {
                        context.startActivity(
                            ReaderActivity.newCanonicalIntent(
                                context = context,
                                canonicalChapterId = event.canonicalChapterId,
                            ),
                        )
                    }
                    is CanonicalLibraryEvent.OpenCanonicalTitle -> {
                        navigator.push(
                            canonicalLibraryDestination(event.canonicalTitleId),
                        )
                    }
                }
            }
        }

        CanonicalLibraryScreenContent(
            state = state,
            navigateUp = null,
            title = stringResource(MR.strings.label_library),
            onUpdateStatus = screenModel::setStatus,
            onRemoveItem = screenModel::removeItem,
            onSearchQueryChange = screenModel::search,
            categories = categories,
            onSetCategories = categoriesViewModel::setCategories,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onCategoryFilterChange = screenModel::selectCategory,
            onRead = { item ->
                screenModel.readOrContinue(item.canonicalTitleId)
            },
            onOpenItem = { item ->
                navigator.push(
                    canonicalLibraryDestination(item.canonicalTitleId),
                )
            },
        )

        LaunchedEffect(screenModel) {
            launch {
                queryEvent.receiveAsFlow().collect { query ->
                    screenModel.search(query)
                }
            }
        }

        LaunchedEffect(state) {
            if (state is CanonicalLibraryScreenState.Success) {
                (context as? MainActivity)?.ready = true
            }
        }
    }

    private val queryEvent = Channel<String>()

    suspend fun search(query: String) = queryEvent.send(query)
}

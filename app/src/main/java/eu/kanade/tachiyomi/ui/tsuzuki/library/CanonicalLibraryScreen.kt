package eu.kanade.tachiyomi.ui.tsuzuki.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.tsuzuki.source.ReadingSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.tsuzuki.source.SourceResolverScreen
import eu.kanade.presentation.tsuzuki.library.CanonicalLibraryScreen as CanonicalLibraryScreenContent

class CanonicalLibraryScreen : Screen() {

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
                    is CanonicalLibraryEvent.ResolveReadingSource -> {
                        navigator.push(
                            SourceResolverScreen(
                                event.canonicalTitleId,
                                event.title,
                            ),
                        )
                    }
                }
            }
        }

        CanonicalLibraryScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onUpdateStatus = screenModel::setStatus,
            onRemoveItem = screenModel::removeItem,
            onSearchQueryChange = screenModel::search,
            categories = categories,
            onSetCategories = categoriesViewModel::setCategories,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onCategoryFilterChange = screenModel::selectCategory,
            onRead = { item ->
                screenModel.readOrContinue(
                    canonicalTitleId = item.title.id,
                    title = item.title.displayTitle,
                )
            },
            onResolveSource = { item ->
                navigator.push(SourceResolverScreen(item.title.id, item.title.displayTitle))
            },
            onOpenSourcePreferences = { navigator.push(ReadingSourcePreferencesScreen()) },
        )
    }
}

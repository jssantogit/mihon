package eu.kanade.tachiyomi.ui.tsuzuki.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.tsuzuki.source.ReadingSourcePreferencesScreen as ReadingSourcePreferencesScreenContent

class ReadingSourcePreferencesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<ReadingSourcePreferencesScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        ReadingSourcePreferencesScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onSelectLanguage = screenModel::selectLanguage,
            onAddSource = screenModel::addSource,
            onRemoveSource = screenModel::removeSource,
            onMoveUp = screenModel::moveUp,
            onMoveDown = screenModel::moveDown,
            onSave = screenModel::save,
        )
    }
}

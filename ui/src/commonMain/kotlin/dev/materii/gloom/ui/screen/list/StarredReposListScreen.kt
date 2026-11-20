package dev.materii.gloom.ui.screen.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.koinScreenModel
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import dev.materii.gloom.Res
import dev.materii.gloom.ui.component.ThinDivider
import dev.materii.gloom.ui.component.toolbar.LargeToolbar
import dev.materii.gloom.ui.screen.list.viewmodel.StarredReposListViewModel
import dev.materii.gloom.ui.screen.repo.component.RepoItem
import dev.materii.gloom.ui.util.clearFocusOnOutsidePress
import org.koin.core.parameter.parametersOf

class StarredReposListScreen(
    private val username: String
): Screen {

    override val key: ScreenKey
        get() = "${this::class.simpleName}($username)"

    private val titleRes: StringResource get() = Res.strings.title_starred

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: StarredReposListViewModel = koinScreenModel { parametersOf(username) }
        val state by viewModel.state.collectAsState()
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        var searchFieldCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        Scaffold(
            topBar = {
                LargeToolbar(
                    title = stringResource(titleRes),
                    scrollBehavior = scrollBehavior
                )
            }
        ) { pv ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pv)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .clearFocusOnOutsidePress(searchFieldCoords)
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onGloballyPositioned { searchFieldCoords = it },
                    placeholder = { Text(stringResource(Res.strings.hint_search_starred)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(
                            onClick = { viewModel.setQuery("") }
                        ) {
                            Icon(Icons.Outlined.Clear, contentDescription = null)
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    CategoryMenu(
                        lists = state.lists,
                        selectedListId = state.selectedListId,
                        onSelect = viewModel::selectList
                    )

                    Spacer(Modifier.weight(1f))

                    SortMenu(
                        selected = state.sort,
                        onSelect = viewModel::setSort
                    )
                }

                when {
                    state.loading && state.repos.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    state.filteredRepos.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(Res.strings.msg_no_results))
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = state.filteredRepos,
                            key = { StarredReposListViewModel.UiState.key(it) }
                        ) { repo ->
                            RepoItem(repo = repo)
                            ThinDivider()
                        }
                        if (state.loading) {
                            item(key = "loading_footer") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CategoryMenu(
        lists: List<StarredReposListViewModel.StarredList>,
        selectedListId: String?,
        onSelect: (String?) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        val currentName = when (selectedListId) {
            null -> stringResource(Res.strings.label_all)
            StarredReposListViewModel.UiState.UNCATEGORIZED_ID ->
                stringResource(Res.strings.label_uncategorized)
            else -> lists.firstOrNull { it.id == selectedListId }?.name
                ?: stringResource(Res.strings.label_all)
        }

        Box {
            TextButton(onClick = { expanded = true }) {
                Icon(
                    Icons.Outlined.Category,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = currentName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(96.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.strings.label_all)) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                    trailingIcon = if (selectedListId == null) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else null
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.strings.label_uncategorized)) },
                    onClick = {
                        onSelect(StarredReposListViewModel.UiState.UNCATEGORIZED_ID)
                        expanded = false
                    },
                    trailingIcon = if (selectedListId == StarredReposListViewModel.UiState.UNCATEGORIZED_ID) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else null
                )
                lists.forEach { list ->
                    DropdownMenuItem(
                        text = { Text(list.name) },
                        onClick = {
                            onSelect(list.id)
                            expanded = false
                        },
                        trailingIcon = if (selectedListId == list.id) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SortMenu(
        selected: StarredReposListViewModel.SortMode,
        onSelect: (StarredReposListViewModel.SortMode) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        val options = listOf(
            StarredReposListViewModel.SortMode.RECENTLY_STARRED to Res.strings.sort_recently_starred,
            StarredReposListViewModel.SortMode.OLDEST_STARRED to Res.strings.sort_oldest_starred,
            StarredReposListViewModel.SortMode.MOST_STARS to Res.strings.sort_most_stars,
            StarredReposListViewModel.SortMode.NAME_AZ to Res.strings.sort_name_az
        )

        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    Icons.Outlined.Sort,
                    contentDescription = stringResource(Res.strings.label_sort)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (mode, res) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(res)) },
                        onClick = {
                            onSelect(mode)
                            expanded = false
                        },
                        trailingIcon = if (selected == mode) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
    }

}

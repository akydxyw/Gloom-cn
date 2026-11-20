package dev.materii.gloom.ui.screen.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.icerock.moko.resources.compose.stringResource
import dev.materii.gloom.Res
import dev.materii.gloom.ui.component.ThinDivider
import dev.materii.gloom.ui.component.filter.ChoiceInputChip
import dev.materii.gloom.ui.screen.explore.component.SearchResultItem
import dev.materii.gloom.ui.screen.explore.component.TrendingFeedFooter
import dev.materii.gloom.ui.screen.explore.component.TrendingFeedHeader
import dev.materii.gloom.ui.screen.explore.component.TrendingRepoItem
import dev.materii.gloom.ui.screen.explore.viewmodel.ExploreViewModel
import dev.materii.gloom.ui.screen.explore.viewmodel.SearchSortMode
import dev.materii.gloom.ui.screen.explore.viewmodel.SearchTypeFilter
import dev.materii.gloom.ui.screen.profile.ProfileScreen
import dev.materii.gloom.ui.screen.repo.RepoScreen
import dev.materii.gloom.ui.util.NavigationUtil.navigate
import dev.materii.gloom.ui.util.clearFocusOnOutsidePress

class ExploreScreen: Tab {

    override val options: TabOptions
        @Composable get() {
            val navigator = LocalTabNavigator.current
            val selected = navigator.current == this
            return TabOptions(
                0u,
                stringResource(Res.strings.navigation_explore),
                rememberVectorPainter(if (selected) Icons.Filled.Explore else Icons.Outlined.Explore)
            )
        }

    @Composable
    override fun Content() = Screen()

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun Screen() {
        val viewModel: ExploreViewModel = koinScreenModel()
        val navigator = LocalNavigator.currentOrThrow
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        var searchFieldCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        Scaffold(
            topBar = { TopBar(scrollBehavior) }
        ) { pv ->
            Column(
                modifier = Modifier
                    .padding(pv)
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .clearFocusOnOutsidePress(searchFieldCoords)
            ) {
                SearchField(
                    query = viewModel.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onCoordsChanged = { searchFieldCoords = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (viewModel.searchQuery.isBlank()) {
                    TrendingContent(viewModel, navigator)
                } else {
                    SearchResultsContent(viewModel, navigator)
                }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun TrendingContent(
        viewModel: ExploreViewModel,
        navigator: Navigator
    ) {
        PullToRefreshBox(
            isRefreshing = viewModel.isLoading,
            onRefresh = { viewModel.loadTrending() },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                item("header") {
                    TrendingFeedHeader(
                        currentTrendingPeriod = viewModel.trendingPeriod,
                        onTrendingPeriodChange = { newPeriod -> viewModel.updateTrendingPeriod(newPeriod) }
                    )
                }

                items(
                    viewModel.trendingRepos,
                    key = { it.id }
                ) { trendingRepo ->
                    TrendingRepoItem(
                        trendingRepository = trendingRepo,
                        trendingPeriod = viewModel.trendingPeriod,
                        starToggleEnabled = !viewModel.repoStarsPending.contains(trendingRepo.id),
                        onClick = { navigator.navigate(RepoScreen(trendingRepo.owner.login, trendingRepo.name)) },
                        onOwnerClick = { navigator.navigate(ProfileScreen(trendingRepo.owner.login)) },
                        onStarClick = { viewModel.starRepo(trendingRepo.id) },
                        onUnstarClick = { viewModel.unstarRepo(trendingRepo.id) }
                    )
                }

                if (viewModel.trendingRepos.isNotEmpty()) {
                    item("footer") {
                        TrendingFeedFooter()
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchResultsContent(
        viewModel: ExploreViewModel,
        navigator: Navigator
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ChoiceInputChip(
                    defaultValue = SearchTypeFilter.REPOSITORIES,
                    currentValue = viewModel.searchType,
                    onSelectChoice = viewModel::onSearchTypeSelected,
                    label = { type ->
                        Text(
                            stringResource(
                                when (type) {
                                    SearchTypeFilter.REPOSITORIES -> Res.strings.label_search_repositories
                                    SearchTypeFilter.USERS        -> Res.strings.label_search_users
                                    SearchTypeFilter.ISSUES       -> Res.strings.label_search_issues
                                    SearchTypeFilter.DISCUSSIONS  -> Res.strings.label_search_discussions
                                }
                            )
                        )
                    }
                )

                Spacer(Modifier.width(8.dp))

                ChoiceInputChip(
                    defaultValue = SearchSortMode.BEST_MATCH,
                    currentValue = viewModel.searchSort,
                    onSelectChoice = viewModel::onSearchSortSelected,
                    label = { sort ->
                        Text(
                            stringResource(
                                when (sort) {
                                    SearchSortMode.BEST_MATCH       -> Res.strings.search_sort_best_match
                                    SearchSortMode.MOST_STARS       -> Res.strings.search_sort_most_stars
                                    SearchSortMode.MOST_FORKS       -> Res.strings.search_sort_most_forks
                                    SearchSortMode.RECENTLY_UPDATED -> Res.strings.search_sort_recently_updated
                                    SearchSortMode.MOST_FOLLOWERS   -> Res.strings.search_sort_most_followers
                                    SearchSortMode.MOST_COMMENTED   -> Res.strings.search_sort_most_commented
                                    SearchSortMode.NEWEST           -> Res.strings.search_sort_newest
                                }
                            )
                        )
                    }
                )

                Spacer(Modifier.weight(1f))

                viewModel.searchResultCount?.let { count ->
                    Text(
                        text = stringResource(Res.strings.msg_search_result_count, count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                    )
                }
            }

            when {
                viewModel.searchResults.isEmpty() && viewModel.isSearching -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                viewModel.searchResults.isEmpty() && viewModel.searchFailed -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(Res.strings.msg_search_failed))
                        TextButton(onClick = viewModel::retrySearch) {
                            Text(stringResource(Res.strings.action_try_again))
                        }
                    }
                }

                viewModel.searchResults.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(Res.strings.msg_search_no_results))
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = viewModel.searchResults,
                        key = { index, item -> "$index:${item.hashCode()}" }
                    ) { _, item ->
                        SearchResultItem(item = item)
                        ThinDivider()
                    }

                    if (viewModel.searchHasMore) {
                        item(key = "search_footer") {
                            LaunchedEffect(Unit) {
                                viewModel.loadMoreSearchResults()
                            }
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

    @Composable
    private fun SearchField(
        query: String,
        onQueryChange: (String) -> Unit,
        onCoordsChanged: (LayoutCoordinates) -> Unit,
        modifier: Modifier = Modifier
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    onCoordsChanged(coords)
                },
            placeholder = { Text(stringResource(Res.strings.hint_search_github)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(
                    onClick = { onQueryChange("") }
                ) {
                    Icon(Icons.Outlined.Clear, contentDescription = null)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp)
        )
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun TopBar(
        scrollBehavior: TopAppBarScrollBehavior
    ) {
        LargeTopAppBar(
            title = {
                Text(text = options.title)
            },
            scrollBehavior = scrollBehavior
        )
    }

}

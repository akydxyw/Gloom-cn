package dev.materii.gloom.ui.screen.explore.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.materii.gloom.api.dto.user.User
import dev.materii.gloom.api.model.ModelRepo
import dev.materii.gloom.api.model.ModelUser
import dev.materii.gloom.api.repository.GraphQLRepository
import dev.materii.gloom.api.util.fold
import dev.materii.gloom.api.util.ifSuccessful
import dev.materii.gloom.domain.manager.PreferenceManager
import dev.materii.gloom.domain.manager.enums.TrendingPeriodPreference
import dev.materii.gloom.gql.SearchQuery
import dev.materii.gloom.gql.fragment.TrendingRepository
import dev.materii.gloom.gql.type.SearchType
import dev.materii.gloom.gql.type.TrendingPeriod
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The type of GitHub resources to search for.
 */
enum class SearchTypeFilter {
    REPOSITORIES,
    USERS,
    ISSUES,
    DISCUSSIONS;

    fun toApiType(): SearchType = when (this) {
        REPOSITORIES -> SearchType.REPOSITORY
        USERS        -> SearchType.USER
        ISSUES       -> SearchType.ISSUE
        DISCUSSIONS  -> SearchType.DISCUSSION
    }
}

/**
 * Sort options for GitHub search. The qualifier is appended to the raw
 * search query using GitHub's search syntax (e.g. `sort:stars-desc`).
 */
enum class SearchSortMode(
    val qualifier: String?
) {
    BEST_MATCH(null),
    MOST_STARS("sort:stars-desc"),
    MOST_FORKS("sort:forks-desc"),
    RECENTLY_UPDATED("sort:updated-desc"),
    MOST_FOLLOWERS("sort:followers-desc"),
    MOST_COMMENTED("sort:comments-desc"),
    NEWEST("sort:joined-desc");

    companion object {

        fun sortsFor(type: SearchTypeFilter): List<SearchSortMode> = when (type) {
            SearchTypeFilter.REPOSITORIES -> listOf(BEST_MATCH, MOST_STARS, MOST_FORKS, RECENTLY_UPDATED)
            SearchTypeFilter.USERS        -> listOf(BEST_MATCH, MOST_FOLLOWERS, NEWEST)
            SearchTypeFilter.ISSUES       -> listOf(BEST_MATCH, RECENTLY_UPDATED, MOST_COMMENTED)
            SearchTypeFilter.DISCUSSIONS  -> listOf(BEST_MATCH, RECENTLY_UPDATED, MOST_COMMENTED)
        }
    }
}

/**
 * A single item in the GitHub search results.
 */
sealed interface SearchResult {

    data class Repo(
        val repo: ModelRepo
    ): SearchResult

    data class User(
        val user: ModelUser
    ): SearchResult

    data class Issue(
        val issue: SearchQuery.OnIssue
    ): SearchResult

    data class PullRequest(
        val pullRequest: SearchQuery.OnPullRequest
    ): SearchResult

    data class Discussion(
        val discussion: SearchQuery.OnDiscussion
    ): SearchResult
}

class ExploreViewModel(
    private val graphQLRepository: GraphQLRepository,
    private val preferenceManager: PreferenceManager
): ScreenModel {

    val trendingRepos = mutableStateListOf<TrendingRepository>()

    val repoStarsPending = mutableStateListOf<String>()

    var isLoading by mutableStateOf(false)
        private set

    var trendingPeriod by mutableStateOf(preferenceManager.trendingPeriod)
        private set

    // SEARCH

    var searchQuery by mutableStateOf("")
        private set

    var searchType by mutableStateOf(SearchTypeFilter.REPOSITORIES)
        private set

    var searchSort by mutableStateOf(SearchSortMode.BEST_MATCH)
        private set

    val searchResults = mutableStateListOf<SearchResult>()

    var isSearching by mutableStateOf(false)
        private set

    var searchHasMore by mutableStateOf(false)
        private set

    var searchFailed by mutableStateOf(false)
        private set

    var searchResultCount by mutableStateOf<Int?>(null)
        private set

    private var searchCursor: String? = null

    private var searchJob: Job? = null

    init {
        loadTrending()
    }

    // PUBLIC

    fun loadTrending() {
        screenModelScope.launch {
            isLoading = true
            graphQLRepository.getTrending(trendingPeriod.toApi()).ifSuccessful { trending ->
                trendingRepos.clear()
                trendingRepos.addAll(trending)
            }
            isLoading = false
        }
    }

    fun updateTrendingPeriod(newPeriod: TrendingPeriodPreference) {
        trendingPeriod = newPeriod
        preferenceManager.trendingPeriod = newPeriod
        trendingRepos.clear()
        loadTrending()
    }

    fun starRepo(id: String) {
        screenModelScope.launch {
            repoStarsPending.add(id)
            editRepo(id) { it.copy(viewerHasStarred = true) }
            graphQLRepository.starRepo(id).fold(
                onSuccess = { _, _ -> },
                onError = {
                    editRepo(id) { it.copy(viewerHasStarred = false) }
                },
                onFailure = {
                    editRepo(id) { it.copy(viewerHasStarred = false) }
                }
            )
            repoStarsPending.remove(id)
        }
    }

    fun unstarRepo(id: String) {
        screenModelScope.launch {
            repoStarsPending.add(id)
            editRepo(id) { it.copy(viewerHasStarred = false) }
            graphQLRepository.unstarRepo(id).fold(
                onSuccess = { _, _ -> },
                onError = {
                    editRepo(id) { it.copy(viewerHasStarred = true) }
                },
                onFailure = {
                    editRepo(id) { it.copy(viewerHasStarred = true) }
                }
            )
            repoStarsPending.remove(id)
        }
    }

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        if (query.isBlank()) {
            searchJob?.cancel()
            searchJob = null
            clearSearchResults()
            return
        }
        launchSearch(reset = true, debounceMs = SEARCH_DEBOUNCE_MS)
    }

    fun onSearchTypeSelected(type: SearchTypeFilter) {
        if (type == searchType) return
        searchType = type
        if (searchSort !in SearchSortMode.sortsFor(type)) {
            searchSort = SearchSortMode.BEST_MATCH
        }
        if (searchQuery.isNotBlank()) launchSearch(reset = true)
    }

    fun onSearchSortSelected(sort: SearchSortMode) {
        if (sort == searchSort || sort !in SearchSortMode.sortsFor(searchType)) return
        searchSort = sort
        if (searchQuery.isNotBlank()) launchSearch(reset = true)
    }

    fun loadMoreSearchResults() {
        if (isSearching || !searchHasMore || searchQuery.isBlank()) return
        launchSearch(reset = false)
    }

    fun retrySearch() {
        if (searchQuery.isBlank() || isSearching) return
        launchSearch(reset = searchResults.isEmpty())
    }

    // PRIVATE

    private fun launchSearch(reset: Boolean, debounceMs: Long = 0) {
        searchJob?.cancel()
        searchJob = screenModelScope.launch {
            try {
                if (debounceMs > 0) delay(debounceMs)
                isSearching = true
                searchFailed = false
                if (reset) {
                    searchCursor = null
                    searchHasMore = false
                }
                graphQLRepository.search(
                    query = buildSearchQuery(),
                    type = searchType.toApiType(),
                    after = searchCursor,
                    count = SEARCH_PAGE_SIZE
                ).fold(
                    onSuccess = { data, _ ->
                        val connection = data.search
                        searchCursor = connection.pageInfo.endCursor
                        searchHasMore = connection.pageInfo.hasNextPage
                        searchResultCount = when (searchType) {
                            SearchTypeFilter.REPOSITORIES -> connection.repositoryCount
                            SearchTypeFilter.USERS        -> connection.userCount
                            SearchTypeFilter.ISSUES       -> connection.issueCount
                            SearchTypeFilter.DISCUSSIONS  -> connection.discussionCount
                        }
                        val newItems = connection.nodes.orEmpty().mapNotNull { node ->
                            node?.toSearchResult()
                        }
                        if (reset) searchResults.clear()
                        searchResults.addAll(newItems)
                    },
                    onError = { _ -> searchFailed = true },
                    onFailure = { searchFailed = true }
                )
            } finally {
                isSearching = false
            }
        }
    }

    private fun buildSearchQuery(): String {
        val base = searchQuery.trim()
        val qualifier = searchSort.qualifier ?: return base
        return "$base $qualifier"
    }

    private fun clearSearchResults() {
        searchResults.clear()
        searchCursor = null
        searchHasMore = false
        searchFailed = false
        isSearching = false
        searchResultCount = null
    }

    private fun SearchQuery.Node.toSearchResult(): SearchResult? {
        onRepository?.let {
            return SearchResult.Repo(ModelRepo.fromRepoListFragment(it.repoListRepoFragment))
        }
        onUser?.let {
            return SearchResult.User(
                ModelUser(
                    username = it.login,
                    displayName = it.displayName,
                    bio = it.bio,
                    avatar = it.avatarUrl,
                    type = User.Type.USER,
                    followers = it.followers.totalCount.toLong()
                )
            )
        }
        onOrganization?.let {
            return SearchResult.User(
                ModelUser(
                    username = it.login,
                    displayName = it.orgName,
                    bio = it.description,
                    avatar = it.avatarUrl,
                    type = User.Type.ORG
                )
            )
        }
        onIssue?.let { return SearchResult.Issue(it) }
        onPullRequest?.let { return SearchResult.PullRequest(it) }
        onDiscussion?.let { return SearchResult.Discussion(it) }
        return null
    }

    private fun editRepo(id: String, block: (TrendingRepository) -> TrendingRepository) {
        val targetIndex = trendingRepos.indexOfFirst { it.id == id }
        val target = trendingRepos[targetIndex]
        trendingRepos[targetIndex] = block(target)
    }

    private fun TrendingPeriodPreference.toApi(): TrendingPeriod {
        return when (this) {
            TrendingPeriodPreference.MONTHLY -> TrendingPeriod.MONTHLY
            TrendingPeriodPreference.WEEKLY  -> TrendingPeriod.WEEKLY
            TrendingPeriodPreference.DAILY   -> TrendingPeriod.DAILY
        }
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 30
        private const val SEARCH_DEBOUNCE_MS = 400L
    }

}

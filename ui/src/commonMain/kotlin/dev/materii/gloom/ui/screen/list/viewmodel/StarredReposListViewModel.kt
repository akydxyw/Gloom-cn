package dev.materii.gloom.ui.screen.list.viewmodel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.materii.gloom.api.model.ModelRepo
import dev.materii.gloom.api.repository.GraphQLRepository
import dev.materii.gloom.api.util.getOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StarredReposListViewModel(
    private val repo: GraphQLRepository,
    private val username: String
): ScreenModel {

    enum class SortMode {
        RECENTLY_STARRED,
        OLDEST_STARRED,
        MOST_STARS,
        NAME_AZ
    }

    data class StarredList(
        val id: String,
        val name: String,
        val description: String?,
        val isPrivate: Boolean,
        val repoKeys: Set<String>
    )

    data class UiState(
        val loading: Boolean = true,
        val repos: List<ModelRepo> = emptyList(),
        val lists: List<StarredList> = emptyList(),
        val query: String = "",
        val selectedListId: String? = null,
        val sort: SortMode = SortMode.RECENTLY_STARRED
    ) {
        val filteredRepos: List<ModelRepo>
            get() {
                val q = query.trim().lowercase()
                var result = when (selectedListId) {
                    null -> repos
                    UNCATEGORIZED_ID -> {
                        val categorized = lists.flatMap { it.repoKeys }.toSet()
                        repos.filter { key(it) !in categorized }
                    }
                    else -> {
                        val keys = lists.firstOrNull { it.id == selectedListId }?.repoKeys ?: emptySet()
                        repos.filter { key(it) in keys }
                    }
                }
                if (q.isNotEmpty()) result = result.filter { r ->
                    r.name?.lowercase()?.contains(q) == true ||
                        r.owner?.username?.lowercase()?.contains(q) == true ||
                        r.description?.lowercase()?.contains(q) == true
                }
                return when (sort) {
                    SortMode.RECENTLY_STARRED -> result
                    SortMode.OLDEST_STARRED -> result.asReversed()
                    SortMode.MOST_STARS -> result.sortedByDescending { it.stars ?: 0 }
                    SortMode.NAME_AZ -> result.sortedWith(
                        compareBy(
                            { it.name?.lowercase() ?: "" },
                            { it.owner?.username?.lowercase() ?: "" }
                        )
                    )
                }
            }

        companion object {
            const val UNCATEGORIZED_ID = "__uncategorized__"

            fun key(repo: ModelRepo): String =
                "${repo.owner?.username ?: ""}/${repo.name ?: ""}".lowercase()
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun selectList(listId: String?) = _state.update { it.copy(selectedListId = listId) }

    fun setSort(sort: SortMode) = _state.update { it.copy(sort = sort) }

    fun refresh() = load()

    private fun load() {
        loadJob?.cancel()
        _state.update {
            it.copy(loading = true, repos = emptyList(), lists = emptyList())
        }
        loadJob = screenModelScope.launch {
            val repos = mutableListOf<ModelRepo>()
            var cursor: String? = null
            var hasMore = true
            while (hasMore) {
                val data = repo.getStarredRepositories(username, cursor).getOrNull()
                    ?: break
                repos += data.user?.starredRepositories?.nodes.orEmpty()
                    .filterNotNull()
                    .map { ModelRepo.fromStarredReposQuery(it) }
                val pageInfo = data.user?.starredRepositories?.pageInfo
                hasMore = pageInfo?.hasNextPage == true && pageInfo.endCursor != null
                cursor = pageInfo?.endCursor
                _state.update { it.copy(repos = repos.toList()) }
            }

            val lists = mutableListOf<StarredList>()
            var listCursor: String? = null
            var listsHasMore = true
            while (listsHasMore) {
                val data = repo.getStarredLists(username, listCursor).getOrNull()
                    ?: break
                val listsConnection = data.user?.lists ?: break
                lists += listsConnection.nodes.orEmpty().filterNotNull().map { list ->
                    StarredList(
                        id = list.id,
                        name = list.name,
                        description = list.description,
                        isPrivate = list.isPrivate,
                        repoKeys = list.items?.nodes.orEmpty()
                            .filterNotNull()
                            .mapNotNull { item ->
                                val mapped = ModelRepo.fromStarredListsQuery(item)
                                    ?: return@mapNotNull null
                                UiState.key(mapped)
                            }
                            .toSet()
                    )
                }
                listsHasMore = listsConnection.pageInfo?.hasNextPage == true
                listCursor = listsConnection.pageInfo?.endCursor
            }

            _state.update {
                it.copy(loading = false, repos = repos.toList(), lists = lists)
            }
        }
    }

}

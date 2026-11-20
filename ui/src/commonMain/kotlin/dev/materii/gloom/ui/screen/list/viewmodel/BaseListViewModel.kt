package dev.materii.gloom.ui.screen.list.viewmodel

import androidx.paging.*
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.apollographql.apollo.api.Query

abstract class BaseListViewModel<I: Any, D: Query.Data?>: ScreenModel {

    val items = Pager(PagingConfig(pageSize = 30)) {
        object: PagingSource<String, I>() {
            // Cross-page de-duplication: cursor-based feeds can return the same
            // entry again when the boundary shifts between pages. Only entries
            // with a strong unique fingerprint are dropped.
            private val seen = mutableSetOf<String>()

            override suspend fun load(params: LoadParams<String>): LoadResult<String, I> = try {
                val page = params.key

                val response = loadPage(page)

                val nextKey = getCursor(response)

                var nodes = createItems(response)
                if (page != null) {
                    nodes = nodes.filter { item ->
                        val fingerprint = itemFingerprint(item)
                        fingerprint == null || seen.add(fingerprint)
                    }
                }

                LoadResult.Page(
                    data = nodes,
                    nextKey = nextKey,
                    prevKey = null
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadResult.Error(e)
            }

            override fun getRefreshKey(state: PagingState<String, I>): String? =
                state.anchorPosition?.let {
                    state.closestPageToPosition(it)?.prevKey
                }
        }
    }.flow.cachedIn(screenModelScope)

    abstract suspend fun loadPage(cursor: String? = null): D

    abstract fun createItems(data: D): List<I>

    abstract fun getCursor(data: D): String?

    /**
     * Stable unique identity of a list item, used to drop entries that show up
     * on more than one page. Return null to skip de-duplication for this item.
     */
    open fun itemFingerprint(item: I): String? = null

}
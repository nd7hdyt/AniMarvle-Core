package com.animarvle.app.ui.mylist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animarvle.app.AnimarvleApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MyListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as AnimarvleApp).container.myList

    val allItems: StateFlow<List<MyListItem>> = repository.itemsFlow

    private val _selectedType = MutableStateFlow("Movies")
    val selectedType: StateFlow<String> = _selectedType

    private val _selectedStatus = MutableStateFlow("Pending")
    val selectedStatus: StateFlow<String> = _selectedStatus

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val filteredItems: StateFlow<List<MyListItem>> = combine(
        repository.itemsFlow,
        _selectedType,
        _selectedStatus,
        _searchQuery,
    ) { items, type, status, query ->
        items.filter { item ->
            val isAnime = item.posterUrl?.contains("anilist", ignoreCase = true) == true ||
                item.format?.contains("ANIME", ignoreCase = true) == true ||
                item.format in listOf("ONA", "OVA", "TV_SHORT", "SPECIAL")

            val matchesType = when (type) {
                "Movies" -> !isAnime && (item.format?.contains("MOVIE", ignoreCase = true) == true ||
                    item.format?.contains("Movie", ignoreCase = true) == true ||
                    (item.totalEpisodes == 1 && item.format?.contains("TV", ignoreCase = true) != true))
                "Series" -> !isAnime && (item.format?.equals("TV", ignoreCase = true) == true ||
                    item.format?.contains("SERIES", ignoreCase = true) == true ||
                    item.format?.contains("Series", ignoreCase = true) == true)
                "Anime" -> isAnime
                else -> true
            }

            val matchesStatus = when (status) {
                "Pending" -> item.status == MyListStatus.PLAN_TO_WATCH || (item.status != MyListStatus.WATCHING && item.status != MyListStatus.COMPLETED)
                "Watching" -> item.status == MyListStatus.WATCHING
                "Completed" -> item.status == MyListStatus.COMPLETED
                else -> true
            }

            val matchesQuery = query.isBlank() || item.title.contains(query, ignoreCase = true)

            matchesType && matchesStatus && matchesQuery
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun selectType(type: String) {
        _selectedType.value = type
    }

    fun selectStatus(status: String) {
        _selectedStatus.value = status
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun removeItem(mediaId: Long) {
        viewModelScope.launch {
            repository.remove(mediaId)
        }
    }

    fun updateItemStatus(item: MyListItem, newStatus: MyListStatus) {
        viewModelScope.launch {
            repository.upsert(item.copy(status = newStatus, updatedAtMs = System.currentTimeMillis()))
        }
    }
}

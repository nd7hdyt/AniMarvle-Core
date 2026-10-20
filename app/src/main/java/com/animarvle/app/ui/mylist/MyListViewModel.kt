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

    private val _selectedStatus = MutableStateFlow<MyListStatus?>(null)
    val selectedStatus: StateFlow<MyListStatus?> = _selectedStatus

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val filteredItems: StateFlow<List<MyListItem>> = combine(
        repository.itemsFlow,
        _selectedStatus,
        _searchQuery,
    ) { items, status, query ->
        items.filter { item ->
            val matchesStatus = (status == null || item.status == status)
            val matchesQuery = query.isBlank() || item.title.contains(query, ignoreCase = true)
            matchesStatus && matchesQuery
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun selectStatus(status: MyListStatus?) {
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
}

package com.animarvle.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.animarvle.app.data.AppMediaMode
import com.animarvle.app.data.MediaTypeManager
import com.animarvle.app.data.MetadataRepository
import com.animarvle.app.domain.ScheduleItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ScheduleState(
    val loading: Boolean = false,
    val dayOffset: Int = 0,
    val mode: AppMediaMode = AppMediaMode.ANIME,
    val totalCount: Int? = null,
    val items: List<ScheduleItem> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false,
)

class ScheduleViewModel(
    private val repo: MetadataRepository,
    private val mediaTypeManager: MediaTypeManager? = null,
) : ViewModel() {

    private val initialMode = mediaTypeManager?.currentMode?.value ?: AppMediaMode.ANIME
    private val _state = MutableStateFlow(ScheduleState(mode = initialMode))
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    init {
        load(0, initialMode)
    }

    fun setMode(newMode: AppMediaMode) {
        if (_state.value.mode == newMode) return
        mediaTypeManager?.setMode(newMode)
        load(_state.value.dayOffset, newMode)
    }

    fun load(dayOffset: Int = _state.value.dayOffset, mode: AppMediaMode = _state.value.mode) {
        _state.value = _state.value.copy(loading = true, dayOffset = dayOffset, mode = mode, error = null)
        viewModelScope.launch {
            runCatching {
                if (mode == AppMediaMode.ANIME) {
                    val items = repo.scheduleFromToday(dayOffset)
                    items.size to items
                } else {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
                    val dateIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
                    repo.tmdbSchedule(dateIso)
                }
            }.onSuccess { (total, items) ->
                _state.value = _state.value.copy(
                    loading = false,
                    totalCount = total,
                    items = items,
                    error = null,
                    loaded = true,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message,
                    loaded = true,
                )
            }
        }
    }

    class Factory(
        private val repo: MetadataRepository,
        private val mediaTypeManager: MediaTypeManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ScheduleViewModel(repo, mediaTypeManager) as T
    }
}
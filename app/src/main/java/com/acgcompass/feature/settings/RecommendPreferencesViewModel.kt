package com.acgcompass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.domain.recommend.RecommendPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置 → 推荐偏好：读写 [RecommendPreferences]，探索队列和今晚看什么下次生成时生效。 */
@HiltViewModel
class RecommendPreferencesViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val preferences: StateFlow<RecommendPreferences?> = settingsDataStore.recommendPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun update(transform: (RecommendPreferences) -> RecommendPreferences) {
        val current = preferences.value ?: RecommendPreferences.DEFAULT
        viewModelScope.launch { settingsDataStore.setRecommendPreferences(transform(current).normalized()) }
    }

    fun reset() = update { RecommendPreferences.DEFAULT }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

package com.acgcompass.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.designsystem.FlatSwitch
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.ui.AcgTopBarHeight
import com.acgcompass.core.ui.topBarScrim
import com.acgcompass.core.ui.transparentTopBarColors
import com.acgcompass.data.datastore.HomeModulePref
import com.acgcompass.data.datastore.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 首页模块偏好设置 ViewModel（独立、不影响主设置页）。 */
@HiltViewModel
class HomeModulesViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val enabledKeys: StateFlow<Set<String>> =
        settingsDataStore.homeModules
            .map { it }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                com.acgcompass.data.datastore.HomeModulePrefs.DEFAULT_KEYS,
            )

    fun toggle(key: String, on: Boolean) {
        viewModelScope.launch {
            val current = enabledKeys.value.toMutableSet()
            if (on) current.add(key) else current.remove(key)
            settingsDataStore.setHomeModules(current)
        }
    }
}

/** 首页展示模块偏好页（设置 → 首页模块）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeModulesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeModulesViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabledKeys.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.topBarScrim(),
                colors = transparentTopBarColors(),
                expandedHeight = AcgTopBarHeight,
                title = { Text("首页模块") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "选择首页要展示的模块。首页聚焦今日决策与推荐入口；完整管理在「待补池」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrismCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    HomeModulePref.entries.forEach { module ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                module.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            FlatSwitch(
                                checked = module.key in enabled,
                                onCheckedChange = { viewModel.toggle(module.key, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

package dev.materii.gloom.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import dev.icerock.moko.resources.compose.stringResource
import dev.materii.gloom.Res
import dev.materii.gloom.api.translation.FreePoolService
import dev.materii.gloom.api.translation.TranslationManager
import dev.materii.gloom.domain.manager.enums.FreePoolMode
import dev.materii.gloom.domain.manager.enums.TranslationEngine
import dev.materii.gloom.domain.manager.enums.TranslationTarget
import dev.materii.gloom.ui.component.toolbar.LargeToolbar
import dev.materii.gloom.ui.screen.settings.component.SettingsGroup
import dev.materii.gloom.ui.screen.settings.component.SettingsHeader
import dev.materii.gloom.ui.screen.settings.component.SettingsItem
import dev.materii.gloom.ui.screen.settings.component.SettingsItemChoice
import dev.materii.gloom.ui.screen.settings.component.SettingsSwitch
import dev.materii.gloom.ui.screen.settings.viewmodel.TranslationSettingsViewModel
import dev.materii.gloom.util.getString
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class CheckState { IDLE, CHECKING, OK, FAIL }

class TranslationSettingsScreen: Screen {

    @Composable
    override fun Content() = Screen()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(
        viewModel: TranslationSettingsViewModel = koinScreenModel()
    ) {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val manager: TranslationManager = koinInject()
        val scope = rememberCoroutineScope()
        val checkStates = remember { mutableStateMapOf<String, CheckState>() }
        val disabledServices = viewModel.prefs.freePoolDisabled
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        fun toggleService(id: String, enabled: Boolean) {
            val next = disabledServices.toMutableSet()
            if (enabled) next.remove(id) else next.add(id)
            viewModel.prefs.freePoolDisabled = next.joinToString(",")
        }

        fun checkService(service: FreePoolService) {
            if (checkStates[service.id] == CheckState.CHECKING) return
            scope.launch {
                checkStates[service.id] = CheckState.CHECKING
                val ok = manager.checkService(service)
                checkStates[service.id] = if (ok) CheckState.OK else CheckState.FAIL
            }
        }

        Scaffold(
            topBar = { Toolbar(scrollBehavior) },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        ) { pv ->
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(pv)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SettingsHeader(stringResource(Res.strings.translation_service))
                SettingsGroup {
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_auto),
                        secondaryLabel = stringResource(Res.strings.translation_auto_description),
                        pref = viewModel.prefs.autoTranslate,
                        onPrefChange = { viewModel.prefs.autoTranslate = it }
                    )
                }

                SettingsGroup {
                    SettingsItemChoice(
                        label = stringResource(Res.strings.translation_service),
                        pref = viewModel.prefs.translationEngine,
                        onPrefChange = { viewModel.prefs.translationEngine = it },
                        labelFactory = { engineLabel(it) }
                    )

                    SettingsItemChoice(
                        label = stringResource(Res.strings.translation_target_language),
                        pref = viewModel.prefs.translationTarget,
                        onPrefChange = { viewModel.prefs.translationTarget = it },
                        labelFactory = { targetLabel(it) }
                    )
                }

                if (viewModel.prefs.translationEngine == TranslationEngine.FREE_POOL) {
                    SettingsHeader(stringResource(Res.strings.translation_pool))
                    SettingsGroup {
                        SettingsItemChoice(
                            label = stringResource(Res.strings.translation_pool_mode),
                            pref = viewModel.prefs.freePoolMode,
                            onPrefChange = { viewModel.prefs.freePoolMode = it },
                            labelFactory = { mode ->
                                when (mode) {
                                    FreePoolMode.BALANCED -> getString(Res.strings.translation_pool_balanced)
                                    FreePoolMode.SEQUENTIAL -> getString(Res.strings.translation_pool_sequential)
                                }
                            }
                        )
                    }

                    SettingsHeader(stringResource(Res.strings.translation_pool_services))
                    SettingsGroup {
                        FreePoolService.entries.forEach { service ->
                            ServiceCheckRow(
                                label = poolServiceLabel(service),
                                enabled = service.id !in disabledServices,
                                checkState = checkStates[service.id] ?: CheckState.IDLE,
                                onToggle = { toggleService(service.id, it) },
                                onCheck = { checkService(service) }
                            )
                        }
                    }

                    Text(
                        text = stringResource(Res.strings.translation_check_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }

                when (viewModel.prefs.translationEngine) {
                    TranslationEngine.DEEPLX -> {
                        SettingsHeader(stringResource(Res.strings.translation_deeplx_header))
                        SettingsGroup {
                            PreferenceTextField(
                                label = stringResource(Res.strings.translation_deeplx_endpoint),
                                hint = stringResource(Res.strings.translation_deeplx_endpoint_hint),
                                value = viewModel.prefs.deeplxEndpoint,
                                onValueChange = { viewModel.prefs.deeplxEndpoint = it }
                            )
                            PreferenceTextField(
                                label = stringResource(Res.strings.translation_deeplx_token),
                                value = viewModel.prefs.deeplxToken,
                                onValueChange = { viewModel.prefs.deeplxToken = it }
                            )
                        }
                    }

                    TranslationEngine.OPENAI -> {
                        SettingsHeader(stringResource(Res.strings.translation_openai_header))
                        SettingsGroup {
                            PreferenceTextField(
                                label = stringResource(Res.strings.translation_openai_endpoint),
                                hint = stringResource(Res.strings.translation_openai_endpoint_hint),
                                value = viewModel.prefs.openaiEndpoint,
                                onValueChange = { viewModel.prefs.openaiEndpoint = it }
                            )
                            PreferenceTextField(
                                label = stringResource(Res.strings.translation_openai_key),
                                value = viewModel.prefs.openaiKey,
                                onValueChange = { viewModel.prefs.openaiKey = it }
                            )
                            PreferenceTextField(
                                label = stringResource(Res.strings.translation_openai_model),
                                value = viewModel.prefs.openaiModel,
                                onValueChange = { viewModel.prefs.openaiModel = it }
                            )
                        }
                    }

                    TranslationEngine.MYMEMORY -> {
                        SettingsHeader(stringResource(Res.strings.translation_mymemory_header))
                        SettingsGroup {
                            PreferenceTextField(
                                label = stringResource(Res.strings.translation_mymemory_email),
                                value = viewModel.prefs.mymemoryEmail,
                                onValueChange = { viewModel.prefs.mymemoryEmail = it }
                            )
                        }
                    }

                    else -> {}
                }

                SettingsHeader(stringResource(Res.strings.translation_scope_header))
                SettingsGroup {
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_scope_repo_name),
                        pref = viewModel.prefs.translateRepoName,
                        onPrefChange = { viewModel.prefs.translateRepoName = it }
                    )
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_scope_repo_description),
                        pref = viewModel.prefs.translateRepoDescription,
                        onPrefChange = { viewModel.prefs.translateRepoDescription = it }
                    )
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_scope_readme),
                        pref = viewModel.prefs.translateReadme,
                        onPrefChange = { viewModel.prefs.translateReadme = it }
                    )
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_scope_issue_title),
                        pref = viewModel.prefs.translateIssueTitle,
                        onPrefChange = { viewModel.prefs.translateIssueTitle = it }
                    )
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_scope_pr_title),
                        pref = viewModel.prefs.translatePrTitle,
                        onPrefChange = { viewModel.prefs.translatePrTitle = it }
                    )
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_scope_commits),
                        pref = viewModel.prefs.translateCommits,
                        onPrefChange = { viewModel.prefs.translateCommits = it }
                    )
                    SettingsSwitch(
                        label = stringResource(Res.strings.translation_scope_releases),
                        pref = viewModel.prefs.translateReleases,
                        onPrefChange = { viewModel.prefs.translateReleases = it }
                    )
                }

                Text(
                    text = stringResource(Res.strings.translation_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    @Composable
    private fun ServiceCheckRow(
        label: String,
        enabled: Boolean,
        checkState: CheckState,
        onToggle: (Boolean) -> Unit,
        onCheck: () -> Unit
    ) {
        SettingsItem(
            text = { Text(label) },
            secondaryText = {
                val text = when (checkState) {
                    CheckState.OK -> stringResource(Res.strings.translation_check_ok)
                    CheckState.FAIL -> stringResource(Res.strings.translation_check_fail)
                    CheckState.CHECKING -> stringResource(Res.strings.translation_checking)
                    CheckState.IDLE -> null
                }
                text?.let {
                    Text(
                        text = it,
                        color = when (checkState) {
                            CheckState.OK -> gloomCheckOk()
                            CheckState.FAIL -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                    )
                }
            },
            onClick = onCheck,
            enabled = enabled
        ) {
            when (checkState) {
                CheckState.CHECKING -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                CheckState.OK -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = gloomCheckOk(),
                    modifier = Modifier.size(20.dp)
                )
                CheckState.FAIL -> Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                CheckState.IDLE -> Spacer(Modifier.size(20.dp))
            }

            IconButton(
                onClick = onCheck,
                enabled = enabled,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.NetworkCheck,
                    contentDescription = stringResource(Res.strings.translation_check),
                    modifier = Modifier.size(20.dp)
                )
            }

            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }

    @Composable
    private fun gloomCheckOk() = MaterialTheme.colorScheme.primary

    @Composable
    private fun PreferenceTextField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        hint: String? = null
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            supportingText = hint?.let { { Text(it) } },
            singleLine = true,
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }

    private fun engineLabel(engine: TranslationEngine): String = when (engine) {
        TranslationEngine.FREE_POOL -> getString(Res.strings.translation_pool)
        TranslationEngine.GOOGLE -> getString(Res.strings.service_google)
        TranslationEngine.MICROSOFT -> getString(Res.strings.service_microsoft)
        TranslationEngine.MYMEMORY -> getString(Res.strings.service_mymemory)
        TranslationEngine.DEEPLX -> getString(Res.strings.translation_engine_deeplx)
        TranslationEngine.OPENAI -> getString(Res.strings.translation_engine_openai)
    }

    @Composable
    private fun poolServiceLabel(service: FreePoolService): String = when (service) {
        FreePoolService.MICROSOFT -> getString(Res.strings.service_microsoft)
        FreePoolService.GOOGLE -> getString(Res.strings.service_google)
        FreePoolService.TRANSMART -> getString(Res.strings.service_transmart)
        FreePoolService.VOLCENGINE -> getString(Res.strings.service_volcengine)
        FreePoolService.YOUDAO -> getString(Res.strings.service_youdao)
        FreePoolService.ICIBA -> getString(Res.strings.service_iciba)
        FreePoolService.ALIBABA -> getString(Res.strings.service_alibaba)
        FreePoolService.BILIBILI -> getString(Res.strings.service_bilibili)
        FreePoolService.MODERNMT -> getString(Res.strings.service_modernmt)
        FreePoolService.LINGVANEX -> getString(Res.strings.service_lingvanex)
        FreePoolService.YANDEX -> getString(Res.strings.service_yandex)
        FreePoolService.MYMEMORY -> getString(Res.strings.service_mymemory)
        FreePoolService.REVERSO -> getString(Res.strings.service_reverso)
        FreePoolService.APERTIUM -> getString(Res.strings.service_apertium)
    }

    private fun targetLabel(target: TranslationTarget): String = when (target) {
        TranslationTarget.ZH_CN -> getString(Res.strings.translation_target_zh_cn)
        TranslationTarget.ZH_TW -> getString(Res.strings.translation_target_zh_tw)
        TranslationTarget.EN -> getString(Res.strings.translation_target_en)
        TranslationTarget.JA -> getString(Res.strings.translation_target_ja)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Toolbar(
        scrollBehavior: TopAppBarScrollBehavior
    ) {
        LargeToolbar(
            title = stringResource(Res.strings.settings_translation),
            scrollBehavior = scrollBehavior
        )
    }

}

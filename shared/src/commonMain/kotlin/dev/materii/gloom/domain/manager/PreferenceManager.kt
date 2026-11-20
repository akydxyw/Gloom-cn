package dev.materii.gloom.domain.manager

import dev.materii.gloom.domain.manager.base.BasePreferenceManager
import dev.materii.gloom.domain.manager.base.booleanPreference
import dev.materii.gloom.domain.manager.base.enumPreference
import dev.materii.gloom.domain.manager.base.intPreference
import dev.materii.gloom.domain.manager.base.stringPreference
import dev.materii.gloom.domain.manager.enums.AppIcon
import dev.materii.gloom.domain.manager.enums.AvatarShape
import dev.materii.gloom.domain.manager.enums.FreePoolMode
import dev.materii.gloom.domain.manager.enums.Theme
import dev.materii.gloom.domain.manager.enums.TranslationEngine
import dev.materii.gloom.domain.manager.enums.TranslationTarget
import dev.materii.gloom.domain.manager.enums.TrendingPeriodPreference
import dev.materii.gloom.util.SettingsProvider

class PreferenceManager(provider: SettingsProvider):
    BasePreferenceManager(provider) {

    var theme by enumPreference("theme", Theme.SYSTEM)
    var monet by booleanPreference("monet", dev.materii.gloom.util.supportsMonet)

    var userAvatarShape by enumPreference("user_avatar_shape", AvatarShape.Circle)
    var userAvatarRadius by intPreference("user_avatar_radius", 31)

    var orgAvatarShape by enumPreference("org_avatar_shape", AvatarShape.RoundedCorner)
    var orgAvatarRadius by intPreference("org_avatar_radius", 31)

    var trendingPeriod by enumPreference("trending_period", TRENDING_PERIOD)

    var appIcon by enumPreference("app_icon", AppIcon.Main)

    var translationEngine by enumPreference("translation_engine", TranslationEngine.FREE_POOL)

    var autoTranslate by booleanPreference("auto_translate", true)
    var freePoolMode by enumPreference("free_pool_mode", FreePoolMode.BALANCED)
    var freePoolDisabled by stringPreference("free_pool_disabled", "")
    var translationTarget by enumPreference("translation_target", TranslationTarget.ZH_CN)
    var translationSource by stringPreference("translation_source", "auto")

    var translateRepoName by booleanPreference("translate_repo_name", true)
    var translateRepoDescription by booleanPreference("translate_repo_description", true)
    var translateReadme by booleanPreference("translate_readme", true)
    var translateIssueTitle by booleanPreference("translate_issue_title", true)
    var translatePrTitle by booleanPreference("translate_pr_title", true)
    var translateCommits by booleanPreference("translate_commits", true)
    var translateReleases by booleanPreference("translate_releases", true)

    var deeplxEndpoint by stringPreference("deeplx_endpoint", "")
    var deeplxToken by stringPreference("deeplx_token", "")

    var openaiEndpoint by stringPreference("openai_endpoint", "")
    var openaiKey by stringPreference("openai_key", "")
    var openaiModel by stringPreference("openai_model", "")

    var mymemoryEmail by stringPreference("mymemory_email", "")

    init {
        if (userAvatarRadius > 50) userAvatarRadius = 50
        if (userAvatarRadius < 0) userAvatarRadius = 0
        if (orgAvatarRadius > 50) orgAvatarRadius = 50
        if (orgAvatarRadius < 0) orgAvatarRadius = 0
    }

    companion object Defaults {

        val TRENDING_PERIOD = TrendingPeriodPreference.DAILY

    }

}

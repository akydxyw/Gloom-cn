package dev.materii.gloom.ui.screen.release.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.compose.stringResource
import dev.materii.gloom.Res
import dev.materii.gloom.api.dto.user.User
import dev.materii.gloom.domain.manager.PreferenceManager
import dev.materii.gloom.gql.fragment.ReleaseDetails
import dev.materii.gloom.ui.component.Avatar
import dev.materii.gloom.ui.component.Label
import dev.materii.gloom.ui.component.translation.rememberTranslation
import dev.materii.gloom.ui.screen.repo.RepoScreen
import dev.materii.gloom.ui.theme.gloomColorScheme
import dev.materii.gloom.util.ifNullOrBlank
import org.koin.compose.koinInject

@Composable
fun ReleaseHeader(
    details: ReleaseDetails,
    modifier: Modifier = Modifier
) {
    val nav = LocalNavigator.currentOrThrow
    val prefs: PreferenceManager = koinInject()
    val title = details.name.ifNullOrBlank { details.tagName }
    val translatedTitle = if (prefs.autoTranslate && prefs.translateReleases) {
        rememberTranslation(title, enabled = true)
    } else {
        null
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable {
                nav.push(RepoScreen(details.repository.owner.login, details.repository.name))
            }
        ) {
            Avatar(
                url = details.repository.owner.avatarUrl,
                type = User.Type.fromTypeName(details.repository.owner.__typename),
                modifier = Modifier.size(22.dp)
            )

            Text(
                buildAnnotatedString {
                    append(details.repository.owner.login)
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(0.5f))) {
                        append(" / ")
                    }
                    append(details.repository.name)
                },
                style = MaterialTheme.typography.labelLarge
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = MaterialTheme.typography.headlineMedium,
                    overflow = TextOverflow.Ellipsis
                )

                if (translatedTitle != null && translatedTitle != title) {
                    Text(
                        text = translatedTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.65f)
                    )
                }
            }

            if (details.isLatest) {
                Label(
                    text = stringResource(Res.strings.label_latest),
                    textColor = MaterialTheme.gloomColorScheme.statusGreen
                )
            }

            if (details.isPrerelease) {
                Label(
                    text = stringResource(Res.strings.label_prerelease),
                    textColor = MaterialTheme.gloomColorScheme.statusYellow
                )
            }
        }
    }
}
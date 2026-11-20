package dev.materii.gloom.ui.screen.explore.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.materii.gloom.api.model.ModelRepo
import dev.materii.gloom.api.model.ModelUser
import dev.materii.gloom.domain.manager.PreferenceManager
import dev.materii.gloom.gql.type.IssueState
import dev.materii.gloom.gql.type.PullRequestState
import dev.materii.gloom.ui.component.Avatar
import dev.materii.gloom.ui.component.LabeledIcon
import dev.materii.gloom.ui.component.translation.rememberTranslation
import dev.materii.gloom.ui.screen.explore.viewmodel.SearchResult
import dev.materii.gloom.ui.screen.profile.ProfileScreen
import dev.materii.gloom.ui.screen.repo.RepoScreen
import dev.materii.gloom.ui.screen.repo.component.RepoItem
import dev.materii.gloom.ui.util.NavigationUtil.navigate
import dev.materii.gloom.util.NumberFormatter
import org.koin.compose.koinInject

private val StateOpen = Color(0xFF2DA44E)
private val StateClosed = Color(0xFF8250DF)
private val StateMerged = Color(0xFF8250DF)
private val StateDeclined = Color(0xFFCF222E)

/**
 * Renders a single GitHub search result regardless of its type.
 */
@Composable
fun SearchResultItem(
    item: SearchResult,
    modifier: Modifier = Modifier
) {
    val prefs: PreferenceManager = koinInject()

    when (item) {
        is SearchResult.Repo       -> RepoItem(repo = item.repo, modifier = modifier)
        is SearchResult.User       -> UserResultItem(item.user, modifier)
        is SearchResult.Issue      -> ThreadResultItem(
            icon = Icons.Filled.Circle,
            iconTint = if (item.issue.issueState == IssueState.OPEN) StateOpen else StateClosed,
            title = item.issue.title,
            subtitle = "${item.issue.repository?.nameWithOwner ?: ""} #${item.issue.number}",
            authorLogin = item.issue.author?.login,
            commentCount = item.issue.issueComments.totalCount,
            translateEnabled = prefs.translateIssueTitle,
            modifier = modifier
        )
        is SearchResult.PullRequest -> ThreadResultItem(
            icon = Icons.Outlined.MergeType,
            iconTint = when (item.pullRequest.prState) {
                PullRequestState.MERGED -> StateMerged
                PullRequestState.CLOSED -> StateDeclined
                else                    -> StateOpen
            },
            title = item.pullRequest.title,
            subtitle = "${item.pullRequest.repository?.nameWithOwner ?: ""} #${item.pullRequest.number}",
            authorLogin = item.pullRequest.author?.login,
            commentCount = item.pullRequest.prComments.totalCount,
            translateEnabled = prefs.translatePrTitle,
            modifier = modifier
        )
        is SearchResult.Discussion -> ThreadResultItem(
            icon = Icons.Outlined.Forum,
            iconTint = MaterialTheme.colorScheme.onSurface.copy(0.7f),
            title = item.discussion.title,
            subtitle = "${item.discussion.repository?.nameWithOwner ?: ""} #${item.discussion.number}",
            authorLogin = item.discussion.author?.login,
            commentCount = item.discussion.discussionComments.totalCount,
            translateEnabled = prefs.translateIssueTitle,
            modifier = modifier
        )
    }
}

@Composable
private fun UserResultItem(
    user: ModelUser,
    modifier: Modifier = Modifier
) {
    val nav = LocalNavigator.currentOrThrow

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { user.username?.let { nav.navigate(ProfileScreen(it)) } }
            .padding(16.dp)
    ) {
        Avatar(
            url = user.avatar,
            type = user.type,
            modifier = Modifier.size(40.dp)
        )

        Spacer(Modifier.width(12.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = user.username ?: "ghost",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            user.displayName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            user.bio?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        user.followers?.let {
            LabeledIcon(
                icon = Icons.Outlined.People,
                label = NumberFormatter.compact(it.toInt()),
                iconTint = MaterialTheme.colorScheme.onSurface.copy(0.6f)
            )
        }
    }
}

@Composable
private fun ThreadResultItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    authorLogin: String?,
    commentCount: Int,
    translateEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val nav = LocalNavigator.currentOrThrow
    val prefs: PreferenceManager = koinInject()
    val translatedTitle = if (translateEnabled && prefs.autoTranslate) {
        rememberTranslation(title, enabled = true)
    } else {
        null
    }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val parts = subtitle.substringBefore(" #").split('/')
                if (parts.size == 2) nav.navigate(RepoScreen(parts[0], parts[1]))
            }
            .padding(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(12.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title.ifBlank { subtitle },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (translatedTitle != null && translatedTitle != title) {
                Text(
                    text = translatedTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                authorLogin?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                LabeledIcon(
                    icon = Icons.Outlined.Comment,
                    label = NumberFormatter.compact(commentCount),
                    iconTint = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                )
            }
        }
    }
}

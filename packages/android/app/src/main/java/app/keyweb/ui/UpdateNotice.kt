package app.keyweb.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.keyweb.update.AppUpdateInfo
import app.keyweb.update.ReleaseUpdateChecker

/**
 * "A newer Keyweb is ready", when there is one.
 *
 * Renders nothing at all the rest of the time, which is almost always. An
 * update notice that occupies space while saying "you are up to date" trains
 * people to look past exactly the spot where the real message will appear.
 *
 * The check runs once per visit to this screen and fails silently: offline, or
 * GitHub unreachable, means no update, because there is nothing useful to say
 * about it to someone who opened a password manager.
 */
@Composable
fun UpdateNotice(
    currentVersion: String,
    onGet: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val checker = remember(context) { ReleaseUpdateChecker(context.applicationContext) }
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }

    LaunchedEffect(currentVersion) {
        update = checker.checkForUpdate(currentVersion)
    }

    val info = update ?: return

    StatusLine(
        Tone.ATTENTION,
        "Keyweb ${info.version} is ready to install.",
        // No changelog here on purpose: it would have to be fetched and
        // trusted, and "what changed" belongs on the page this opens.
        "You have $currentVersion. Getting it opens your browser — " +
            "your passwords stay where they are.",
        modifier,
        actionLabel = "Get the new version",
        onAction = { onGet(info.downloadUrl) },
    )

    // Deliberately quieter than the action, and deliberately present: a notice
    // that cannot be put away is a notice people learn to ignore.
    TextButton(onClick = {
        checker.dismiss(info.version)
        update = null
    }) {
        Text("Not now")
    }
}

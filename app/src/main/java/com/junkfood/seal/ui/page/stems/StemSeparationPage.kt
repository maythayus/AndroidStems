package com.junkfood.seal.ui.page.stems

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.R
import com.junkfood.seal.stems.StemModel
import com.junkfood.seal.stems.StemSeparation
import com.junkfood.seal.ui.component.FilledTonalButtonWithIcon
import com.junkfood.seal.ui.component.OutlinedButtonWithIcon
import com.junkfood.seal.ui.component.PreferenceInfo
import com.junkfood.seal.ui.page.settings.BasePreferencePage
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.makeToast

@Composable
fun StemSeparationPage(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val input by StemSeparation.input.collectAsStateWithLifecycle()
    val state by StemSeparation.state.collectAsStateWithLifecycle()
    val running = state is StemSeparation.State.Running

    // L'écran reste allumé pendant le calcul (quelques minutes pour un morceau).
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) StemSeparation.select(context, uri)
        }

    BasePreferencePage(title = stringResource(R.string.stems_title), onBack = onNavigateBack) {
        LazyColumn(contentPadding = it) {
            item {
                OutlinedCard(modifier = Modifier.padding(16.dp)) {
                    PreferenceInfo(
                        text =
                            stringResource(R.string.stems_intro) +
                                if (!StemModel.isInstalled(context))
                                    "\n\n" +
                                        stringResource(
                                            R.string.stems_model_hint,
                                            StemModel.SIZE_BYTES / 1_000_000,
                                        )
                                else ""
                    )
                }
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AudioFile, null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = input?.name ?: stringResource(R.string.stems_no_file),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            OutlinedButtonWithIcon(
                                onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                                icon = Icons.Outlined.AudioFile,
                                text = stringResource(R.string.stems_pick_file),
                            )
                            if (running) {
                                FilledTonalButtonWithIcon(
                                    onClick = { StemSeparation.cancel() },
                                    icon = Icons.Outlined.Close,
                                    text = stringResource(R.string.cancel),
                                )
                            } else {
                                Button(
                                    onClick = { StemSeparation.start(context) },
                                    enabled = input != null,
                                ) {
                                    Icon(Icons.Outlined.GraphicEq, null)
                                    Text(
                                        stringResource(R.string.stems_start),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            when (val s = state) {
                is StemSeparation.State.Running ->
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text =
                                    stringResource(
                                        when (s.step) {
                                            StemSeparation.Step.DownloadModel ->
                                                R.string.stems_step_download
                                            StemSeparation.Step.Decode -> R.string.stems_step_decode
                                            StemSeparation.Step.Separate ->
                                                R.string.stems_step_separate
                                        }
                                    ) + " — ${(s.progress * 100).toInt()} %",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                is StemSeparation.State.Failed ->
                    item {
                        PreferenceInfo(
                            text = stringResource(R.string.stems_failed, s.message),
                            icon = Icons.Outlined.ErrorOutline,
                        )
                    }

                is StemSeparation.State.Done -> {
                    item {
                        PreferenceInfo(
                            text = stringResource(R.string.stems_done, s.seconds),
                            icon = Icons.Outlined.GraphicEq,
                        )
                    }
                    items(s.outputs) { output ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(output.labelRes),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    output.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    val shareTitle = stringResource(R.string.share)
                                    val unavailable = stringResource(R.string.file_unavailable)
                                    OutlinedButtonWithIcon(
                                        onClick = {
                                            FileUtil.createIntentForSharingFile(output.location)
                                                ?.runCatching {
                                                    context.startActivity(
                                                        Intent.createChooser(this, shareTitle)
                                                    )
                                                }
                                        },
                                        icon = Icons.Outlined.Share,
                                        text = shareTitle,
                                    )
                                    FilledTonalButtonWithIcon(
                                        onClick = {
                                            FileUtil.openFile(output.location) {
                                                context.makeToast(unavailable)
                                            }
                                        },
                                        icon = Icons.Outlined.PlayArrow,
                                        text = stringResource(R.string.stems_play),
                                    )
                                }
                            }
                        }
                    }
                }

                StemSeparation.State.Idle -> {}
            }

            item {
                Text(
                    text = stringResource(R.string.stems_credits, StemModel.NAME),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

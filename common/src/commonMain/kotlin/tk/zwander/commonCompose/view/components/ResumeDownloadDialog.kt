package tk.zwander.commonCompose.view.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import dev.zwander.compose.alertdialog.InWindowAlertDialog
import tk.zwander.common.data.DownloadState
import tk.zwander.samloaderkotlin.resources.MR

@Composable
fun ResumeDownloadDialog(
    modifier: Modifier = Modifier,
    showing: Boolean,
    incompleteDownloads: List<DownloadState>,
    onDismissRequest: () -> Unit,
    onResumeAll: () -> Unit,
    onResumeDownload: (DownloadState) -> Unit,
) {
    InWindowAlertDialog(
        modifier = modifier,
        showing = showing,
        title = {
            Text(text = stringResource(MR.strings.resumeDownloadTitle))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.resumeDownloadMessage, incompleteDownloads.size),
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (incompleteDownloads.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        incompleteDownloads.forEach { state ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onResumeDownload(state)
                                },
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                ) {
                                    Text(
                                        text = state.fileName,
                                        style = MaterialTheme.typography.titleMedium,
                                    )

                                    Text(
                                        text = stringResource(
                                            MR.strings.resumeDownloadItem,
                                            state.fileName,
                                            state.chunks.count { it.status == tk.zwander.common.data.ChunkStatus.COMPLETED },
                                            state.chunks.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    Text(
                                        text = "${((state.downloadedBytes.toFloat() / state.fileSize * 100).toInt())}% completed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        buttons = {
            TextButton(
                onClick = onDismissRequest,
            ) {
                Text(text = stringResource(MR.strings.skipAll))
            }

            TextButton(
                onClick = onResumeAll,
                enabled = incompleteDownloads.isNotEmpty(),
            ) {
                Text(
                    text = stringResource(MR.strings.resume),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        onDismissRequest = onDismissRequest,
        contentsScrollable = true,
    )
}
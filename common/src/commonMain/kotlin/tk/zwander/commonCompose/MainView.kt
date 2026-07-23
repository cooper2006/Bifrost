package tk.zwander.commonCompose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import dev.zwander.kmp.platform.HostOS
import kotlinx.coroutines.launch
import tk.zwander.common.data.DownloadState as BifrostDownloadState
import tk.zwander.common.tools.AuthParamsHandler
import tk.zwander.common.tools.delegates.Downloader
import tk.zwander.common.util.DownloadStateManager
import tk.zwander.common.util.ketch
import tk.zwander.commonCompose.locals.LocalDownloadModel
import tk.zwander.commonCompose.view.LocalPagerState
import tk.zwander.commonCompose.view.LocalUseTransparencyEffects
import tk.zwander.commonCompose.view.components.BifrostTheme
import tk.zwander.commonCompose.view.components.ResumeDownloadDialog
import tk.zwander.commonCompose.view.components.TabView
import tk.zwander.commonCompose.view.components.pages
import kotlin.time.ExperimentalTime

/**
 * The main UI view.
 */
@ExperimentalTime
@Composable
fun MainView(
    modifier: Modifier = Modifier,
    fullPadding: PaddingValues = PaddingValues(),
) {
    val scope = rememberCoroutineScope()

    val pagerState = LocalPagerState.current
    val downloadModel = LocalDownloadModel.current

    var showResumeDialog by remember { mutableStateOf(false) }
    var incompleteDownloads by remember { mutableStateOf<List<BifrostDownloadState>>(emptyList()) }

    LaunchedEffect(null) {
        ketch.start()
        ketch.tasks.value.forEach {
            if (it.state.value is DownloadState.Completed) {
                it.remove()
            }
        }

        AuthParamsHandler.extractFile()

        val incomplete = DownloadStateManager.getIncompleteDownloads()
        if (incomplete.isNotEmpty()) {
            incompleteDownloads = incomplete
            showResumeDialog = true
        }
    }

    BifrostTheme {
        val useTransparency = LocalUseTransparencyEffects.current

        Surface(
            color = if (useTransparency) Color.Transparent else MaterialTheme.colorScheme.surface,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (useTransparency) MaterialTheme.colorScheme.onBackground else LocalContentColor.current,
            ) {
                Column(
                    modifier = modifier.fillMaxSize()
                        .padding(fullPadding),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 1200.dp)
                            .align(Alignment.CenterHorizontally)
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            pageSpacing = 8.dp,
                            userScrollEnabled = HostOS.current == HostOS.Android || HostOS.current == HostOS.IOS,
                            beyondViewportPageCount = pagerState.pageCount,
                        ) {
                            pages[it].render()
                        }
                    }

                    TabView(
                        selectedPage = pagerState.currentPage,
                        onPageSelected = {
                            scope.launch {
                                pagerState.animateScrollToPage(it)
                            }
                        },
                    )
                }
            }
        }
    }

    ResumeDownloadDialog(
        showing = showResumeDialog,
        incompleteDownloads = incompleteDownloads,
        onDismissRequest = {
            showResumeDialog = false
        },
        onResumeAll = {
            showResumeDialog = false
            // Resume all incomplete downloads one by one
            scope.launch {
                incompleteDownloads.forEach { state ->
                    downloadModel.model.value = state.model
                    downloadModel.region.value = state.region
                    downloadModel.fw.value = state.fw
                    
                    val firmwareId = "${state.model}_${state.region}_${state.fw}".replace("/", "_")
                    DownloadStateManager.deleteState(firmwareId)
                    
                    downloadModel.launchJob {
                        Downloader.onDownload(
                            downloadModel,
                            confirmCallback = object : Downloader.DownloadErrorCallback {
                                override fun onError(info: Downloader.DownloadErrorInfo) {
                                    // Handle error silently for batch resume
                                }
                            },
                        )
                    }
                }
            }
        },
        onResumeDownload = { state ->
            showResumeDialog = false
            // Resume single download
            scope.launch {
                downloadModel.model.value = state.model
                downloadModel.region.value = state.region
                downloadModel.fw.value = state.fw
                
                val firmwareId = "${state.model}_${state.region}_${state.fw}".replace("/", "_")
                DownloadStateManager.deleteState(firmwareId)
                
                downloadModel.launchJob {
                    Downloader.onDownload(
                        downloadModel,
                        confirmCallback = object : Downloader.DownloadErrorCallback {
                            override fun onError(info: Downloader.DownloadErrorInfo) {
                                // Handle error
                            }
                        },
                    )
                }
            }
        },
    )
}

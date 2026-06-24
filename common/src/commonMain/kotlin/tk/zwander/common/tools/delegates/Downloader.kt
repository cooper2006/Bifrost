package tk.zwander.common.tools.delegates

import com.linroid.ketch.api.KetchError
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.zwander.common.data.BinaryFileInfo
import tk.zwander.common.tools.CryptUtils
import tk.zwander.common.tools.FusClient
import tk.zwander.common.tools.Request
import tk.zwander.common.tools.VersionFetch
import tk.zwander.common.util.BifrostSettings
import tk.zwander.common.util.ChangelogHandler
import tk.zwander.common.util.Event
import tk.zwander.common.util.FileManager
import tk.zwander.common.util.eventManager
import tk.zwander.common.util.invoke
import tk.zwander.common.util.ketch
import tk.zwander.common.util.streamOperationWithProgress
import tk.zwander.commonCompose.model.DownloadModel
import tk.zwander.samloaderkotlin.resources.MR
import kotlin.time.ExperimentalTime

object Downloader {
    interface DownloadErrorCallback {
        fun onError(info: DownloadErrorInfo)
    }

    data class DownloadErrorInfo(
        val message: String,
        val callback: DownloadErrorConfirmCallback,
    )

    data class DownloadErrorConfirmCallback(
        val onAccept: suspend () -> Unit,
        val onCancel: suspend () -> Unit,
    )

    suspend fun onDownload(
        model: DownloadModel,
        confirmCallback: DownloadErrorCallback,
    ) {
        eventManager.sendEvent(Event.Download.Start)
        model.statusText.value = MR.strings.downloading()

        val info = Request.retrieveBinaryFileInfo(
            fw = model.fw.value,
            model = model.model.value,
            region = model.region.value,
            onVersionException = { exception, info ->
                confirmCallback.onError(
                    info = DownloadErrorInfo(
                        message = exception.message!!,
                        callback = DownloadErrorConfirmCallback(
                            onAccept = {
                                performDownload(info!!, model)
                            },
                            onCancel = {
                                model.endJob("")
                                eventManager.sendEvent(Event.Download.Finish)
                            },
                        )
                    ),
                )
            },
            onFinish = {
                model.endJob(it)
                eventManager.sendEvent(Event.Download.Finish)
            },
            shouldReportError = {
                !model.manual.value
            },
            imeiSerial = "",
        )

        if (info != null) {
            performDownload(info, model)
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun performDownload(info: BinaryFileInfo, model: DownloadModel) {
        val (path, fileName, size, crc32, v4Key, fwVer, modelType) = info

        val fullFileName = fileName.replace(
            ".zip",
            "_${model.fw.value.replace("/", "_")}_${model.region.value}.zip",
        ).substringAfterLast("/")

        val decryptionKeyFileName = if (BifrostSettings.Keys.enableDecryptKeySave()) {
            "DecryptionKey_${fullFileName}.txt"
        } else {
            null
        }

        val downloadDirectory = FileManager.pickDirectory()
        val tempDirectory = FileManager.getTempDirectory()

        val encFile = (tempDirectory ?: downloadDirectory)?.child(fullFileName, false) ?: run {
            model.endJob("")
            eventManager.sendEvent(Event.Download.Finish)
            return
        }
        val extractedEncFile = downloadDirectory?.child(fullFileName, false) ?: run {
            model.endJob("")
            eventManager.sendEvent(Event.Download.Finish)
            return
        }
        val decFile = downloadDirectory.child(
            fullFileName.replace(".enc2", "")
                .replace(".enc4", ""),
            false,
        )
        val decKeyFile = downloadDirectory.let { dir ->
            decryptionKeyFileName?.let { dec ->
                dir.child(dec, false)
            }
        }

        // Track temporary files for cleanup
        model.addTempFile(encFile)
        model.addTempFile(extractedEncFile)
        model.addTempFile(decFile)
        model.addTempFile(decKeyFile)

        decKeyFile?.openOutputStream(false)?.use { output ->
            if (fullFileName.endsWith(".enc2")) {
                output.write(
                    CryptUtils.getV2Key(
                        model.fw.value,
                        model.model.value,
                        model.region.value,
                    ).second.toByteArray(),
                )
            }

            v4Key?.let {
                output.write(v4Key.second.toByteArray())
            }
        }

        // The FUS nonce can become invalid between BinaryInit and the actual
        // file download (random 401). When that happens we regenerate the
        // nonce and re-run both steps so the init and download share the same
        // session, bounded to prevent an infinite loop.
        val maxInitRetries = 3
        var initRetries = 0
        var md5: String? = null

        try {
            while (initRetries <= maxInitRetries) {
                if (initRetries > 0) {
                    FusClient.refreshNonce()
                }

                val request = Request.createBinaryInit(
                    fileName,
                    FusClient.getNonce(),
                    fwVer,
                    modelType,
                    model.region.value,
                )
                FusClient.makeReq(FusClient.Request.BINARY_INIT, request)

                try {
                    md5 = if (extractedEncFile.getLength() < size) {
                        FusClient.downloadFile(
                            fileName = path + fileName,
                            start = encFile.getLength(),
                            size = size,
                            dest = encFile,
                        ) { current, max, bps ->
                            // Check for pause
                            while (model.isPaused.value) {
                                kotlinx.coroutines.delay(100)
                            }

                            model.progress.value = current to max
                            model.speed.value = bps

                            eventManager.sendEvent(
                                Event.Download.Progress(
                                    status = MR.strings.downloading(),
                                    current = current,
                                    max = max,
                                )
                            )
                        }
                    } else {
                        null
                    }
                    break // download succeeded
                } catch (e: KetchError) {
                    val isAuth = when (e) {
                        is KetchError.Http -> e.code == 401
                        is KetchError.AuthenticationFailed -> true
                        else -> false
                    }
                    if (isAuth && initRetries < maxInitRetries) {
                        initRetries++
                        // Drop the failed ketch task so the next attempt
                        // starts clean with the refreshed nonce.
                        ketch.tasks.value
                            .find {
                                it.request.url.contains("NF_SmartDownloadBinaryForMass.do")
                            }?.remove()
                        continue
                    }
                    throw e
                }
            }

            if (crc32 != null) {
                model.speed.value = 0L
                model.statusText.value = MR.strings.checkingCRC()
                val result = CryptUtils.checkCrc32(
                    encFile.openInputStream() ?: return,
                    encFile.getLength(),
                    crc32,
                ) { current, max, bps ->
                    // Check for pause
                    while (model.isPaused.value) {
                        kotlinx.coroutines.delay(100)
                    }

                    model.progress.value = current to max
                    model.speed.value = bps

                    eventManager.sendEvent(
                        Event.Download.Progress(
                            status = MR.strings.checkingCRC(),
                            current = current,
                            max = max,
                        )
                    )
                }

                if (!result) {
                    model.endJob(MR.strings.crcCheckFailed())
                    return
                }
            }

            if (md5 != null) {
                model.speed.value = 0L
                model.statusText.value = MR.strings.checkingMD5()

                eventManager.sendEvent(
                    Event.Download.Progress(
                        status = MR.strings.checkingMD5(),
                        current = 0,
                        max = 1,
                    )
                )

                val result = withContext(Dispatchers.Default) {
                    CryptUtils.checkMD5(
                        md5,
                        encFile.openInputStream(),
                    )
                }

                if (!result) {
                    model.endJob(MR.strings.md5CheckFailed())
                    return
                }
            }

            if (tempDirectory != null && tempDirectory != downloadDirectory && extractedEncFile.getLength() < size) {
                model.speed.value = 0L
                model.statusText.value = "Copying"

                val input = encFile.openInputStream() ?: run {
                    model.endJob("")
                    return
                }
                val output = extractedEncFile.openOutputStream() ?: run {
                    model.endJob("")
                    return
                }

                try {
                    streamOperationWithProgress(
                        input = input,
                        output = output,
                        size = encFile.getLength(),
                        progressCallback = { current, max, bps ->
                            // Check for pause
                            while (model.isPaused.value) {
                                kotlinx.coroutines.delay(100)
                            }

                            model.progress.value = current to max
                            model.speed.value = bps

                            eventManager.sendEvent(
                                Event.Download.Progress(
                                    status = "Copying",
                                    current = current,
                                    max = max,
                                )
                            )
                        },
                    )
                } finally {
                    input.close()
                    output.close()
                    encFile.delete()
                }
            }

            model.speed.value = 0L
            model.statusText.value = MR.strings.decrypting()

            val key =
                if (fullFileName.endsWith(".enc2")) {
                    CryptUtils.getV2Key(
                        model.fw.value,
                        model.model.value,
                        model.region.value,
                    ).first
                } else {
                    info.v4Key?.first!!
                }

            CryptUtils.decryptProgress(
                extractedEncFile.openInputStream() ?: return,
                decFile?.openOutputStream() ?: return,
                key,
                size,
            ) { current, max, bps ->
                // Check for pause
                while (model.isPaused.value) {
                    kotlinx.coroutines.delay(100)
                }

                model.progress.value = current to max
                model.speed.value = bps

                eventManager.sendEvent(
                    Event.Download.Progress(
                        status = MR.strings.decrypting(),
                        current = current,
                        max = max,
                    )
                )
            }

            if (BifrostSettings.Keys.autoDeleteEncryptedFirmware()) {
                encFile.delete()
                extractedEncFile.delete()
            }

            model.endJob(MR.strings.done())
        } catch (e: Throwable) {
            val message = if (e !is CancellationException) "${e.message}" else ""
            model.endJob(message)
        }

        eventManager.sendEvent(Event.Download.Finish)
    }

    suspend fun onFetch(model: DownloadModel) {
        model.statusText.value = ""
        model.changelog.value = null
        model.osCode.value = ""

        val (fw, os, error, output) = VersionFetch.hybridGetLatestVersion(
            model.model.value,
            model.region.value,
        )

        if (error != null) {
            model.endJob(
                MR.strings.firmwareCheckError(
                    error.message.toString(),
                    output.replace("\t", "  ")
                )
            )
            return
        }

        model.changelog.value = ChangelogHandler.getChangelog(
            model.model.value,
            model.region.value,
            fw.split("/")[0],
        )

        model.fw.value = fw
        model.osCode.value = os

        model.endJob("")
    }
}
package tk.zwander.common.tools.delegates

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
        println("[BifrostDownload] onDownload start: model=${model.model.value}, fw=${model.fw.value}, region=${model.region.value}")
        eventManager.sendEvent(Event.Download.Start)
        model.statusText.value = MR.strings.downloading()

        val info = Request.retrieveBinaryFileInfo(
            fw = model.fw.value,
            model = model.model.value,
            region = model.region.value,
            onVersionException = { exception, info ->
                println("[BifrostDownload] onDownload version exception: ${exception.message}")
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
                println("[BifrostDownload] onDownload retrieveBinaryFileInfo onFinish: ${it.take(80)}")
                model.endJob(it)
                eventManager.sendEvent(Event.Download.Finish)
            },
            shouldReportError = {
                !model.manual.value
            },
            imeiSerial = "",
        )

        if (info != null) {
            println("[BifrostDownload] onDownload: retrieved file info, fileName=${info.fileName}, size=${info.size}")
            performDownload(info, model)
        } else {
            println("[BifrostDownload] onDownload: no file info returned")
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun performDownload(info: BinaryFileInfo, model: DownloadModel) {
        val (path, fileName, size, crc32, v4Key, fwVer, modelType) = info
        println("[BifrostDownload] performDownload start: path=$path, fileName=$fileName, size=${size}bytes, crc32=$crc32, hasV4Key=${v4Key != null}, fwVer=$fwVer, modelType=$modelType")

        val fullFileName = fileName.replace(
            ".zip",
            "_${model.fw.value.replace("/", "_")}_${model.region.value}.zip",
        ).substringAfterLast("/")
        println("[BifrostDownload] performDownload: fullFileName=$fullFileName")

        val decryptionKeyFileName = if (BifrostSettings.Keys.enableDecryptKeySave()) {
            "DecryptionKey_${fullFileName}.txt"
        } else {
            null
        }

        val downloadDirectory = FileManager.pickDirectory()
        val tempDirectory = FileManager.getTempDirectory()
        println("[BifrostDownload] performDownload: downloadDir=${downloadDirectory?.getAbsolutePath()}, tempDir=${tempDirectory?.getAbsolutePath()}")

        if (downloadDirectory == null) {
            println("[BifrostDownload] performDownload: downloadDirectory null, aborting")
            model.endJob("")
            eventManager.sendEvent(Event.Download.Finish)
            return
        }

        val encFile = (tempDirectory ?: downloadDirectory)?.child(fullFileName, false) ?: run {
            println("[BifrostDownload] performDownload: encFile null, aborting")
            model.endJob("")
            eventManager.sendEvent(Event.Download.Finish)
            return
        }
        val extractedEncFile = downloadDirectory?.child(fullFileName, false) ?: run {
            println("[BifrostDownload] performDownload: extractedEncFile null, aborting")
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
        println("[BifrostDownload] performDownload: encFile=${encFile.getAbsolutePath()}, decFile=${decFile?.getAbsolutePath()}")

        // Track temporary files for cleanup
        model.addTempFile(encFile)
        model.addTempFile(extractedEncFile)
        model.addTempFile(decFile)
        model.addTempFile(decKeyFile)

        if (decKeyFile != null) {
            println("[BifrostDownload] performDownload: writing decryption key file")
        }
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
        // Also handles socket timeouts for large files (17GB+) by resuming
        // from the current file offset.
        val maxInitRetries = 10
        var initRetries = 0
        var md5: String? = null
        println("[BifrostDownload] performDownload: entering BinaryInit+download retry loop (max $maxInitRetries)")

        try {
            while (initRetries <= maxInitRetries) {
                if (initRetries > 0) {
                    println("[BifrostDownload] performDownload: retry #$initRetries, refreshing nonce")
                    FusClient.refreshNonce()
                }

                val request = Request.createBinaryInit(
                    fileName,
                    FusClient.getNonce(),
                    fwVer,
                    modelType,
                    model.region.value,
                )
                println("[BifrostDownload] performDownload: sending BinaryInit request (attempt ${initRetries + 1})")
                FusClient.makeReq(FusClient.Request.BINARY_INIT, request)
                println("[BifrostDownload] performDownload: BinaryInit response received")

                try {
                    val existingLen = extractedEncFile.getLength()
                    println("[BifrostDownload] performDownload: existing enc len=$existingLen, target size=$size, willDownload=${existingLen < size}")
                    md5 = if (existingLen < size) {
                        FusClient.downloadFile(
                            fileName = path + fileName,
                            start = encFile.getLength(),
                            size = size,
                            dest = encFile,
                            onAuthRefresh = {
                                println("[BifrostDownload] performDownload: onAuthRefresh, re-sending BinaryInit")
                                val initRequest = Request.createBinaryInit(
                                    fileName,
                                    FusClient.getNonce(),
                                    fwVer,
                                    modelType,
                                    model.region.value,
                                )
                                FusClient.makeReq(FusClient.Request.BINARY_INIT, initRequest)
                            },
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
                        println("[BifrostDownload] performDownload: file already complete, skipping download")
                        null
                    }
                    println("[BifrostDownload] performDownload: download phase complete, md5=$md5")
                    break // download succeeded
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val isAuth = e.message?.contains("401") == true
                    val isTimeout = e is java.net.SocketTimeoutException ||
                        e.message?.contains("timeout") == true ||
                        e.message?.contains("SocketTimeout") == true
                    val isConnectionClosed = e.javaClass.simpleName.contains("ClosedByteChannel") ||
                        e.message?.contains("closed") == true ||
                        e.message?.contains("Closed") == true ||
                        e is java.io.IOException
                    println("[BifrostDownload] performDownload: download error: ${e.javaClass.simpleName}: ${e.message}, isAuth=$isAuth, isTimeout=$isTimeout, isConnectionClosed=$isConnectionClosed")
                    if ((isAuth || isTimeout || isConnectionClosed) && initRetries < maxInitRetries) {
                        initRetries++
                        if (isAuth) {
                            println("[BifrostDownload] performDownload: auth failure, will retry ($initRetries/$maxInitRetries)")
                        } else {
                            println("[BifrostDownload] performDownload: connection lost, will retry from offset ($initRetries/$maxInitRetries)")
                        }
                        continue
                    }
                    throw e
                }
            }

            if (crc32 != null) {
                println("[BifrostDownload] performDownload: starting CRC32 check, expected=$crc32")
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
                println("[BifrostDownload] performDownload: CRC32 result=$result")

                if (!result) {
                    println("[BifrostDownload] performDownload: CRC32 check FAILED")
                    model.endJob(MR.strings.crcCheckFailed())
                    return
                }
            } else {
                println("[BifrostDownload] performDownload: no CRC32 provided, skipping")
            }

            if (md5 != null) {
                println("[BifrostDownload] performDownload: starting MD5 check, expected=$md5")
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
                println("[BifrostDownload] performDownload: MD5 result=$result")

                if (!result) {
                    println("[BifrostDownload] performDownload: MD5 check FAILED")
                    model.endJob(MR.strings.md5CheckFailed())
                    return
                }
            } else {
                println("[BifrostDownload] performDownload: no MD5 provided, skipping")
            }

            if (tempDirectory != null && tempDirectory != downloadDirectory && extractedEncFile.getLength() < size) {
                println("[BifrostDownload] performDownload: copying temp file to download dir")
                model.speed.value = 0L
                model.statusText.value = "Copying"

                val input = encFile.openInputStream() ?: run {
                    println("[BifrostDownload] performDownload: copy input stream null, aborting")
                    model.endJob("")
                    return
                }
                val output = extractedEncFile.openOutputStream() ?: run {
                    println("[BifrostDownload] performDownload: copy output stream null, aborting")
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
                    println("[BifrostDownload] performDownload: copy complete")
                } finally {
                    input.close()
                    output.close()
                    encFile.delete()
                    println("[BifrostDownload] performDownload: temp enc file deleted after copy")
                }
            }

            println("[BifrostDownload] performDownload: starting decryption")
            model.speed.value = 0L
            model.statusText.value = MR.strings.decrypting()

            val key = if (fullFileName.endsWith(".enc2")) {
                println("[BifrostDownload] performDownload: using V2 key (.enc2)")
                CryptUtils.getV2Key(
                    model.fw.value,
                    model.model.value,
                    model.region.value,
                ).first
            } else if (info.v4Key != null) {
                println("[BifrostDownload] performDownload: using V4 key (.enc4)")
                info.v4Key.first
            } else {
                println("[BifrostDownload] performDownload: no key available, aborting")
                model.endJob("")
                return
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
            println("[BifrostDownload] performDownload: decryption complete")

            if (BifrostSettings.Keys.autoDeleteEncryptedFirmware()) {
                println("[BifrostDownload] performDownload: auto-deleting encrypted files")
                encFile.delete()
                extractedEncFile.delete()
            }

            println("[BifrostDownload] performDownload: DONE")
            model.endJob(MR.strings.done())
        } catch (e: Throwable) {
            println("[BifrostDownload] performDownload: FAILED: ${e.javaClass.simpleName}: ${e.message}")
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

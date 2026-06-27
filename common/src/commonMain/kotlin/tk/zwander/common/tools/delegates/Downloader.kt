package tk.zwander.common.tools.delegates

import com.linroid.ketch.api.KetchError
import dev.zwander.kotlin.file.IPlatformFile
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import tk.zwander.common.data.BinaryFileInfo
import tk.zwander.common.tools.CryptUtils
import tk.zwander.common.tools.FusClient
import tk.zwander.common.tools.Request
import tk.zwander.common.tools.VersionFetch
import tk.zwander.common.util.BifrostSettings
import tk.zwander.common.util.ChangelogHandler
import tk.zwander.common.util.DownloadStateManager
import tk.zwander.common.util.Event
import tk.zwander.common.util.FileManager
import tk.zwander.common.util.eventManager
import tk.zwander.common.util.invoke
import tk.zwander.common.util.ketch
import tk.zwander.common.util.streamOperationWithProgress
import tk.zwander.commonCompose.model.DownloadModel
import tk.zwander.samloaderkotlin.resources.MR
import kotlin.time.ExperimentalTime

private val logger = LoggerFactory.getLogger("Downloader")

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
        logger.debug("performDownload called, info = $info")
        
        val (path, fileName, size, crc32, v4Key, fwVer, modelType) = info
        
        logger.debug("path = $path, fileName = $fileName, size = $size, crc32 = $crc32, modelType = $modelType")
        logger.debug("fwVer = $fwVer")

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
        // Use download directory for temp files as well to avoid path issues
        val tempDirectory = downloadDirectory
        
        logger.debug("downloadDirectory = $downloadDirectory, tempDirectory = $tempDirectory")
        logger.debug("About to create files")
        
        val files = createDownloadFiles(downloadDirectory, fullFileName)
            ?: return
        
        val (encFile, extractedEncFile, decFile) = files
        val decKeyFile = downloadDirectory?.let { dir ->
            decryptionKeyFileName?.let { dec ->
                dir.child(dec, false)
            }
        }

        // Track temporary files for cleanup
        model.addTempFile(encFile)
        model.addTempFile(extractedEncFile)
        model.addTempFile(decFile)
        model.addTempFile(decKeyFile)

        logger.debug("decKeyFile = $decKeyFile")
        writeDecryptionKey(decKeyFile, fullFileName, v4Key, model)

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
                    val firmwareId = "${model.model.value}_${model.region.value}_${model.fw.value}"
                        .replace("/", "_")

                    md5 = handleFileDownload(
                        encFile = encFile,
                        extractedEncFile = extractedEncFile,
                        downloadDirectory = downloadDirectory,
                        path = path,
                        fileName = fileName,
                        size = size,
                        crc32 = crc32,
                        v4Key = v4Key,
                        firmwareId = firmwareId,
                        model = model,
                    )
                    break // download succeeded
                } catch (e: KetchError) {
                    val isAuth = when (e) {
                        is KetchError.Http -> e.code == 401
                        is KetchError.AuthenticationFailed -> true
                        else -> false
                    }
                    if (isAuth && initRetries < maxInitRetries) {
                        initRetries++
                        ketch.tasks.value
                            .find {
                                it.request.url.contains("NF_SmartDownloadBinaryForMass.do")
                            }?.remove()
                        continue
                    }
                    throw e
                } catch (e: Exception) {
                    val isAuth = e.message?.contains("401") == true
                    if (isAuth && initRetries < maxInitRetries) {
                        initRetries++
                        continue
                    }
                    throw e
                }
            }

            if (crc32 != null) {
                model.speed.value = 0L
                model.statusText.value = MR.strings.checkingCRC()
                logger.debug("Starting final CRC32 check, file size: ${encFile.getLength()}, expected CRC32: $crc32")
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
                        logger.error("Final CRC32 check FAILED!")
                        model.endJob(MR.strings.crcCheckFailed())
                        return
                    } else {
                        logger.debug("Final CRC32 check PASSED!")
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

            if (BifrostSettings.Keys.autoDecryptFirmware()) {
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
                        info.v4Key?.first ?: run {
                            model.endJob(MR.strings.decryptError("Missing decryption key (v4Key is null)"))
                            eventManager.sendEvent(Event.Download.Finish)
                            return
                        }
                    }

                CryptUtils.decryptProgress(
                    extractedEncFile.openInputStream() ?: return,
                    decFile.openOutputStream() ?: return,
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

                if (BifrostSettings.Keys.autoDeleteEncryptedFirmware() && BifrostSettings.Keys.autoDecryptFirmware()) {
                    encFile.delete()
                    extractedEncFile.delete()
                }
            }

            model.endJob(MR.strings.done())
        } catch (e: Throwable) {
            val message = if (e !is CancellationException) {
                logger.error("Download error: ${e.message}")
                logger.debug("Stack trace:", e)
                (e.message ?: e.toString())
            } else ""
            model.endJob(message)
        }

        eventManager.sendEvent(Event.Download.Finish)
    }

    /**
     * Create download file objects
     */
    private fun createDownloadFiles(
        downloadDirectory: IPlatformFile?,
        fullFileName: String,
    ): Triple<IPlatformFile, IPlatformFile, IPlatformFile>? {
        return try {
            logger.debug("About to create encFile...")
            val encFile = downloadDirectory?.child(fullFileName, false)
            logger.debug("encFile created: $encFile")
            
            logger.debug("About to create extractedEncFile...")
            val extractedEncFile = downloadDirectory?.child(fullFileName, false)
            logger.debug("extractedEncFile created: $extractedEncFile")
            
            logger.debug("About to create decFile...")
            val decFile = downloadDirectory?.child(
                fullFileName.replace(".enc2", "")
                    .replace(".enc4", ""),
                false,
            )
            logger.debug("decFile created: $decFile")

            if (encFile == null || extractedEncFile == null || decFile == null) {
                logger.error("One or more files are null: encFile=$encFile, extractedEncFile=$extractedEncFile, decFile=$decFile")
                return null
            }

            logger.debug("encFile = $encFile, extractedEncFile = $extractedEncFile, decFile = $decFile")
            Triple(encFile, extractedEncFile, decFile)
        } catch (e: Exception) {
            logger.error("Exception during file creation")
            logger.debug("Stack trace:", e)
            null
        }
    }

    /**
     * Write decryption key to file
     */
    private fun writeDecryptionKey(
        decKeyFile: IPlatformFile?,
        fullFileName: String,
        v4Key: Pair<ByteArray, String>?,
        model: DownloadModel,
    ) {
        logger.debug("decKeyFile = $decKeyFile")
        decKeyFile?.let { keyFile ->
            keyFile.openOutputStream(false)?.use { output ->
                logger.debug("Writing decryption key")
                if (fullFileName.endsWith(".enc2")) {
                    val key = CryptUtils.getV2Key(
                        model.fw.value,
                        model.model.value,
                        model.region.value,
                    ).second
                    logger.debug("V2 key length: ${key.length}")
                    output.write(key.toByteArray())
                }

                v4Key?.let {
                    logger.debug("V4 key length: ${it.second.length}")
                    output.write(it.second.toByteArray())
                }
                logger.debug("Decryption key written successfully")
            } ?: logger.warn("Failed to open decKeyFile output stream")
        } ?: logger.warn("decKeyFile is null")
    }

    /**
     * Handle file download with CRC32 check and retry logic
     */
    private suspend fun handleFileDownload(
        encFile: IPlatformFile,
        extractedEncFile: IPlatformFile,
        downloadDirectory: IPlatformFile?,
        path: String,
        fileName: String,
        size: Long,
        crc32: Long?,
        v4Key: Pair<ByteArray, String>?,
        firmwareId: String,
        model: DownloadModel,
    ): String? {
        return if (extractedEncFile.getLength() < size) {
            logger.debug("File size ${extractedEncFile.getLength()} < expected $size, starting download...")
            val downloadDir = downloadDirectory ?: run {
                model.endJob("")
                eventManager.sendEvent(Event.Download.Finish)
                return null
            }

            val firmwareIdForState = "${model.model.value}_${model.region.value}_${model.fw.value}"
                .replace("/", "_")
            val existingState = DownloadStateManager.loadState(firmwareIdForState)
            model.totalChunks.value = existingState?.chunks?.size ?: 0
            model.completedChunks.value = existingState?.chunks?.count { it.status == tk.zwander.common.data.ChunkStatus.COMPLETED } ?: 0

            FusClient.downloadFileChunked(
                fileName = path + fileName,
                size = size,
                dest = encFile,
                destDir = downloadDir,
                firmwareId = firmwareId,
                model = model.model.value,
                region = model.region.value,
                fw = model.fw.value,
                crc32 = crc32?.toString(),
                v4KeyBase64 = v4Key?.first?.let {
                    kotlin.io.encoding.Base64.Default.encode(it)
                },
                isPaused = { model.isPaused.value },
                progressCallback = { current, max, bps ->
                    model.progress.value = current to max
                    model.speed.value = bps

                    eventManager.sendEvent(
                        Event.Download.Progress(
                            status = MR.strings.downloading(),
                            current = current,
                            max = max,
                        )
                    )
                },
                chunkProgressCallback = { completed, total ->
                    model.completedChunks.value = completed
                    model.totalChunks.value = total
                },
                onNonceRefresh = {
                    logger.debug("Refreshing nonce due to auth error...")
                    FusClient.makeReq(FusClient.Request.GENERATE_NONCE)
                    logger.debug("Nonce refreshed successfully")
                },
            )
        } else if (crc32 != null) {
            logger.debug("File exists with expected size, verifying CRC32...")
            val crcCheckPassed = runCatching {
                encFile.openInputStream()?.use { inputStream ->
                    val crc = io.github.andreypfau.kotlinx.crypto.CRC32()
                    val buffer = ByteArray(8192)
                    var len = inputStream.readAtMostTo(buffer, 0, buffer.size)
                    while (len > 0) {
                        crc.update(buffer, 0, len)
                        len = inputStream.readAtMostTo(buffer, 0, buffer.size)
                    }
                    val actualCrc = crc.intDigest()
                    val expectedCrc = crc32.toInt()
                    logger.debug("Pre-download CRC32 check - actual: $actualCrc, expected: $expectedCrc, file size: ${encFile.getLength()}")
                    actualCrc == expectedCrc
                } ?: false
            }.getOrDefault(false)
            
            if (!crcCheckPassed) {
                logger.warn("Pre-download CRC32 check FAILED! Deleting corrupted file...")
                encFile.delete()
                
                val downloadDir = downloadDirectory ?: run {
                    model.endJob("")
                    eventManager.sendEvent(Event.Download.Finish)
                    return null
                }

                val firmwareIdForState = "${model.model.value}_${model.region.value}_${model.fw.value}"
                    .replace("/", "_")
                val existingState = DownloadStateManager.loadState(firmwareIdForState)
                model.totalChunks.value = existingState?.chunks?.size ?: 0
                model.completedChunks.value = existingState?.chunks?.count { it.status == tk.zwander.common.data.ChunkStatus.COMPLETED } ?: 0

                FusClient.downloadFileChunked(
                    fileName = path + fileName,
                    size = size,
                    dest = encFile,
                    destDir = downloadDir,
                    firmwareId = firmwareId,
                    model = model.model.value,
                    region = model.region.value,
                    fw = model.fw.value,
                    crc32 = crc32.toString(),
                    v4KeyBase64 = v4Key?.first?.let {
                        kotlin.io.encoding.Base64.Default.encode(it)
                    },
                    isPaused = { model.isPaused.value },
                    progressCallback = { current, max, bps ->
                        model.progress.value = current to max
                        model.speed.value = bps

                        eventManager.sendEvent(
                            Event.Download.Progress(
                                status = MR.strings.downloading(),
                                current = current,
                                max = max,
                            )
                        )
                    },
                    chunkProgressCallback = { completed, total ->
                        model.completedChunks.value = completed
                        model.totalChunks.value = total
                    },
                    onNonceRefresh = {
                        logger.debug("Refreshing nonce due to auth error...")
                        FusClient.makeReq(FusClient.Request.GENERATE_NONCE)
                        logger.debug("Nonce refreshed successfully")
                    },
                )
            } else {
                logger.debug("CRC32 check passed, skipping download")
                null
            }
        } else {
            null
        }
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
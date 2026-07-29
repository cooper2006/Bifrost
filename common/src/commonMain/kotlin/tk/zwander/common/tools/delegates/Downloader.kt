package tk.zwander.common.tools.delegates

import tk.zwander.common.util.BifrostLogger
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tk.zwander.common.data.BinaryFileInfo
import tk.zwander.common.data.DownloadPhase
import tk.zwander.common.data.Progress
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
import tk.zwander.common.util.retryWithBackoff
import tk.zwander.common.util.streamOperationWithProgress
import tk.zwander.commonCompose.model.DownloadModel
import tk.zwander.samloaderkotlin.resources.MR
import kotlin.time.ExperimentalTime

/**
 * 当下载处于暂停状态时阻塞，直到恢复。
 */
private suspend fun DownloadModel.waitWhilePaused() {
    while (isPaused.value) {
        delay(100)
    }
}

/**
 * 下载上下文的不可变快照，在 performDownload 初始化阶段构造一次，
 * 各阶段方法共享此上下文，避免重复从 model 读取。
 */
data class DownloadContext(
    val info: BinaryFileInfo,
    val fullFileName: String,
    val downloadDirectory: dev.zwander.kotlin.file.IPlatformFile,
    val tempDirectory: dev.zwander.kotlin.file.IPlatformFile?,
    val encFile: dev.zwander.kotlin.file.IPlatformFile,
    val extractedEncFile: dev.zwander.kotlin.file.IPlatformFile,
    val decFile: dev.zwander.kotlin.file.IPlatformFile?,
    val decKeyFile: dev.zwander.kotlin.file.IPlatformFile?,
)

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
        BifrostLogger.download.info("onDownload start: model=${model.model.value}, fw=${model.fw.value}, region=${model.region.value}")
        eventManager.sendEvent(Event.Download.Start)
        model.stateMachine.transition(DownloadPhase.FetchingInfo)

        val info = Request.retrieveBinaryFileInfo(
            fw = model.fw.value,
            model = model.model.value,
            region = model.region.value,
            onVersionException = { exception, info ->
                val errorMsg = exception.message ?: "Unknown error"
                BifrostLogger.download.info("onDownload version exception: $errorMsg")
                confirmCallback.onError(
                    info = DownloadErrorInfo(
                        message = errorMsg,
                        callback = DownloadErrorConfirmCallback(
                            onAccept = {
                                if (info != null) {
                                    performDownload(info, model)
                                } else {
                                    BifrostLogger.download.info("onDownload: info is null after version exception, aborting")
                                    model.endJob("")
                                    eventManager.sendEvent(Event.Download.Finish)
                                }
                            },
                            onCancel = {
                                model.endJob("")
                                eventManager.sendEvent(Event.Download.Finish)
                            },
                        )
                    ),
                )
            },
            onErrorFinish = { text ->
                BifrostLogger.download.info("onDownload retrieveBinaryFileInfo onErrorFinish: ${text.take(80)}")
                model.endJob(text)
                eventManager.sendEvent(Event.Download.Finish)
            },
            shouldReportError = {
                !model.manual.value
            },
            imeiSerial = model.imeiSerial.value,
            legacy = false,
        )

        if (info != null) {
            BifrostLogger.download.info("onDownload: retrieved file info, fileName=${info.fileName}, size=${info.size}")
            performDownload(info, model)
        } else {
            BifrostLogger.download.info("onDownload: no file info returned")
        }
    }

    /**
     * 执行下载的主流程，拆分为多个阶段方法。
     * 每个阶段方法职责单一，返回 Result 表示成功/失败。
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun performDownload(info: BinaryFileInfo, model: DownloadModel) {
        val (path, fileName, size, crc32, v4Key, fwVer, modelType) = info
        BifrostLogger.download.info("performDownload start: path=$path, fileName=$fileName, size=${size}bytes, crc32=$crc32, hasV4Key=${v4Key != null}, fwVer=$fwVer, modelType=$modelType")

        // --- 阶段 0: 初始化下载上下文 ---
        val ctx = buildDownloadContext(info, model) ?: return

        // --- 阶段 1: 写解密密钥文件（如果有） ---
        writeDecryptionKey(ctx, model)

        try {
            // --- 阶段 2: BinaryInit + 下载（含重试） ---
            val md5 = phaseBinaryInitAndDownload(ctx, model, info, fwVer, modelType, path, fileName, size)

            // --- 阶段 3: CRC32 校验 ---
            if (!phaseVerifyCrc32(ctx, model, crc32)) return

            // --- 阶段 4: MD5 校验 ---
            if (!phaseVerifyMd5(ctx, model, md5)) return

            // --- 阶段 5: 文件复制（如果使用临时目录） ---
            phaseCopyFile(ctx, model, size)

            // --- 阶段 6: 解密 ---
            if (!phaseDecrypt(ctx, model, info, size)) return

            // --- 清理 ---
            phaseCleanup(ctx, model)

            BifrostLogger.download.info("performDownload: DONE")
            model.stateMachine.transition(DownloadPhase.Done(MR.strings.done()))
            model.endJobSuccess(MR.strings.done())
        } catch (e: Throwable) {
            BifrostLogger.download.info("performDownload: FAILED: ${e.javaClass.simpleName}: ${e.message}")
            model.stateMachine.transition(DownloadPhase.Error(e.message ?: "", e))
            val message = if (e !is CancellationException) "${e.message}" else ""
            model.endJob(message)
        }

        eventManager.sendEvent(Event.Download.Finish)
    }

    // ===================== 阶段方法 =====================

    /**
     * 阶段 0: 构建下载上下文。
     * 返回 null 表示初始化失败（已调用 endJob）。
     */
    private suspend fun buildDownloadContext(
        info: BinaryFileInfo,
        model: DownloadModel,
    ): DownloadContext? {
        val fullFileName = info.fileName.replace(
            ".zip",
            "_${model.fw.value.replace("/", "_")}_${model.region.value}.zip",
        ).substringAfterLast("/")

        val decryptionKeyFileName = if (BifrostSettings.Keys.enableDecryptKeySave()) {
            "DecryptionKey_${fullFileName}.txt"
        } else {
            null
        }

        val downloadDirectory = FileManager.pickDirectory() ?: run {
            BifrostLogger.download.info("buildDownloadContext: downloadDirectory null, aborting")
            model.endJob("")
            eventManager.sendEvent(Event.Download.Finish)
            return null
        }
        val tempDirectory = FileManager.getTempDirectory()

        val encFile = (tempDirectory ?: downloadDirectory).child(fullFileName, false) ?: run {
            BifrostLogger.download.info("buildDownloadContext: encFile null, aborting")
            model.endJob("")
            eventManager.sendEvent(Event.Download.Finish)
            return null
        }
        val extractedEncFile = downloadDirectory.child(fullFileName, false) ?: run {
            BifrostLogger.download.info("buildDownloadContext: extractedEncFile null, aborting")
            model.endJob("")
            eventManager.sendEvent(Event.Download.Finish)
            return null
        }
        val decFile = downloadDirectory.child(
            fullFileName.replace(".enc2", "").replace(".enc4", ""),
            false,
        )
        val decKeyFile = downloadDirectory.let { dir ->
            decryptionKeyFileName?.let { dec -> dir.child(dec, false) }
        }

        val ctx = DownloadContext(
            info = info,
            fullFileName = fullFileName,
            downloadDirectory = downloadDirectory,
            tempDirectory = tempDirectory,
            encFile = encFile,
            extractedEncFile = extractedEncFile,
            decFile = decFile,
            decKeyFile = decKeyFile,
        )

        model.addTempFile(ctx.encFile)
        model.addTempFile(ctx.extractedEncFile)
        model.addTempFile(ctx.decFile)
        model.addTempFile(ctx.decKeyFile)

        BifrostLogger.download.info("buildDownloadContext: done, encFile=${ctx.encFile.getAbsolutePath()}, decFile=${ctx.decFile?.getAbsolutePath()}")
        return ctx
    }

    /**
     * 阶段 1: 写解密密钥文件。
     */
    private suspend fun writeDecryptionKey(ctx: DownloadContext, model: DownloadModel) {
        ctx.decKeyFile?.openOutputStream(false)?.use { output ->
            if (ctx.fullFileName.endsWith(".enc2")) {
                output.write(
                    CryptUtils.getV2Key(
                        model.fw.value,
                        model.model.value,
                        model.region.value,
                    ).second.toByteArray(),
                )
            }
            ctx.info.v4Key?.let {
                output.write(it.logicString.toByteArray())
            }
        }
    }

    /**
     * 阶段 2: BinaryInit + 下载（含 401/超时/断连重试）。
     * 使用 [retryWithBackoff] 替代手写 while 循环。
     */
    private suspend fun phaseBinaryInitAndDownload(
        ctx: DownloadContext,
        model: DownloadModel,
        info: BinaryFileInfo,
        fwVer: String?,
        modelType: String?,
        path: String,
        fileName: String,
        size: Long,
    ): String? {
        model.stateMachine.transition(DownloadPhase.BinaryInit)
        BifrostLogger.download.info("phaseBinaryInitAndDownload start")

        return retryWithBackoff<String?>(
            maxRetries = 10,
            initialDelay = 1000L,
            maxDelay = 10_000L,
            retryable = { e ->
                e is tk.zwander.common.exceptions.AuthExpiredException ||
                    e is java.net.SocketTimeoutException ||
                    e is tk.zwander.common.exceptions.DownloadTimeoutException ||
                    e is tk.zwander.common.exceptions.ConnectionClosedException ||
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("closed", ignoreCase = true) == true ||
                    (e is java.io.IOException && e !is java.nio.file.FileSystemException)
            },
        ) {
            doBinaryInitAndDownload(ctx, model, fwVer, modelType, path, fileName, size)
        }
    }

    /**
     * 执行一次 BinaryInit + 下载。
     */
    private suspend fun doBinaryInitAndDownload(
        ctx: DownloadContext,
        model: DownloadModel,
        fwVer: String?,
        modelType: String?,
        path: String,
        fileName: String,
        size: Long,
    ): String? {
        FusClient.refreshNonce()

        val initRequest = Request.createBinaryInit(
            fileName,
            FusClient.getNonce(),
            fwVer,
            modelType,
            model.region.value,
            legacy = false,
        )
        FusClient.makeReq(
            request = FusClient.Request.BINARY_INIT,
            data = initRequest,
            signature = null,
            includeNonce = true,
        )

        val existingLen = ctx.encFile.getLength()
        if (existingLen < size) {
            model.stateMachine.transitionWithProgress(
                DownloadPhase::Downloading,
                existingLen, size,
            )

            return FusClient.downloadFile(
                fileName = path + fileName,
                start = existingLen,
                size = size,
                dest = ctx.encFile,
                onAuthRefresh = {
                    BifrostLogger.download.info("doBinaryInitAndDownload: onAuthRefresh, re-sending BinaryInit")
                    FusClient.refreshNonce()
                    val refreshInit = Request.createBinaryInit(
                        fileName,
                        FusClient.getNonce(),
                        fwVer,
                        modelType,
                        model.region.value,
                        legacy = false,
                    )
                    FusClient.makeReq(
                        request = FusClient.Request.BINARY_INIT,
                        data = refreshInit,
                        signature = null,
                        includeNonce = true,
                    )
                },
                progressCallback = { current, max, bps ->
                    model.waitWhilePaused()
                    model.stateMachine.transitionWithProgress(DownloadPhase::Downloading, current, max, bps)
                    model.progress.value = current to max
                    model.speed.value = bps
                    eventManager.sendEvent(
                        Event.Download.Progress(status = MR.strings.downloading(), current = current, max = max),
                    )
                },
            )
        } else {
            BifrostLogger.download.info("doBinaryInitAndDownload: file already complete, skipping download")
            return null
        }
    }

    /**
     * 阶段 3: CRC32 校验。
     * @return true 表示校验通过或无需检查，false 表示失败（已调用 endJob）。
     */
    private suspend fun phaseVerifyCrc32(
        ctx: DownloadContext,
        model: DownloadModel,
        crc32: Long?,
    ): Boolean {
        if (crc32 == null) {
            BifrostLogger.download.info("phaseVerifyCrc32: no CRC32 provided, skipping")
            return true
        }

        BifrostLogger.download.info("phaseVerifyCrc32: starting CRC32 check, expected=$crc32")
        model.stateMachine.transitionWithProgress(DownloadPhase::VerifyingCrc, 0, ctx.encFile.getLength())

        val result = CryptUtils.checkCrc32(
            ctx.encFile.openInputStream() ?: return false,
            ctx.encFile.getLength(),
            crc32,
        ) { current, max, bps ->
            model.waitWhilePaused()
            model.stateMachine.transitionWithProgress(DownloadPhase::VerifyingCrc, current, max, bps)
            model.progress.value = current to max
            model.speed.value = bps
            eventManager.sendEvent(
                Event.Download.Progress(status = MR.strings.checkingCRC(), current = current, max = max),
            )
        }

        if (!result) {
            BifrostLogger.download.info("phaseVerifyCrc32: FAILED")
            model.cleanupTempFiles()
            model.endJob(MR.strings.crcCheckFailed())
            return false
        }
        return true
    }

    /**
     * 阶段 4: MD5 校验。
     * @return true 表示校验通过或无需检查，false 表示失败。
     */
    private suspend fun phaseVerifyMd5(
        ctx: DownloadContext,
        model: DownloadModel,
        md5: String?,
    ): Boolean {
        if (md5 == null) {
            BifrostLogger.download.info("phaseVerifyMd5: no MD5 provided, skipping")
            return true
        }

        BifrostLogger.download.info("phaseVerifyMd5: starting MD5 check, expected=$md5")
        model.stateMachine.transitionWithProgress(DownloadPhase::VerifyingMd5, 0, 1)

        val result = withContext(Dispatchers.Default) {
            CryptUtils.checkMD5(md5, ctx.encFile.openInputStream())
        }

        if (!result) {
            BifrostLogger.download.info("phaseVerifyMd5: FAILED")
            model.cleanupTempFiles()
            model.endJob(MR.strings.md5CheckFailed())
            return false
        }
        return true
    }

    /**
     * 阶段 5: 文件复制（如果使用临时目录）。
     */
    private suspend fun phaseCopyFile(
        ctx: DownloadContext,
        model: DownloadModel,
        size: Long,
    ) {
        if (ctx.tempDirectory == null || ctx.tempDirectory == ctx.downloadDirectory ||
            ctx.extractedEncFile.getLength() >= size) {
            return
        }

        BifrostLogger.download.info("phaseCopyFile: copying temp file to download dir")
        model.stateMachine.transitionWithProgress(DownloadPhase::Copying, 0, ctx.encFile.getLength())

        val input = ctx.encFile.openInputStream() ?: run {
            BifrostLogger.download.info("phaseCopyFile: input stream null, aborting")
            model.endJob("")
            return
        }
        val output = ctx.extractedEncFile.openOutputStream() ?: run {
            BifrostLogger.download.info("phaseCopyFile: output stream null, aborting")
            model.endJob("")
            return
        }

        try {
            streamOperationWithProgress(
                input = input,
                output = output,
                size = ctx.encFile.getLength(),
                progressCallback = { current, max, bps ->
                    model.waitWhilePaused()
                    model.stateMachine.transitionWithProgress(DownloadPhase::Copying, current, max, bps)
                    model.progress.value = current to max
                    model.speed.value = bps
                    eventManager.sendEvent(
                        Event.Download.Progress(status = MR.strings.copying(), current = current, max = max),
                    )
                },
            )
            BifrostLogger.download.info("phaseCopyFile: copy complete")
        } finally {
            input.close()
            output.close()
            ctx.encFile.delete()
        }
    }

    /**
     * 阶段 6: 解密文件。
     * @return true 表示解密成功或无需解密，false 表示失败。
     */
    private suspend fun phaseDecrypt(
        ctx: DownloadContext,
        model: DownloadModel,
        info: BinaryFileInfo,
        size: Long,
    ): Boolean {
        BifrostLogger.download.info("phaseDecrypt: starting")
        model.stateMachine.transitionWithProgress(DownloadPhase::Decrypting, 0, size)

        val key = if (ctx.fullFileName.endsWith(".enc2")) {
            BifrostLogger.download.info("phaseDecrypt: using V2 key (.enc2)")
            CryptUtils.getV2Key(model.fw.value, model.model.value, model.region.value).first
        } else if (info.v4Key != null) {
            BifrostLogger.download.info("phaseDecrypt: using V4 key (.enc4)")
            info.v4Key.keyBytes
        } else {
            BifrostLogger.download.info("phaseDecrypt: no key available, aborting")
            model.endJob("")
            return false
        }

        CryptUtils.decryptProgress(
            ctx.extractedEncFile.openInputStream() ?: return false,
            ctx.decFile?.openOutputStream() ?: return false,
            key,
            size,
        ) { current, max, bps ->
            model.waitWhilePaused()
            model.stateMachine.transitionWithProgress(DownloadPhase::Decrypting, current, max, bps)
            model.progress.value = current to max
            model.speed.value = bps
            eventManager.sendEvent(
                Event.Download.Progress(status = MR.strings.decrypting(), current = current, max = max),
            )
        }

        BifrostLogger.download.info("phaseDecrypt: complete")
        return true
    }

    /**
     * 清理：可选自动删除加密文件。
     */
    private suspend fun phaseCleanup(ctx: DownloadContext, model: DownloadModel) {
        if (BifrostSettings.Keys.autoDeleteEncryptedFirmware()) {
            BifrostLogger.download.info("phaseCleanup: auto-deleting encrypted files")
            ctx.encFile.delete()
            ctx.extractedEncFile.delete()
        }
    }

    suspend fun onFetch(model: DownloadModel) {
        BifrostLogger.download.info("onFetch START: model=${model.model.value}, region=${model.region.value}")
        model.statusText.value = ""
        model.changelog.value = null
        model.osCode.value = ""

        try {
            BifrostLogger.download.info("onFetch: calling hybridGetLatestVersion with 120s timeout")
            val (fw, os, error, output) = withTimeout(120_000L) {
                VersionFetch.hybridGetLatestVersion(
                    model.model.value,
                    model.region.value,
                )
            }
            BifrostLogger.download.info("onFetch: hybridGetLatestVersion returned, error=${error != null}, fw=$fw")

            if (error != null) {
                BifrostLogger.download.warn("onFetch: server returned error: ${error.message}")
                model.endJob(
                    MR.strings.firmwareCheckError(
                        error.message.toString(),
                        output.replace("\t", "  ")
                    )
                )
                return
            }

            BifrostLogger.download.info("onFetch: fetching changelog for $fw")
            model.changelog.value = ChangelogHandler.getChangelog(
                model.model.value,
                model.region.value,
                fw.split("/")[0],
            )
            BifrostLogger.download.info("onFetch: changelog fetched")

            model.fw.value = fw
            model.osCode.value = os

            model.endJob(MR.strings.done())
            BifrostLogger.download.info("onFetch SUCCESS")
        } catch (e: CancellationException) {
            BifrostLogger.download.info("onFetch cancelled (timeout or user cancel)")
            model.endJob(
                MR.strings.firmwareCheckError(
                    "Request timed out or was cancelled",
                    "",
                )
            )
        } catch (e: Throwable) {
            BifrostLogger.download.error("onFetch FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            model.endJob(
                MR.strings.firmwareCheckError(
                    e.message ?: "Unknown error",
                    "",
                )
            )
        }
    }
}

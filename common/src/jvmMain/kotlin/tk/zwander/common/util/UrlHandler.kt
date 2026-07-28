package tk.zwander.common.util

import java.awt.Desktop
import java.io.IOException
import java.lang.StringBuilder
import java.net.URI
import java.net.URLEncoder
import tk.zwander.common.util.BifrostLogger

actual object UrlHandler {
    private val desktop = Desktop.getDesktop()

    actual fun launchUrl(url: String, forceBrowser: Boolean) {
        val uri = URI(url)

        try {
            desktop.browse(uri)
        } catch (e: IOException) {
            BifrostLogger.general.warn("Failed to open URL: ${url}", e)
        } catch (e: UnsupportedOperationException) {
            BifrostLogger.general.warn("Desktop browse not supported for URL: ${url}", e)
        }
    }
    actual fun sendEmail(address: String, subject: String?, content: String?) {
        val string = StringBuilder()
        string.append("mailto:")
        string.append(address)

        string.append("?subject=${URLEncoder.encode(subject ?: "", Charsets.UTF_8)}")
        string.append("&body=${URLEncoder.encode(content ?: "", Charsets.UTF_8)}")

        val uri = URI(string.toString())

        try {
            desktop.mail(uri)
        } catch (e: IOException) {
            BifrostLogger.general.warn("Failed to send email to: ${address}", e)
        } catch (e: UnsupportedOperationException) {
            BifrostLogger.general.warn("Desktop mail not supported for: ${address}", e)
        }
    }
}
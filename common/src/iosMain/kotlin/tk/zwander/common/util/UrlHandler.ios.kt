package tk.zwander.common.util

import io.ktor.http.encodeURLQueryComponent
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import tk.zwander.common.util.BifrostLogger

actual object UrlHandler {
    actual fun launchUrl(url: String, forceBrowser: Boolean) {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl != null) {
            UIApplication.sharedApplication.openURL(
                nsUrl,
                mapOf<Any?, Any?>(),
                null,
            )
        } else {
            BifrostLogger.general.warn("launchUrl: invalid URL: $url")
        }
    }

    actual fun sendEmail(address: String, subject: String?, content: String?) {
        val url = buildString {
            append(address)

            val options = mutableListOf<String>()

            if (!subject.isNullOrBlank()) {
                options.add("subject=${subject.encodeURLQueryComponent()}")
            }

            if (!content.isNullOrBlank()) {
                options.add("body=${content.encodeURLQueryComponent()}")
            }

            if (options.isNotEmpty()) {
                append("?${options.joinToString("&")}")
            }
        }

        val nsUrl = NSURL.URLWithString("mailto:$url")
        if (nsUrl != null) {
            UIApplication.sharedApplication.openURL(
                nsUrl,
                mapOf<Any?, Any?>(),
                null,
            )
        } else {
            BifrostLogger.general.warn("sendEmail: invalid mailto URL: $url")
        }
    }
}
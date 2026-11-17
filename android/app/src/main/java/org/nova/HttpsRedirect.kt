package org.nova

import java.net.URL

/** Validates a redirect before opening its destination. */
internal object HttpsRedirect {
    fun resolve(currentUrl: String, location: String): String {
        val base = URL(currentUrl)
        require(base.protocol.equals("https", ignoreCase = true) && base.host.isNotBlank()) {
            "HTTPS redirect source required"
        }
        val target = URL(base, location.trim())
        require(target.protocol.equals("https", ignoreCase = true) &&
            target.host.isNotBlank() && target.userInfo == null) {
            "Refusing non-HTTPS or credential-bearing redirect"
        }
        return target.toExternalForm()
    }
}

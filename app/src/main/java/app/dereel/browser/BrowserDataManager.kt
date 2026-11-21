package app.dereel.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.annotation.MainThread
import app.dereel.core.model.Platform
import app.dereel.core.web.PlatformWeb

/**
 * Clears Dereel-owned browser data, scoped to one platform's documented origins (FR-18, FR-19,
 * FR-35). Another platform's sign-in is never touched by a platform-scoped clear.
 */
class BrowserDataManager {
    data class ClearResult(val remainingCookieDomains: List<String>)

    @MainThread
    fun clearPlatform(platform: Platform): ClearResult {
        val scope = PlatformWeb.dataScope(platform)
        val cookies = CookieManager.getInstance()
        for (domain in scope.cookieDomains) {
            for (url in urlsFor(domain)) {
                val header = cookies.getCookie(url) ?: continue
                header.split(';')
                    .map { it.substringBefore('=').trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .forEach { name -> expire(cookies, url, domain, name) }
            }
        }
        cookies.flush()
        val storage = WebStorage.getInstance()
        scope.storageOrigins.forEach { storage.deleteOrigin(it) }

        // Cookies set on a narrower path cannot be listed, so report anything left behind.
        val remaining = scope.cookieDomains.filter { domain -> urlsFor(domain).any { !cookies.getCookie(it).isNullOrBlank() } }
        return ClearResult(remaining)
    }

    /** Reset: removes all Dereel browser sessions and site storage (SEC-07). */
    @MainThread
    fun clearAll() {
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.flush()
        WebStorage.getInstance().deleteAllData()
    }

    private fun urlsFor(domain: String) = listOf("https://$domain/", "https://www.$domain/", "https://m.$domain/", "https://accounts.$domain/")

    private fun expire(cookies: CookieManager, url: String, domain: String, name: String) {
        val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure"
        cookies.setCookie(url, "$expired; Domain=.$domain")
        cookies.setCookie(url, "$expired; Domain=$domain")
        cookies.setCookie(url, expired) // host-only and __Host- cookies
    }
}

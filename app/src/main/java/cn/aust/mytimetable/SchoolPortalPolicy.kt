package cn.aust.mytimetable

import java.net.URI

/** No JavaScript bridge, credential interception, or navigation outside this origin. */
internal object SchoolPortalPolicy {
    const val HOME = "https://jwglyd.aust.edu.cn/app-web/#/tabs/course-table"

    fun allowsNavigation(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("jwglyd.aust.edu.cn", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }
}

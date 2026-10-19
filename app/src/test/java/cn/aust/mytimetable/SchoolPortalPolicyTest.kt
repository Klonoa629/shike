package cn.aust.mytimetable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolPortalPolicyTest {
    @Test fun acceptsTheSchoolHttpsOrigin() {
        assertTrue(SchoolPortalPolicy.allowsNavigation(SchoolPortalPolicy.HOME))
        assertTrue(SchoolPortalPolicy.allowsNavigation("https://jwglyd.aust.edu.cn:443/app-web/#/login"))
    }

    @Test fun rejectsLookalikeHostsAndUserInfo() {
        listOf(
            "https://jwglyd.aust.edu.cn.example.com/",
            "https://jwglyd.aust.edu.cn@example.com/",
            "https://example.com@jwglyd.aust.edu.cn/",
            "https://example.com/",
        ).forEach { assertFalse(it, SchoolPortalPolicy.allowsNavigation(it)) }
    }

    @Test fun rejectsNonHttpsAndUnexpectedPorts() {
        listOf(
            "http://jwglyd.aust.edu.cn/",
            "https://jwglyd.aust.edu.cn:8443/",
            "file:///data/local/file.html",
            "javascript:alert(1)",
            "intent://jwglyd.aust.edu.cn/",
            "/app-web/",
            "not a URL",
        ).forEach { assertFalse(it, SchoolPortalPolicy.allowsNavigation(it)) }
    }
}

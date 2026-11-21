package com.termux.agent

import com.termux.agent.application.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {
    @Test
    fun defaultSshEndpointMatchesTermuxSshd() {
        assertEquals("127.0.0.1", AppConfig.DEFAULT_SSH_HOST)
        assertEquals(8022, AppConfig.DEFAULT_SSH_PORT)
        assertEquals("com.termux", AppConfig.TERMUX_PACKAGE_NAME)
    }
}

package com.termux.agent

import com.termux.agent.application.config.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppConfigTest {
    @Test
    fun defaultSshEndpointMatchesTermuxSshd() {
        assertEquals("127.0.0.1", AppConfig.DEFAULT_SSH_HOST)
        assertEquals(8022, AppConfig.DEFAULT_SSH_PORT)
        assertEquals("com.termux", AppConfig.TERMUX_PACKAGE_NAME)
    }
}

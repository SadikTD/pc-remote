package com.pcremote.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test fun `first run with no PCs starts at onboarding`() {
        assertEquals(Routes.ONBOARDING, Routes.start(pcCount = 0, onboarded = false))
    }

    @Test fun `any known PC or finished onboarding starts at home`() {
        assertEquals(Routes.HOME, Routes.start(pcCount = 1, onboarded = false))
        assertEquals(Routes.HOME, Routes.start(pcCount = 0, onboarded = true))
    }

    @Test fun `mode routes encode the pc id`() {
        assertEquals("pc/a%20b%2Fc/remote", Routes.mode("a b/c", Mode.REMOTE))
        assertEquals("pc/{id}/remote", Routes.modePattern(Mode.REMOTE))
    }
}

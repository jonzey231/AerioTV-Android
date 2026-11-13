package com.aeriotv.android.feature.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Test

/** The What's New header reads as Apple's (App/WhatsNew.swift): title, then the app and version. */
class WhatsNewCopyTest {
    @Test fun headerCopyMatchesApple() {
        assertEquals("What's new in 0.5.14", WhatsNewCopy.title("0.5.14"))
        assertEquals("AerioTV 0.5.14", WhatsNewCopy.versionLine("0.5.14"))
    }
}

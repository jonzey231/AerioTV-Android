package com.aeriotv.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class LocalRecordingRulesTest {
    private fun c(key: String, name: String = key.substringAfterLast('/')) = LocalRecordingRules.Candidate(key, name)

    @Test fun everyMediaFileNoRowPointsAtIsAdoptedAndNoneTwice() {
        val files = listOf(
            c("/rec/b.ts"), c("/rec/a.mp4"), c("/rec/c.m4v"), c("/rec/d.MKV"), c("/rec/e.mov"),
            c("/rec/known.ts"), c("/rec/a.mp4"),
        )
        val out = LocalRecordingRules.orphans(files, known = setOf("/rec/known.ts"))
        assertEquals(listOf("a.mp4", "b.ts", "c.m4v", "d.MKV", "e.mov"), out.map { it.name })
    }

    @Test fun hiddenAndNonMediaFilesAreNotAdopted() {
        val files = listOf(c("/rec/.hidden.ts"), c("/rec/notes.txt"), c("/rec/noext"), c("/rec/clip.ts.part"))
        assertTrue(LocalRecordingRules.orphans(files, known = emptySet()).isEmpty())
    }

    @Test fun aMediaStoreItemMatchesByItsStoredKey() {
        val uri = "content://media/external_primary/downloads/42"
        val files = listOf(c(uri, "Show 2026-11-13.ts"))
        assertTrue(LocalRecordingRules.orphans(files, known = setOf(uri)).isEmpty())
        assertEquals(1, LocalRecordingRules.orphans(files, known = emptySet()).size)
    }

    @Test fun aRowWhoseFileIsGoneOrHasNoLocationIsMissing() {
        val none: (String) -> Boolean = { false }
        val all: (String) -> Boolean = { true }
        assertTrue(LocalRecordingRules.isMissing("completed", "/rec/a.ts", none))
        assertTrue(LocalRecordingRules.isMissing("stopped", "/rec/a.ts", none))
        assertTrue(LocalRecordingRules.isMissing("interrupted", "content://media/external_primary/downloads/7", none))
        assertTrue(LocalRecordingRules.isMissing("completed", "", all))
        assertTrue(LocalRecordingRules.isMissing("completed", null, all))
        assertFalse(LocalRecordingRules.isMissing("completed", "/rec/a.ts", all))
    }

    @Test fun onlyAFinishedRecordingCanBeMissing() {
        val none: (String) -> Boolean = { false }
        assertFalse(LocalRecordingRules.isMissing("recording", "/rec/a.ts", none))
        assertFalse(LocalRecordingRules.isMissing("failed", "", none))
    }

    @Test fun anAdoptedFileIsNamedFromItsDateAsAppleNamesIt() {
        // 2026-11-13 14:52 UTC.
        val title = LocalRecordingRules.adoptedTitle(1_794_581_520_000L, Locale.US, TimeZone.getTimeZone("UTC"))
        // The time's space before PM is a narrow no-break space on current
        // ICU data, a plain one on older; Apple's DateFormatter follows its own.
        assertTrue(title, Regex("Recording from Nov 13, 2026 at 2:52[ \\u202F]PM").matches(title))
    }
}

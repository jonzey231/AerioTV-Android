package com.aeriotv.android.core.data

import com.aeriotv.android.core.data.LocalRecordingFiles.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Recording files are deleted by the kind of path stored (Apple's agent
 * found a profile removal using File(path).delete(), which does nothing for
 * the content:// paths Android 10 and later store by default).
 */
class LocalRecordingFilesTest {
    @Test fun pathsAreClassifiedByHowTheyWereStored() {
        assertEquals(Kind.ContentUri, LocalRecordingFiles.kind("content://media/external/downloads/42"))
        assertEquals(Kind.ContentUri, LocalRecordingFiles.kind("content://com.android.externalstorage.documents/document/primary%3AAerioTV%2Frec.ts"))
        assertEquals(Kind.FileUri, LocalRecordingFiles.kind("file:///data/user/0/com.aeriotv.android/files/rec.ts"))
        assertEquals(Kind.Path, LocalRecordingFiles.kind("/data/user/0/com.aeriotv.android/files/rec.ts"))
    }

    @Test fun noRecordingPathIsDeletedWithARawFileCall() {
        // Every recording delete goes through LocalRecordingFiles.delete.
        val roots = listOf("src/main/java", "app/src/main/java").map { java.io.File(it) }.filter { it.isDirectory }
        org.junit.Assert.assertTrue("source tree not found from ${java.io.File(".").absolutePath}", roots.isNotEmpty())
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (Regex("""File\([^)]*filePath[^)]*\)\.delete\(\)""").containsMatchIn(line)) "${f.name}:${i + 1}" else null
                }
            }.toList()
        }
        assertFalse("raw File(...filePath...).delete(): $offenders", offenders.isNotEmpty())
    }
}

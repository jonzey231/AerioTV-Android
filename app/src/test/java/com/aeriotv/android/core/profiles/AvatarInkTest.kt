package com.aeriotv.android.core.profiles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Avatar letter color and initial (review finding A8, owner pick 2026-11-11):
 * the same on Apple and Android, in light and dark mode, on every form factor.
 */
class AvatarInkTest {

    @Test fun darkLettersOnTheFourLighterColors() {
        for (id in listOf("teal", "orange", "green", "slate")) {
            assertEquals(id, 0xFF0B1220, AvatarCatalog.inkArgb(id))
        }
    }

    @Test fun whiteLettersOnTheOtherFour() {
        for (id in listOf("blue", "purple", "sea", "ember")) {
            assertEquals(id, 0xFFFFFFFF, AvatarCatalog.inkArgb(id))
        }
    }

    @Test fun everyPaletteColorHasOneOfTheTwoInks() {
        assertEquals(8, AvatarCatalog.COLORS.size)
        assertEquals(4, AvatarCatalog.COLORS.count { AvatarCatalog.inkArgb(it) == AvatarCatalog.DARK_INK })
        assertEquals(4, AvatarCatalog.COLORS.count { AvatarCatalog.inkArgb(it) == AvatarCatalog.LIGHT_INK })
    }

    @Test fun theInitialIsTheTrimmedNamesFirstCharacterUppercased() {
        assertEquals("S", AvatarCatalog.initial("  sam ", "kid"))
        assertEquals("L", AvatarCatalog.initial("Logan", "jonzey231"))
    }

    @Test fun anEmptyOrBlankNameFallsBackToTheTrimmedUsername() {
        assertEquals("K", AvatarCatalog.initial("", "kid"))
        assertEquals("B", AvatarCatalog.initial("   ", "  bob "))
    }

    @Test fun nothingAtAllDrawsAQuestionMark() {
        assertEquals("?", AvatarCatalog.initial("", ""))
        assertEquals("?", AvatarCatalog.initial("  ", " \t "))
    }

    @Test fun anAccentedFirstLetterStaysWhole() {
        assertEquals("\u00C9", AvatarCatalog.initial("\u00E9mile", ""))
        // A letter with a combining mark is one character.
        assertEquals("E\u0301", AvatarCatalog.initial("e\u0301va", ""))
    }

    @Test fun anEmojiFirstCharacterStaysWhole() {
        // Thumbs up: a surrogate pair, never half of it.
        assertEquals("\uD83D\uDC4D", AvatarCatalog.initial("\uD83D\uDC4D Team", "x"))
        // With a skin tone modifier: one extended grapheme cluster.
        assertEquals("\uD83D\uDC4D\uD83C\uDFFD", AvatarCatalog.initial("\uD83D\uDC4D\uD83C\uDFFDok", "x"))
    }
}

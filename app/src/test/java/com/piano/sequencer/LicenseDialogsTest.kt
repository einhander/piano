package com.piano.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseDialogsTest {
    @Test
    fun acceptsBundledDocumentBasenames() {
        listOf(
            "GPL-3.0.txt",
            "LICENSES.json",
            "SOURCE.txt",
            "Runtime-Notice-0123456789abcdef0123456789abcdef01234567.txt"
        ).forEach { name -> assertTrue(name, LicenseDialogs.isSafeName(name)) }
    }

    @Test
    fun rejectsTraversalPathsUrlsAndUnsupportedFormats() {
        listOf(
            "../NOTICE.txt", "/NOTICE.txt", "licenses/NOTICE.txt", "licenses\\NOTICE.txt",
            "", "..", "a..b.txt", "NOTICE.html", "NOTICE.HTML",
            "https://example.org/NOTICE.txt", "NOTICE.txt?download=1", " NOTICE.txt"
        ).forEach { name -> assertFalse(name, LicenseDialogs.isSafeName(name)) }
    }

    @Test
    fun paginationPreservesAllCharactersAndWhitespace() {
        val original = "First line\r\n\tSecond line\n" + "x".repeat(48000) + "\nLast line  \n"
        val pages = LicenseDialogs.documentPages(original)
        assertEquals(original, pages.joinToString(""))
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.isNotEmpty() && it.length <= 16000 })
    }

    @Test
    fun paginationKeepsSurrogatePairTogetherAtBoundary() {
        val original = "x".repeat(15999) + "\uD83C\uDFB9" + "y".repeat(16000)
        val pages = LicenseDialogs.documentPages(original)
        assertEquals(original, pages.joinToString(""))
        assertEquals(15999, pages.first().length)
        assertTrue(pages[1].startsWith("\uD83C\uDFB9"))
        assertTrue(pages.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test
    fun exactBoundaryDoesNotCreateEmptyTrailingPage() {
        val original = "x".repeat(16000)
        assertEquals(listOf(original), LicenseDialogs.documentPages(original))
    }

    @Test
    fun emptyDocumentHasReadableNonemptyPage() {
        val pages = LicenseDialogs.documentPages("")
        assertEquals(1, pages.size)
        assertTrue(pages.single().isNotBlank())
    }
}

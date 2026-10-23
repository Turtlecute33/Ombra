package app.ombra.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicencesTest {
    @Test
    fun proseJoinsTheLinesOfAParagraph() {
        val text = "  GNU GENERAL PUBLIC LICENSE\n   Version 3\r\n\r\nPreamble line one\nline two\n \n\n\nEnd\n"
        assertEquals(listOf("GNU GENERAL PUBLIC LICENSE Version 3", "Preamble line one line two", "End"), paragraphs(text, reflow = true))
    }

    @Test
    fun aListKeepsItsLines() {
        val text = "serde 1.0.219\n  MIT OR Apache-2.0\n\ntokio 1.47.1\n  MIT\n"
        assertEquals(listOf("serde 1.0.219\n  MIT OR Apache-2.0", "tokio 1.47.1\n  MIT"), paragraphs(text, reflow = false))
    }

    @Test
    fun thePackagedGplIsTheRepositoryLicence() {
        // Unit tests run in the module directory.
        val packaged = File("src/main/res/raw/licence_gpl_3_0.txt")
        val repository = File("../LICENSE")
        assertTrue(packaged.isFile && repository.isFile, "run from the app module")
        assertEquals(repository.readText(), packaged.readText())
    }

    @Test
    fun everyComponentHasALicence() {
        assertEquals(COMPONENTS.size, COMPONENTS.map { it.name }.toSet().size)
        assertTrue(COMPONENTS.any { it.licence == Licence.Gpl3 })
        assertTrue(COMPONENTS.filter { it.licence == Licence.Apache2 }.map { it.name }.containsAll(listOf("AndroidX", "Jetpack Compose", "Kotlin")))
    }
}

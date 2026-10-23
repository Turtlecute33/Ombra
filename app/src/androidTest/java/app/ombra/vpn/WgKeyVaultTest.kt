package app.ombra.vpn

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android Keystore part of WgKeyVault, which JVM tests cannot reach. */
class WgKeyVaultTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "wg-key-vault-test")

    @Before
    fun cleanDir() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun removeDir() {
        dir.deleteRecursively()
    }

    @Test
    fun storesEncryptedAndLoadsBack() {
        val secret = ByteArray(32) { (it * 7 + 1).toByte() }
        assertNull(WgKeyVault(dir).load())

        assertTrue(WgKeyVault(dir).store(secret.copyOf()))

        val onDisk = dir.listFiles().orEmpty().single().readBytes()
        assertFalse(onDisk.asList().windowed(secret.size).any { it == secret.asList() }, "the key is on disk in clear")
        assertContentEquals(secret, WgKeyVault(dir).load())
    }

    @Test
    fun damagedFileGivesNoKey() {
        assertTrue(WgKeyVault(dir).store(ByteArray(32) { 1 }))
        val file = dir.listFiles().orEmpty().single()
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

        // Rust then makes a new key instead of using a wrong one.
        assertNull(WgKeyVault(dir).load())
    }
}

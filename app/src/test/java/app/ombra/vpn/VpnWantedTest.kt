package app.ombra.vpn

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnWantedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun flagSurvivesANewInstance() {
        VpnWanted(File(tmp.root, VpnWanted.FILE_NAME)).value = true
        assertTrue(VpnWanted(File(tmp.root, VpnWanted.FILE_NAME)).value)
    }

    @Test
    fun isSetReadsWhatTheFlagWrote() {
        // PackageReplacedReceiver reads the flag with isSet, without CoreHost.
        val wanted = VpnWanted(File(tmp.root, VpnWanted.FILE_NAME))
        assertFalse(VpnWanted.isSet(tmp.root))
        wanted.value = true
        assertTrue(VpnWanted.isSet(tmp.root))
        wanted.value = false
        assertFalse(VpnWanted.isSet(tmp.root))
    }
}

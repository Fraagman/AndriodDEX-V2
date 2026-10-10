package com.example.androidhost

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The backup exclusion rules must name files that actually exist in app storage.
 * A rule naming a stale filename is a silent no-op: flip allowBackup and the
 * pairing key leaves the device with the backup.
 *
 * Reads the rule files straight from the source tree (Gradle runs unit tests
 * with the module directory as the working directory).
 */
class BackupRulesTest {

    @Test
    fun backupRulesExcludeTheRealKeyFilenames() {
        val fullText = File("src/main/res/xml/backup_rules.xml").readText()
        assertTrue("full-backup-content must exclude pairing_v2.psk", fullText.contains("pairing_v2.psk"))
        assertTrue("full-backup-content must exclude tls_identity.bin", fullText.contains("tls_identity.bin"))
        assertTrue("the legacy pairing.psk name must not appear", !fullText.contains("\"pairing.psk\""))

        val extractText = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("data-extraction-rules must exclude pairing_v2.psk", extractText.contains("pairing_v2.psk"))
        assertTrue("data-extraction-rules must exclude tls_identity.bin", extractText.contains("tls_identity.bin"))
        assertTrue("the legacy pairing.psk name must not appear", !extractText.contains("\"pairing.psk\""))
    }
}

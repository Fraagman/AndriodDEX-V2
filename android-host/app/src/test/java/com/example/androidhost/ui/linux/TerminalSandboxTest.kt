package com.example.androidhost.ui.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Pins the terminal jail's rules. The terminal is network-reachable from any
 * paired PC, and the app's private store (filesDir) holds pairing_v2.psk and
 * tls_identity.bin — the tests reproduce exactly the proven attack
 * (`run-as ... sh -c 'cd files && head pairing_v2.psk'` succeeded on the
 * emulator) and assert every variant of it is now refused.
 */
class TerminalSandboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Builds the real on-device layout: filesDir with the key files, and the
     * jail root (filesDir/home) as a subfolder.
     */
    private fun realLayout(): Triple<File, File, File> {
        val filesDir = tmp.newFolder("files")
        // The secrets, exactly where SecureStore writes them on the device.
        File(filesDir, "pairing_v2.psk").writeBytes(ByteArray(32) { it.toByte() })
        File(filesDir, "tls_identity.bin").writeBytes(ByteArray(64))
        val root = TerminalSandbox.jailRoot(filesDir)
        File(root, "readme.txt").writeText("sandbox content")
        return Triple(filesDir, root, File(root, "readme.txt"))
    }

    // ---- The proven attack, in every form ----

    @Test
    fun catPairingKeyRelativePathIsRefused() {
        val (filesDir, root, _) = realLayout()
        // cwd = filesDir itself (the OLD jail root) — the key is "inside" the
        // old root, which is precisely why the old check passed it. The new
        // check still refuses it because the jail root is filesDir/home.
        val refusal = TerminalSandbox.validate(root, filesDir, "cat pairing_v2.psk".split(" "))
        assertNotNull("cat pairing_v2.psk from the OLD root must be refused", refusal)
    }

    @Test
    fun headTailWcOnTheKeyAreRefusedFromInsideTheJail() {
        val (filesDir, root, _) = realLayout()
        val key = File(filesDir, "pairing_v2.psk")
        // The proven live attack ran with cwd=filesDir (the old jail root); from
        // the new jail the key is one level up, reachable via ../ or an absolute
        // path. Every path-taking program must refuse it in both forms.
        for (cmd in listOf(
            listOf("head", "../pairing_v2.psk"),
            listOf("tail", "../pairing_v2.psk"),
            listOf("wc", "../pairing_v2.psk"),
            listOf("head", key.absolutePath),
            listOf("tail", key.absolutePath),
            listOf("wc", key.absolutePath),
        )) {
            val refusal = TerminalSandbox.validate(root, root, cmd)
            assertNotNull("'${cmd.joinToString(" ")}' (the proven live attack) must be refused", refusal)
        }
    }

    @Test
    fun lsRefusesPathsOutsideTheJail() {
        val (filesDir, root, _) = realLayout()
        assertNotNull(TerminalSandbox.validate(root, root, listOf("ls", filesDir.absolutePath)))
        // Plain `ls` (cwd listing) stays allowed.
        assertNull(TerminalSandbox.validate(root, root, listOf("ls")))
    }

    // ---- Path resolution rules ----

    @Test
    fun relativePathInsideJailIsAllowed() {
        val (_, root, _) = realLayout()
        assertNull("'cat readme.txt' inside the jail must be allowed",
            TerminalSandbox.validate(root, root, listOf("cat", "readme.txt")))
    }

    @Test
    fun absolutePathInsideJailIsAllowed() {
        val (_, root, file) = realLayout()
        assertNull(TerminalSandbox.validate(root, root, listOf("cat", file.absolutePath)))
    }

    @Test
    fun absolutePathOutsideJailIsRefused() {
        val (filesDir, root, _) = realLayout()
        val key = File(filesDir, "pairing_v2.psk")
        val refusal = TerminalSandbox.validate(root, root, listOf("cat", key.absolutePath))
        assertNotNull("absolute path to the key must be refused", refusal)
        assertTrue(refusal!!.contains("outside sandbox"))
    }

    @Test
    fun parentTraversalEscapeIsRefused() {
        val (_, root, _) = realLayout()
        for (arg in listOf("../pairing_v2.psk", "../../pairing_v2.psk", "../tls_identity.bin")) {
            val refusal = TerminalSandbox.validate(root, root, listOf("cat", arg))
            assertNotNull("traversal '$arg' must be refused", refusal)
        }
    }

    @Test
    fun dotDotThatStaysInsideIsAllowed() {
        val (_, root, _) = realLayout()
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "a.txt").writeText("x")
        // From sub, `../readme.txt` resolves to root/readme.txt — inside.
        assertNull(TerminalSandbox.validate(root, sub, listOf("cat", "../readme.txt")))
    }

    // ---- Symlinks ----

    @Test
    fun symlinkInsideJailIsAllowed() {
        val (filesDir, root, file) = realLayout()
        val link = File(root, "link.txt")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), file.toPath())
            true
        }.getOrDefault(false)
        assumeTrue("symlink creation not permitted on this host", created)
        assertNull("symlink to a file inside the jail must be allowed",
            TerminalSandbox.validate(root, root, listOf("cat", "link.txt")))
    }

    @Test
    fun symlinkEscapingTheJailIsRefused() {
        val (filesDir, root, _) = realLayout()
        val key = File(filesDir, "pairing_v2.psk")
        val link = File(root, "key.txt")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), key.toPath())
            true
        }.getOrDefault(false)
        assumeTrue("symlink creation not permitted on this host", created)
        // canonicalFile resolves the link to filesDir/pairing_v2.psk — outside
        // the jail even though the link itself sits inside it.
        val refusal = TerminalSandbox.validate(root, root, listOf("cat", "key.txt"))
        assertNotNull("symlink pointing at the pairing key must be refused", refusal)
    }

    // ---- Program rules ----

    @Test
    fun unknownProgramsAreRefused() {
        val (_, root, _) = realLayout()
        for (cmd in listOf("sh", "rm", "sh -c ls", "cp a b", "mv a b", "chmod 777 .")) {
            assertNotNull("'$cmd' must be refused", TerminalSandbox.validate(root, root, cmd.split(" ")))
        }
    }

    @Test
    fun flagsAreNotTreatedAsPaths() {
        val (_, root, _) = realLayout()
        assertNull(TerminalSandbox.validate(root, root, listOf("head", "-n", "5", "readme.txt")))
        assertNull(TerminalSandbox.validate(root, root, listOf("wc", "-c", "readme.txt")))
    }

    @Test
    fun jailRootIsCreatedUnderFilesDirAndHoldsNoSecrets() {
        val (filesDir, root, _) = realLayout()
        assertEquals(File(filesDir, "home"), root)
        assertTrue("jail root must exist", root.exists())
        // The key files must be OUTSIDE the jail root (one level up).
        assertTrue(File(filesDir, "pairing_v2.psk").exists())
        assertTrue(File(root, "pairing_v2.psk").exists().not())
    }

    @Test
    fun cdIsJailedByTheSameCheck() {
        val (filesDir, root, _) = realLayout()
        // `cd ..` from the jail root points at filesDir — must resolve to null
        // so TerminalWindow prints "outside sandbox" instead of moving there.
        assertNull(TerminalSandbox.resolveInRoot(root, root, ".."))
        // `cd sub` inside the jail resolves (compare canonical paths: File
        // equality is string-based and temp-dir prefixes can be non-canonical).
        val sub = File(root, "sub").apply { mkdirs() }
        val resolved = TerminalSandbox.resolveInRoot(root, root, "sub")
        assertNotNull(resolved)
        assertEquals(sub.canonicalPath, resolved!!.absolutePath)
    }
}

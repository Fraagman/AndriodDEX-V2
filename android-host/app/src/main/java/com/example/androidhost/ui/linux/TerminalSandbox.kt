package com.example.androidhost.ui.linux

import java.io.File

/**
 * The terminal's sandbox, extracted so its rules are unit-testable.
 *
 * The terminal is reachable from any paired PC, so its rules are:
 *  1. Only read-only programs from [ALLOWED_PROGRAMS] run — everything else is
 *     refused, and no shell is ever involved (arguments are passed literally, so
 *     `;`, `|`, `$()` and backticks cannot smuggle commands).
 *  2. Every program that takes a file path ([PATH_TAKING_PROGRAMS]) resolves each
 *     non-flag path argument through [resolveInRoot] and refuses anything outside
 *     the jail.
 *  3. The jail root is a dedicated subfolder of filesDir ([JAIL_DIR]) — NOT
 *     filesDir itself, which holds `pairing_v2.psk` and `tls_identity.bin` one
 *     level up. Nothing secret is ever created inside it.
 */
internal object TerminalSandbox {

    /** Read-only programs the sandboxed terminal will execute. */
    val ALLOWED_PROGRAMS = setOf(
        "ls", "pwd", "echo", "date", "whoami", "cat", "df", "uname", "head", "tail", "wc"
    )

    /**
     * Programs whose non-flag arguments are file paths that must stay in the jail.
     * `df` takes a path too but only reports filesystem stats; it is excluded
     * from the path check the way it is excluded from harm.
     */
    private val PATH_TAKING_PROGRAMS = setOf("ls", "cat", "head", "tail", "wc")

    /** The jail lives in a dedicated subfolder that never holds secrets. */
    const val JAIL_DIR = "home"

    /** The jail root under [filesDir], created on first use. */
    fun jailRoot(filesDir: File): File {
        val root = File(filesDir, JAIL_DIR)
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    /**
     * Resolves [arg] against [cwd] and returns the canonical file only if it stays
     * inside [root]. Returns null when the path escapes the jail: an absolute path
     * elsewhere, a `../` traversal, or a symlink whose target lies outside.
     * `canonicalFile` resolves symlinks, so the symlink case is covered by the
     * same prefix check; if canonicalization itself fails, the path is refused.
     *
     * Absolute arguments are resolved as `File(arg)`, NOT `File(cwd, arg`):
     * Android's libcore `File(File, String)` CONCATENATES instead of honouring an
     * absolute child, so `File(cwd, "/data/…")` would glue the absolute path
     * into the jail, pass the containment check, and ProcessBuilder would still
     * hand the original absolute token to the OS — which resolves it truly
     * absolutely and reads the key. (Proven by the failing
     * `absolutePathInsideJailIsAllowed` test on the Android-semantics JVM.)
     */
    fun resolveInRoot(root: File, cwd: File, arg: String): File? {
        if (arg.isEmpty()) return null
        val absolute = arg.startsWith("/") || windowsAbsolutePattern.containsMatchIn(arg)
        val raw = if (absolute) File(arg) else File(cwd, arg)
        val resolved = runCatching { raw.canonicalFile }.getOrNull() ?: return null
        return if (isInside(root, resolved)) resolved else null
    }

    /** Windows drive-letter paths (unit tests on a Windows host). */
    private val windowsAbsolutePattern = Regex("^[A-Za-z]:[\\\\/]")

    private fun isInside(root: File, candidate: File): Boolean {
        // Both sides must be canonical: the candidate already is (canonicalFile),
        // and the root is canonicalized here so non-canonical prefixes (temp
        // dirs, 8.3 names, case differences) cannot make a real inside-path
        // compare as outside. Compare with the separator: startsWith on the
        // bare root would also accept a sibling named "home_evil".
        val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        return candidate.absolutePath == rootPath ||
            candidate.absolutePath.startsWith(rootPath + File.separator)
    }

    /**
     * Validates a whitespace-tokenized command line.
     *
     * @return null when the command is allowed, or the human-readable refusal
     *         line to print into the terminal.
     */
    fun validate(root: File, cwd: File, tokens: List<String>): String? {
        val program = tokens.firstOrNull() ?: return "no command"
        if (program !in ALLOWED_PROGRAMS) {
            return "$program: not allowed — sandboxed terminal offers: " +
                ALLOWED_PROGRAMS.joinToString(" ")
        }
        if (program in PATH_TAKING_PROGRAMS) {
            // Non-flag arguments are path operands; flags (-n 5, --bytes) are not.
            val targets = tokens.drop(1).filter { !it.startsWith("-") }
            for (t in targets) {
                if (resolveInRoot(root, cwd, t) == null) {
                    return "$program: $t: outside sandbox (${root.absolutePath})"
                }
            }
        }
        return null
    }
}

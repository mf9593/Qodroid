/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.engine.guest

import com.pxkf.qodroid.engine.VmEngine
import com.termux.terminal.TerminalSession

/**
 * Talks to the guest's login shell over the existing terminal session.
 *
 * The guest is not handed a ready shell: `podroid login:` appears first and
 * expects a username, then a password, and only then is there a prompt to run
 * anything in. Sending a command to a session sitting at the login prompt types
 * it into the username field — the shell then asks for a password and the
 * command text ends up in *that* field instead. The result is a login attempt
 * with `ls -la '/'` as the username and a permanently broken session.
 *
 * So every request goes through [ensureLoggedIn] first, which walks the
 * transcript and answers whatever prompt is showing before any command is sent.
 */
class GuestShell(private val engine: VmEngine) {

    private val session: TerminalSession? get() = engine.terminalSession

    /** Guest credentials, matching what the bundled Alpine image advertises. */
    private val user = DEFAULT_USER
    private val password = DEFAULT_PASSWORD

    /**
     * Walk the login sequence until a shell prompt is showing.
     *
     * Returns false when the session is gone, the transcript does not look like
     * a login prompt (so we are already at a shell), or the sequence did not
     * converge within [timeoutMs].
     */
    suspend fun ensureLoggedIn(timeoutMs: Long = LOGIN_TIMEOUT_MS): Boolean {
        val s = session ?: return false
        if (!s.isRunning) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSeen = ""
        var idle = 0

        while (System.currentTimeMillis() < deadline) {
            val tail = transcript(s)
            if (tail == lastSeen) {
                if (++idle > MAX_IDLE_TICKS) return atShellPrompt(tail)
            } else {
                idle = 0
                lastSeen = tail
            }

            when {
                // Password prompt first: its text also contains "login:" in the
                // banner above, so order matters.
                tail.containsLast(PASSWORD_MARKER) -> s.write("$password\r\n")
                tail.endsWith(LOGIN_MARKER) || tail.endsWith("$LOGIN_MARKER ") ->
                    s.write("$user\r\n")
                else -> return atShellPrompt(tail)
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
        return atShellPrompt(transcript(s))
    }

    /**
     * Send [command] to the guest and return its stdout+stderr.
     *
     * A [GuestCommandResult.Failure] names the stage that failed rather than a
     * blanket "could not read": every failure mode here has a different fix (start
     * the VM, open the terminal, log in, or raise the timeout) and collapsing
     * them into one message sends the user looking in the wrong place.
     */
    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): GuestCommandResult {
        val s = session ?: return GuestCommandResult.NoSession
        if (!s.isRunning) return GuestCommandResult.NoSession
        if (!ensureLoggedIn()) return GuestCommandResult.LoginFailed

        val token = nextToken()
        val (script, beginMarker, endMarker) = frame(command, token)
        val bytes = script.toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)

        val deadline = System.currentTimeMillis() + timeoutMs
        // Silence is evidence of a hung shell only when the budget is short.
        // MAX_IDLE_TICKS x POLL_MS is 3.2 s, which suits the 8 s default — but a
        // whole-filesystem tar is quiet for minutes, so that rule cut the one
        // backup that actually works off after 3 seconds and reported a timeout
        // instead. Allow silence up to half the whole budget, never less than
        // the old threshold.
        val maxIdleTicks = (timeoutMs / POLL_MS / 2).toInt().coerceAtLeast(MAX_IDLE_TICKS)
        var lastSeen = -1
        var idle = 0
        while (System.currentTimeMillis() < deadline) {
            val text = transcript(s)
            extractOutput(text, beginMarker, endMarker)?.let {
                return GuestCommandResult.Ok(it)
            }
            // No new output for a long stretch means the shell is idle or gone.
            if (text.length == lastSeen) {
                if (++idle > maxIdleTicks) return GuestCommandResult.Timeout
            } else {
                idle = 0
                lastSeen = text.length
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
        return GuestCommandResult.Timeout
    }

    /**
     * True when the transcript ends in something that accepts a command.
     *
     * Deliberately conservative: it accepts the markers but not an arbitrary
     * string, because running a listing against an unrecognised prompt is what
     * produced the broken login in the first place.
     */
    private fun atShellPrompt(tail: String): Boolean = sniff(tail)

    /** True when [marker] appears in the last non-blank line. */
    private fun String.containsLast(marker: String): Boolean = lastLineHas(this, marker)

    /**
     * Tail of the emulator's scrollback.
     *
     * WithoutJoinedLines, not the joined variant: the joined one merges rows
     * that the emulator wrapped purely for display, which would corrupt every
     * listing row longer than the terminal width.
     */
    private fun transcript(session: TerminalSession): String =
        runCatching { session.emulator.screen.transcriptTextWithoutJoinedLines }
            .getOrDefault("")
            .lines()
            .takeLast(TAIL_LINES)
            .joinToString("\n")

    private fun nextToken(): String =
        "__PODROID_${java.lang.Long.toHexString(tokenSeq.incrementAndGet())}_"

    private val tokenSeq = java.util.concurrent.atomic.AtomicLong(0)

    companion object {
        /**
         * Boot credentials advertised by the bundled Alpine image
         * ("Default login: root / podroid"). Kept as constants rather than being
         * discovered, because the image is shipped with the APK and does not
         * change.
         */
        private const val DEFAULT_USER = "root"
        private const val DEFAULT_PASSWORD = "podroid"

        internal const val LOGIN_MARKER_FOR_TEST = "podroid login:"
        internal const val PASSWORD_MARKER_FOR_TEST = "Password:"
        private const val LOGIN_MARKER = LOGIN_MARKER_FOR_TEST
        private const val PASSWORD_MARKER = PASSWORD_MARKER_FOR_TEST

        /**
         * What a usable prompt ends with. The bundled image gives a root shell
         * (`podroid:~#`), but a non-root login differs, so both sigils are
         * accepted with and without the trailing space the emulator inserts.
         */
        private val SHELL_PROMPT_MARKERS = listOf("#", "$", "❯", ">")

        private const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val LOGIN_TIMEOUT_MS = 6_000L
        private const val POLL_MS = 80L
        private const val MAX_IDLE_TICKS = 40
        private const val TAIL_LINES = 200

        /**
         * Build the framed script and its two markers.
         *
         * `$P` is written literally, never interpolated here — the shell has
         * to expand it, and only the expansion carries the marker.
         */
        fun frame(command: String, token: String): Triple<String, String, String> {
            val end = token + "end"
            val script = "P=$token; printf '%s\\n' \"\$P\"; $command; printf '%s\\n' \"\${P}end\"\r\n"
            return Triple(script, token, end)
        }

        /**
         * Pull the output out from between the two markers.
         *
         * Returns null while the closing marker has not arrived, and null when
         * the opening marker is missing too — a partial transcript must not yield
         * whatever happens to precede the end marker.
         *
         * Both markers are matched as whole lines rather than by substring. The
         * echo contains `$P` and `${P}end` as separate words and must not match,
         * a real PTY terminates lines with `\r\n` so `\n`+marker+`\n` does not
         * match a genuine marker line, and the opening marker can be the very
         * first thing in the scrollback with no preceding newline.
         */
        fun extractOutput(transcript: String, beginMarker: String, endMarker: String): String? {
            val endMatch = lineRegex(endMarker).findAll(transcript).lastOrNull() ?: return null
            val end = endMatch.range.first
            val beginMatch = lineRegex(beginMarker).findAll(transcript)
                .lastOrNull { it.range.last < end } ?: return null
            return transcript
                .substring(beginMatch.range.last + 1, end)
                .trim('\n', '\r')
        }

        private fun lineRegex(marker: String) = Regex("(?m)^" + Regex.escape(marker) + "\r?$")

        /**
         * Whether the transcript's last line is a shell prompt rather than a
         * prompt we still owe an answer to.
         *
         * Only the LAST line is examined. The login banner contains '#' and
         * '$' in its own prose ("Change root password: passwd"), so scanning the
         * whole tail concludes a shell exists while the session is still
         * sitting at a login form — which is what typed a directory listing
         * into the username field.
         *
         * Pure and internal so it is unit-testable without a guest.
         */
        internal fun sniff(tail: String): Boolean {
            // Everything from the LAST auth prompt onward. A shell prompt
            // appearing in that window is what "we are in" means.
            //
            // Scanning only the final line is wrong in the other direction: after
            // `ls` of a populated directory the last line is a file name, not a
            // prompt, and the next request would conclude the shell was never
            // logged in.
            val lastAuth = maxOf(
                tail.lastIndexOf(PASSWORD_MARKER),
                tail.lastIndexOf(LOGIN_MARKER),
            )
            if (lastAuth >= 0) {
                val after = tail.substring(lastAuth)
                // Still stuck on an auth prompt: no shell sigil after it.
                val lastLine = after.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                if (lastLine.contains(PASSWORD_MARKER) ||
                    lastLine.trimEnd().endsWith(LOGIN_MARKER)
                ) return false
                return after.lineSequence().any { line ->
                    val t = line.trimEnd()
                    SHELL_PROMPT_MARKERS.any { t.endsWith(it) }
                }
            }
            // No auth prompt in view at all — already a shell (repeat login).
            val last = tail.lineSequence().lastOrNull { it.isNotBlank() } ?: return false
            val trimmed = last.trimEnd()
            return SHELL_PROMPT_MARKERS.any { trimmed.endsWith(it) }
        }

        internal fun lastLineHas(text: String, marker: String): Boolean =
            text.lineSequence().lastOrNull { it.isNotBlank() }?.contains(marker) == true
    }
}
/**
 * Outcome of one guest command.
 *
 * Split by stage because the remedy differs per stage, and "could not read the
 * filesystem" pointed the user at the VM when the real problem was an unlogged
 * -in shell.
 */
sealed interface GuestCommandResult {
    data class Ok(val output: String) : GuestCommandResult

    /** No live terminal session — the bridge has not started, or the VM is down. */
    data object NoSession : GuestCommandResult

    /** The session is alive but never reached a shell (login never completed). */
    data object LoginFailed : GuestCommandResult

    /** A shell was reached, but the command produced no parsable output in time. */
    data object Timeout : GuestCommandResult
}

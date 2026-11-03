package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.io.StringWriter

/**
 * [UbisoftDiagScan] and the small pure parts of the Ubisoft report: redaction, registry sections, folder walks and
 * the before/after comparison, copy rules, logcat and `/proc` parsing, and the deep-trace channel rule.
 */
class UbisoftDiagScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hive = """
WINE REGISTRY Version 2
;; All keys relative to \\Machine

[Software\\Wow6432Node\\Ubisoft\\Launcher] 1793630773
#time=1dd6ad8e82cadc0
"InstallDir"="C:\\Program Files (x86)\\Ubisoft\\Ubisoft Game Launcher\\"
"UserToken"="abcdef"

[Software\\Wow6432Node\\Valve\\Steam] 1793628533
#time=1dd6ad3b139d090
"InstallPath"="C:\\Steam"

[Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Uplay Install 1875] 1793632643
#time=1dd6add431606e2
"DisplayName"="Assassin's Creed Syndicate"
""".trimIndent()

    // ---- Redaction ------------------------------------------------------------------------------

    @Test
    fun yamlSecretsAreReplacedAndTheRestIsKept() {
        val yaml = "user:\n  username: bob\n  password: hunter2\n  rememberMe: true\noverlay:\n  enabled: false\n" +
            "session:\n  id: abc\n  more:\n    x: 1\nlanguage: en\n"
        val out = UbisoftDiagScan.redactYaml(yaml)
        assertEquals(
            "user:\n  username: <redacted>\n  password: <redacted>\n  rememberMe: true\noverlay:\n  enabled: false\n" +
                "session:\n  <redacted>\n  <redacted>\n    <redacted>\nlanguage: en\n",
            out,
        )
    }

    @Test
    fun logLinesLoseTokensAndKeepEverythingElse() {
        assertEquals("token=<redacted> user=x", UbisoftDiagScan.redactLine("token=abc12345 user=x"))
        assertFalse(UbisoftDiagScan.redactLine("Authorization: Bearer abcdefghijkl").contains("abcdefghijkl"))
        assertFalse(UbisoftDiagScan.redactLine("jwt eyJhbGciOiJI.eyJzdWIiOiIx.c2ln here").contains("eyJzdWIi"))
        assertEquals("loaded 12 files from the cache", UbisoftDiagScan.redactLine("loaded 12 files from the cache"))
        assertEquals("short", UbisoftDiagScan.redactLine("short"))
    }

    @Test
    fun secretNames() {
        for (n in listOf("Password", "AccessToken", "client_secret", "ExchangeCode", "AuthTicket", "Cookie", "SessionId")) {
            assertTrue(n, UbisoftDiagScan.isSecretName(n))
        }
        assertFalse(UbisoftDiagScan.isSecretName("InstallDir"))
    }

    // ---- Registry -------------------------------------------------------------------------------

    @Test
    fun registrySectionsKeepOnlyUbisoftKeysWithTheirTimestampsAndHideSecrets() {
        val r = UbisoftDiagScan.registrySections(BufferedReader(StringReader(hive)), 100_000)
        assertEquals(
            listOf(
                "Software\\Wow6432Node\\Ubisoft\\Launcher",
                "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Uplay Install 1875",
            ),
            r.keys,
        )
        assertTrue(r.text.contains("] 1793630773"))
        assertTrue(r.text.contains("#time=1dd6ad8e82cadc0"))
        assertTrue(r.text.contains("\"InstallDir\"="))
        assertTrue(r.text.contains("\"UserToken\"=<redacted>"))
        assertFalse(r.text.contains("abcdef"))
        assertFalse(r.text.contains("Valve"))
        assertFalse(r.truncated)
    }

    @Test
    fun missingKeysAreReportedByHasKey() {
        val r = UbisoftDiagScan.registrySections(BufferedReader(StringReader(hive)), 100_000)
        assertTrue(UbisoftDiagScan.hasKey(r.keys, "software\\wow6432node\\ubisoft\\launcher"))
        assertFalse(UbisoftDiagScan.hasKey(r.keys, "Software\\Wow6432Node\\Ubisoft\\Launcher\\Installs\\1875"))
    }

    @Test
    fun registrySectionsStopAtTheSizeLimit() {
        val r = UbisoftDiagScan.registrySections(BufferedReader(StringReader(hive)), 10)
        assertEquals(1, r.keys.size)
        assertTrue(r.truncated)
    }

    @Test
    fun registryCopyKeepsEverythingButSecretValues() {
        val text = "[A\\\\Ubisoft] 1\n#time=1\n\"Token\"=hex(7):41,00,\\\n  42,00,00,00\n\"Other\"=\"x\"\n"
        val w = StringWriter()
        val c = UbisoftDiagScan.copyRegistry(BufferedReader(StringReader(text)), w, 1_000_000)
        assertEquals(1, c.redacted)
        assertFalse(c.truncated)
        assertEquals("[A\\\\Ubisoft] 1\n#time=1\n\"Token\"=<redacted>\n  <redacted>\n\"Other\"=\"x\"\n", w.toString())
    }

    @Test
    fun registryCopyStopsAtTheLimit() {
        val text = (1..100).joinToString("\n") { "\"v$it\"=\"x\"" } + "\n"
        val c = UbisoftDiagScan.copyRegistry(BufferedReader(StringReader(text)), StringWriter(), 50)
        assertTrue(c.truncated)
    }

    // ---- Walks and the comparison ---------------------------------------------------------------

    private fun walkAll(root: File): Map<String, UbisoftDiagScan.Entry> {
        val out = LinkedHashMap<String, UbisoftDiagScan.Entry>()
        UbisoftDiagScan.walk(root, 8, 1000, Long.MAX_VALUE) { p, e -> out[p] = e }
        return out
    }

    @Test
    fun aWalkListsEverythingSortedAndAnEntryRoundTrips() {
        val root = tmp.newFolder("prefix")
        File(root, "b.txt").writeText("hello")
        File(root, "dir").mkdirs()
        File(root, "dir/a b.dat").writeBytes(ByteArray(300))
        val all = walkAll(root)
        assertEquals(listOf("b.txt", "dir", "dir/a b.dat"), all.keys.toList())
        assertEquals(5L, all.getValue("b.txt").size)
        assertEquals('d', all.getValue("dir").kind)
        val line = UbisoftDiagScan.formatEntry("dir/a b.dat", all.getValue("dir/a b.dat"))
        val back = UbisoftDiagScan.parseEntryLine(line)
        assertNotNull(back)
        assertEquals("dir/a b.dat", back!!.first)
        assertEquals(300L, back.second.size)
        assertEquals('f', back.second.kind)
        assertEquals(all.getValue("dir/a b.dat").mtime / 1000, back.second.mtime / 1000)
        assertNull(UbisoftDiagScan.parseEntryLine("# a header line"))
    }

    @Test
    fun theDepthAndEntryCapsAreReported() {
        val root = tmp.newFolder("deep")
        File(root, "a/b/c").mkdirs()
        File(root, "a/b/c/f.txt").writeText("x")
        val seen = ArrayList<String>()
        val r = UbisoftDiagScan.walk(root, 2, 1000, Long.MAX_VALUE) { p, _ -> seen += p }
        assertEquals(listOf("a", "a/b"), seen)
        assertEquals(1, r.depthLimited)
        val capped = UbisoftDiagScan.walk(root, 8, 2, Long.MAX_VALUE) { _, _ -> }
        assertEquals(2, capped.entries)
        assertEquals(1, capped.omitted)
        assertTrue(UbisoftDiagScan.describeWalk(r).contains("depth cap"))
    }

    @Test
    fun theComparisonFindsAddedChangedAndRemovedFiles() {
        val root = tmp.newFolder("cmp")
        File(root, "same.txt").writeText("same")
        File(root, "gone.txt").writeText("gone")
        File(root, "grows.txt").writeText("one")
        val before = walkAll(root)

        File(root, "gone.txt").delete()
        File(root, "grows.txt").writeText("one and more")
        File(root, "new.txt").writeText("new")
        val cmp = UbisoftDiagScan.Comparison(before)
        UbisoftDiagScan.walk(root, 8, 1000, Long.MAX_VALUE) { p, e -> cmp.onNow(p, e) }
        cmp.finish(true)

        assertEquals(1, cmp.addedCount)
        assertEquals(1, cmp.changedCount)
        assertEquals(1, cmp.removedCount)
        assertEquals(1, cmp.unchangedCount)
        assertTrue(cmp.added.single().endsWith("new.txt"))
        assertTrue(cmp.removed.single().endsWith("gone.txt"))
        val text = cmp.render("test", true)
        assertTrue(text.contains("added=1 changed=1 removed=1 unchanged=1"))
    }

    @Test
    fun anIncompleteWalkDoesNotClaimRemovals() {
        val cmp = UbisoftDiagScan.Comparison(mapOf("x" to UbisoftDiagScan.Entry(1, 0, 'f', "?", null)))
        cmp.finish(false)
        assertEquals(0, cmp.removedCount)
        assertTrue(cmp.render("t", false).contains("unknown"))
    }

    // ---- Files ----------------------------------------------------------------------------------

    @Test
    fun theCopyRulesSeparateTextBinaryAndCredentialFiles() {
        val a = UbisoftDiagScan.CopyAction.values()
        assertEquals(5, a.size)
        assertEquals(UbisoftDiagScan.CopyAction.HASH_BINARY, UbisoftDiagScan.copyAction("x/y/upc.dll", 10))
        assertEquals(UbisoftDiagScan.CopyAction.TEXT_REDACTED, UbisoftDiagScan.copyAction("x/settings.yaml", 100))
        assertEquals(UbisoftDiagScan.CopyAction.TEXT_REDACTED, UbisoftDiagScan.copyAction("x/logs/launcher_session.log", 100))
        assertEquals(UbisoftDiagScan.CopyAction.HASH_SENSITIVE, UbisoftDiagScan.copyAction("x/users.dat", 100))
        assertEquals(UbisoftDiagScan.CopyAction.RAW, UbisoftDiagScan.copyAction("x/cache/store.db", 100))
        assertEquals(UbisoftDiagScan.CopyAction.HASH_LARGE, UbisoftDiagScan.copyAction("x/big.log", 3L * 1024 * 1024))
    }

    @Test
    fun sha256AndTails() {
        val f = tmp.newFile("t.log")
        f.writeText("line one\nline two\nline three\n")
        assertEquals(64, UbisoftDiagScan.sha256(f, 1000).length)
        assertTrue(UbisoftDiagScan.sha256(f, 3).startsWith("(not hashed"))
        val whole = UbisoftDiagScan.readTail(f, 1000)!!
        assertEquals("line one\nline two\nline three\n", whole.text)
        assertFalse(whole.cut)
        val tail = UbisoftDiagScan.readTail(f, 15)!!
        assertTrue(tail.cut)
        assertEquals("line three\n", tail.text)
        val bin = tmp.newFile("b.bin")
        bin.writeBytes(byteArrayOf(1, 0, 2, 0, 3))
        assertTrue(UbisoftDiagScan.readTail(bin, 100)!!.binary)
    }

    // ---- Logcat, /proc, limits ------------------------------------------------------------------

    @Test
    fun logcatLinesAreMatchedByTagOrByWord() {
        val tags = listOf("UbisoftSupport", "BH_REALSTEAM")
        assertTrue(UbisoftDiagScan.logcatLineWanted("11-03 10:00:00.123 I/UbisoftSupport( 1234): hello", tags))
        assertTrue(UbisoftDiagScan.logcatLineWanted("11-03 10:00:00.123 W/BH_REALSTEAM( 1234): x", tags))
        assertTrue(UbisoftDiagScan.logcatLineWanted("11-03 10:00:00.123 D/Other( 1): started Uplay r2 loader", tags))
        assertFalse(UbisoftDiagScan.logcatLineWanted("11-03 10:00:00.123 D/Other( 1): nothing here", tags))
    }

    @Test
    fun procStatIsParsedEvenWithSpacesInTheName() {
        val f = MutableList(22) { "0" }
        f[0] = "S"; f[1] = "1"; f[11] = "111"; f[12] = "222"; f[17] = "5"; f[20] = "123456"; f[21] = "789"
        val s = UbisoftDiagScan.parseProcStat("1234 (wine pre (loader)) " + f.joinToString(" "))
        assertEquals(UbisoftDiagScan.ProcStat(1234, "wine pre (loader)", 'S', 1, 111, 222, 5, 123456, 789), s)
        assertNull(UbisoftDiagScan.parseProcStat("garbage"))
        assertTrue(UbisoftDiagScan.formatProc(s!!, "cmd").contains("threads=5"))
    }

    @Test
    fun statusSummaryKeepsTheUsefulLines() {
        val status = "Name:\twine\nState:\tD (disk sleep)\nThreads:\t12\nVmRSS:\t  3000 kB\nUid:\t1 1 1 1\n"
        val out = UbisoftDiagScan.statusSummary(status)
        assertTrue(out.contains("State=D (disk sleep)"))
        assertTrue(out.contains("Threads=12"))
        assertTrue(out.contains("VmRSS=3000 kB"))
        assertFalse(out.contains("Uid"))
    }

    @Test
    fun textIsCutOnACharacterBoundary() {
        assertEquals("h" to true, UbisoftDiagScan.capUtf8("h\u00e9llo", 2))
        assertEquals("abc" to false, UbisoftDiagScan.capUtf8("abc", 10))
        assertEquals("" to true, UbisoftDiagScan.capUtf8("abc", 0))
    }

    // ---- Deep trace, outcome, chain -------------------------------------------------------------

    @Test
    fun deepTraceAddsOnlyTheMissingChannelsToAPlusList() {
        assertEquals("+timestamp,+loaddll,+reg,+file", UbisoftDiag.withDeepTrace("+timestamp,+loaddll"))
        assertEquals("+reg,+file", UbisoftDiag.withDeepTrace("+reg"))
        assertEquals("+reg,+file", UbisoftDiag.withDeepTrace("+reg,+file"))
        assertNull(UbisoftDiag.withDeepTrace("-all"))
        assertNull(UbisoftDiag.withDeepTrace(""))
        assertNull(UbisoftDiag.withDeepTrace(null))
    }

    @Test
    fun theRegistrationOutcomeReadsInWords() {
        val done = UbisoftInstallRegistry.Outcome(UbisoftInstallRegistry.Result.REGISTERED, "11 of 11 value(s) written")
        assertTrue(done.changed)
        assertEquals("REGISTERED: 11 of 11 value(s) written", done.toString())
        val already = UbisoftInstallRegistry.Outcome(UbisoftInstallRegistry.Result.ALREADY_PRESENT, "")
        assertFalse(already.changed)
        assertEquals("ALREADY_PRESENT", already.toString())
        val skipped = UbisoftInstallRegistry.Outcome(UbisoftInstallRegistry.Result.SKIPPED_NOT_UBISOFT, "no shortcut")
        assertEquals(skipped, UbisoftInstallRegistry.record(skipped))
        assertEquals(skipped, UbisoftInstallRegistry.last())
    }

    @Test
    fun theSteamChainIsRecognisedFromThePlanEnv() {
        assertTrue(UbisoftSupport.steamChainArmed(mapOf(EaSupport.CHAIN_ENV to UbisoftSupport.CHAIN_VALUE)))
        assertFalse(UbisoftSupport.steamChainArmed(mapOf(EaSupport.CHAIN_ENV to EaSupport.CHAIN_VALUE)))
        assertFalse(UbisoftSupport.steamChainArmed(emptyMap()))
        assertFalse(UbisoftSupport.steamChainArmed(null))
    }

    @Test
    fun theInstallHashIsFieldOneAsTextOrHex() {
        assertEquals("abc", UbisoftInstallState.installHash(byteArrayOf(0x0A, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())))
        assertEquals("01ff", UbisoftInstallState.installHash(byteArrayOf(0x0A, 2, 0x01, 0xFF.toByte())))
        assertNull(UbisoftInstallState.installHash(byteArrayOf(0x10, 1)))
    }
}

package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `steam-host-builds.json` (winlator-contents) → [SteamHostComponent.parseCatalog]: the accepted
 * Valve builds for Headless Steam, each with the package sha2 it was verified with and the host
 * slot line, plus a `revoked` kill-switch list. Malformed rows are skipped, never fatal.
 */
class SteamHostCatalogTest {

    @Test
    fun parsesBuildsAndRevoked() {
        val c = SteamHostComponent.parseCatalog(
            """
            {"schemaVersion":1,"category":"steam-host-builds",
             "builds":[
               {"version":"1791918634","sha2":"EBB41B3917009CC597CFDD7B76E32F0D6DBD73DF7AC07648113E0DC137395E05",
                "slots":"engine=8,13,17 user=1,4,49,50,54,56,69 apps=7 friends=5","source":"manual"},
               {"version":"not-a-number","sha2":"x"},
               {"version":"1799999999","sha2":"short","slots":""}
             ],
             "revoked":["1788291500"]}
            """.trimIndent()
        )!!
        assertEquals(setOf("1791918634", "1799999999"), c.builds.keys)
        val b = c.builds.getValue("1791918634")
        assertEquals("ebb41b3917009cc597cfdd7b76e32f0d6dbd73df7ac07648113e0dc137395e05", b.sha2)
        assertEquals("engine=8,13,17 user=1,4,49,50,54,56,69 apps=7 friends=5", b.slots)
        assertEquals("", c.builds.getValue("1799999999").sha2)
        assertTrue("1788291500" in c.revoked)
    }

    @Test
    fun rejectsDocumentsWithoutBuilds() {
        assertNull(SteamHostComponent.parseCatalog("""{"schemaVersion":1}"""))
        assertNull(SteamHostComponent.parseCatalog("not json"))
    }
}

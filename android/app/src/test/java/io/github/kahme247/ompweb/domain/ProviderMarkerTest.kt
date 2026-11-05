package io.github.kahme247.ompweb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The badge in every provider-usage/account row is derived, never stored: the
 * known providers share a fixed monogram, an alias omp appended to the id
 * stays visible, and an unknown id falls back to its first two letters.
 */
class ProviderMarkerTest {

    @Test
    fun knownProvidersUseTheirFixedMonogram() {
        assertEquals("CC", providerMarker("commandcode").monogram)
        assertEquals("OA", providerMarker("openai").monogram)
        assertEquals("OA", providerMarker("openai-codex").monogram)
        assertEquals("AN", providerMarker("anthropic").monogram)
        assertEquals("GO", providerMarker("google").monogram)
        assertEquals("GO", providerMarker("gemini").monogram)
        assertEquals("XA", providerMarker("xai").monogram)
        assertEquals("DS", providerMarker("deepseek").monogram)
        assertEquals("OR", providerMarker("openrouter").monogram)
        assertEquals("MI", providerMarker("mistral").monogram)
    }

    @Test
    fun anAliasBecomesASmallSuffixAndKeepsTheProviderMonogram() {
        assertEquals(ProviderMarker("CC", "a", "commandcode-a", "commandcode"), providerMarker("commandcode-a"))
        assertEquals("b", providerMarker("commandcode_b").alias)
        assertEquals("CC", providerMarker("commandcode_b").monogram)
        assertEquals(
            ProviderMarker("OA", "c", "openai-codex-c", "openai-codex"),
            providerMarker("openai-codex-c"),
        )
        assertEquals("a", providerMarker("acme-a").alias)
        assertEquals("AC", providerMarker("acme-a").monogram)
        assertEquals("acme", providerMarker("acme-a").baseId)
    }

    @Test
    fun aLongerLastPartIsPartOfTheProviderId() {
        assertNull(providerMarker("openai-codex").alias)
        assertNull(providerMarker("deepseek-r1").alias)
        assertEquals("ZA", providerMarker("z-ai").monogram)
        assertEquals("DS", providerMarker("deepseek-r1").monogram)
    }

    @Test
    fun unknownProvidersUseTheirFirstTwoLetters() {
        assertEquals("AC", providerMarker("acme-provider").monogram)
        assertEquals("PR", providerMarker("provider").monogram)
        assertEquals("K2", providerMarker("k2").monogram)
        assertEquals("Z", providerMarker("Z").monogram)
        assertEquals("?", providerMarker("").monogram)
        assertNull(providerMarker("").alias)
    }

    @Test
    fun theLabelKeepsTheIdAsOmpReportedIt() {
        assertEquals("CommandCode-A", providerMarker(" CommandCode-A ").label)
        assertEquals("commandcode", providerMarker(" commandcode ").label)
        assertEquals("a", providerMarker(" CommandCode-A ").alias)
    }
}

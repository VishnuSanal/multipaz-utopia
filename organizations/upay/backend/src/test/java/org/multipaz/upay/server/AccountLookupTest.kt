package org.multipaz.upay.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential

/**
 * Shape tests for the DCQL query [AccountLookup] sends to the wallet.
 *
 * The query is the whole privacy contract of this flow — it is what the holder is shown and asked
 * to consent to. Asking for more than two claims, or attaching transaction data, would make a
 * read-only view look like a payment.
 */
class AccountLookupTest {

    @Test
    fun requestsThePaymentCardUnderTheAccountCredentialId() {
        val credential = soleCredential(lookupRequest())
        // The id is what routes the response back to this flow. See UPayVerifierAssistant.
        assertEquals("account", credential["id"]!!.jsonPrimitive.content)
        assertEquals("mso_mdoc", credential["format"]!!.jsonPrimitive.content)
        assertEquals(
            DigitalPaymentCredential.CARD_DOCTYPE,
            credential["meta"]!!.jsonObject["doctype_value"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun requestsExactlyTheTwoClaimsTheViewNeeds() {
        val claims = soleCredential(lookupRequest())["claims"]!!.jsonArray
        val paths = claims.map { claim ->
            claim.jsonObject["path"]!!.jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(
            listOf(
                listOf(DigitalPaymentCredential.CARD_NAMESPACE, "payment_instrument_id"),
                listOf(DigitalPaymentCredential.CARD_NAMESPACE, "holder_name")
            ),
            paths
        )
    }

    @Test
    fun retainsNothing() {
        val claims = soleCredential(lookupRequest())["claims"]!!.jsonArray
        for (claim in claims) {
            assertFalse(
                "a read-only view must not ask to retain anything",
                claim.jsonObject["intent_to_retain"]!!.jsonPrimitive.booleanOrNull!!
            )
        }
    }

    @Test
    fun carriesNoTransactionData() {
        // Transaction data is what makes a presentment a payment. A lookup must never carry it.
        assertNull(lookupRequest()["transaction_data"])
    }

    @Test
    fun carriesProtocolsThroughWhenTheFrontEndAsksForThem() {
        val request = buildJsonObject {
            putJsonArray("protocols") { add("openid4vp-v1") }
        }
        val protocols = lookupRequest(request)["protocols"]!!.jsonArray
        assertEquals(listOf("openid4vp-v1"), protocols.map { it.jsonPrimitive.content })
    }

    @Test
    fun omitsProtocolsWhenTheFrontEndDoesNot() {
        // Absent `protocols` lets the framework offer every protocol it supports.
        assertNull(lookupRequest()["protocols"])
    }

    private fun lookupRequest(request: JsonObject = buildJsonObject { }): JsonObject =
        runBlocking { AccountLookup.processRequest(request) }

    private fun soleCredential(request: JsonObject): JsonObject {
        val credentials = request["dcql"]!!.jsonObject["credentials"]!!.jsonArray
        assertEquals("a lookup asks for one credential", 1, credentials.size)
        return credentials[0].jsonObject
    }
}

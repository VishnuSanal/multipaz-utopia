package org.multipaz.upay.server

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * Routing tests for [UPayVerifierAssistant].
 *
 * These matter more than they look: a request routed to the wrong assistant either debits an
 * account that only meant to look at itself, or hands a payment response to a read-only path.
 */
class UPayVerifierAssistantTest {

    // --- Request side: dispatch on `intent` ---

    @Test
    fun absentIntent_routesToPayment() {
        // The pay page and the marketplace checkout send no `intent` at all. They must keep
        // reaching the payment flow, unchanged, forever.
        val request = buildJsonObject {
            put("payee_account", "12345678")
            put("amount", 10.0)
        }
        assertSame(TransactionProcessor, UPayVerifierAssistant.assistantForRequest(request))
    }

    @Test
    fun explicitPaymentIntent_routesToPayment() {
        val request = buildJsonObject { put("intent", "payment") }
        assertSame(TransactionProcessor, UPayVerifierAssistant.assistantForRequest(request))
    }

    @Test
    fun accountLookupIntent_routesToLookup() {
        val request = buildJsonObject { put("intent", "account_lookup") }
        assertSame(AccountLookup, UPayVerifierAssistant.assistantForRequest(request))
    }

    @Test
    fun unknownIntent_isRejected() {
        // Deliberately not a fallback to payment: a typo must not become a debit.
        val request = buildJsonObject { put("intent", "acount_lookup") }
        assertThrows(InvalidRequestException::class.java) {
            UPayVerifierAssistant.assistantForRequest(request)
        }
    }

    @Test
    fun nonStringIntent_isRejected() {
        val request = buildJsonObject { putJsonArray("intent") { add("payment") } }
        assertThrows(InvalidRequestException::class.java) {
            UPayVerifierAssistant.assistantForRequest(request)
        }
    }

    // --- Response side: dispatch on the DCQL credential id ---

    @Test
    fun paymentCredentialId_routesToPayment() {
        assertSame(TransactionProcessor, UPayVerifierAssistant.assistantForResponse(dcqlWithId("payment")))
    }

    @Test
    fun accountCredentialId_routesToLookup() {
        assertSame(AccountLookup, UPayVerifierAssistant.assistantForResponse(dcqlWithId("account")))
    }

    @Test
    fun unknownCredentialId_isRejected() {
        assertThrows(InvalidRequestException::class.java) {
            UPayVerifierAssistant.assistantForResponse(dcqlWithId("drivers_license"))
        }
    }

    @Test
    fun dcqlWithoutCredentials_isRejected() {
        assertThrows(InvalidRequestException::class.java) {
            UPayVerifierAssistant.assistantForResponse(buildJsonObject { })
        }
    }

    private fun dcqlWithId(id: String) = buildJsonObject {
        putJsonArray("credentials") {
            addJsonObject {
                put("id", id)
                put("format", "mso_mdoc")
            }
        }
    }
}

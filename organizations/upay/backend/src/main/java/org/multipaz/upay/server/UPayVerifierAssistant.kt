package org.multipaz.upay.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.customization.VerifierPresentment

/**
 * Routes verifier traffic to [TransactionProcessor] (payment) or [AccountLookup] (read-only
 * account view).
 *
 * The request side dispatches on `intent`; the response side dispatches on the DCQL credential id,
 * because [VerifierPresentment] does not carry the `intent`.
 */
internal object UPayVerifierAssistant: VerifierAssistant {
    override suspend fun processRequest(request: JsonObject): JsonObject? =
        assistantForRequest(request).processRequest(request)

    override suspend fun processResponse(presentment: VerifierPresentment): JsonObject? =
        assistantForResponse(presentment.dcql).processResponse(presentment)

    /**
     * Picks the assistant named by the request's `intent`. An absent `intent` means payment, which
     * is what keeps the pay page and the marketplace checkout working unchanged.
     *
     * @param request the JSON request as sent by the front-end
     * @throws InvalidRequestException if `intent` is present but is not a known flow name. Note
     *   that a present-but-unusable `intent` is rejected rather than inheriting the payment
     *   default, so a malformed lookup never becomes a debit.
     */
    fun assistantForRequest(request: JsonObject): VerifierAssistant {
        val intent = when (val value = request["intent"]) {
            null -> INTENT_PAYMENT
            is JsonPrimitive if value.isString -> value.content
            else -> throw InvalidRequestException("'intent' must be a string, got: $value")
        }
        return when (intent) {
            INTENT_PAYMENT -> TransactionProcessor
            INTENT_ACCOUNT_LOOKUP -> AccountLookup
            else -> throw InvalidRequestException("'intent' names no known flow: '$intent'")
        }
    }

    /**
     * Picks the assistant that built the DCQL query behind a presentment.
     *
     * @param dcql the DCQL query that produced the presentment
     * @throws InvalidRequestException if the query carries no credential id this server issues
     */
    fun assistantForResponse(dcql: JsonObject): VerifierAssistant {
        val credentialIds = (dcql["credentials"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content
        }
        return when {
            credentialIds.contains(CREDENTIAL_ID_ACCOUNT) -> AccountLookup
            credentialIds.contains(CREDENTIAL_ID_PAYMENT) -> TransactionProcessor
            else -> throw InvalidRequestException(
                "DCQL query carries no credential id this server issued: $credentialIds"
            )
        }
    }

    const val INTENT_PAYMENT = "payment"
    const val INTENT_ACCOUNT_LOOKUP = "account_lookup"

    /** Must match the credential id [TransactionProcessor] puts in its query. */
    const val CREDENTIAL_ID_PAYMENT = "payment"
    const val CREDENTIAL_ID_ACCOUNT = "account"
}

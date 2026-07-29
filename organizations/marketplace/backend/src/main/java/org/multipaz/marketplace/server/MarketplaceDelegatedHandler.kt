package org.multipaz.marketplace.server

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.client.RpcAuthorizedServerClient
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.server.common.enrollmentServerUrl
import org.multipaz.server.common.getBaseUrl
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import org.multipaz.server.payment.PaymentProcessor
import org.multipaz.server.payment.PaymentProcessorStub
import org.multipaz.server.payment.PaymentTransactionRequest
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.verification.MdocVerifiedPresentation
import org.multipaz.verification.PresentmentRecord
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.customization.VerifierPresentment
import java.lang.IllegalStateException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MarketplaceDelegated"

// ---------------------------------------------------------------------------
// The REAL delegated-verifier backend (S6, issue #16) — the marketplace's host-side
// implementation of CredentAgent's DelegatedVerifier seam, wired straight to the real
// Multipaz verifier + UPay processor (NO stand-in).
//
//   POST /delegated/request  → UPay createTransaction + the DPC request (dcql + transaction_data)
//   GET  /delegated/result   → the stored, issuer-trust-verified verdict (verify only, NO commit)
//   POST /delegated/settle    → UPay commitTransaction consuming the stored presentment record
//
// The `reference` threaded through all three is the UPay transactionId minted at /request: it is
// signed into the transaction_data the wallet binds to, surfaces inside the assistant callback, and
// is the handle UPay commits. The load-bearing rule is the TWO-PHASE split: [DelegatedVerifierAssistant]
// verifies issuer trust and STORES the presentment record; it never commits. Settlement happens only
// when the gate calls /delegated/settle after its own re-checks pass.
// ---------------------------------------------------------------------------

/** A verified-but-not-settled DPC presentment, awaiting the gate's settle authorization. */
private class PendingPresentment(
    /** The verdict `consume` returns: {approved, trust_level, claims, binding}. */
    val verdict: JsonObject,
    /** The self-contained presentment record UPay's commitTransaction consumes at settle. */
    val presentmentRecord: PresentmentRecord,
)

// Keyed by the UPay transactionId (the reference). In-memory, single-process — matching the demo's
// existing page-driven stores. A real deployment would TTL/persist these.
private val pendingByReference = ConcurrentHashMap<String, PendingPresentment>()
// The gate's binding (amount/currency/payee=rpID), recorded at /request so the verdict can echo the
// payee the gate expects (its origin rpID) while amount/currency come from what the wallet signed.
private val bindingByReference = ConcurrentHashMap<String, JsonObject>()
private val settlementByReference = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

private val PAYMENT_DOCTYPE = DigitalPaymentCredential.CARD_DOCTYPE

// Claims requested from the payment card: what UPay's commit path needs, plus the fields the
// receipt shows.
private val PAYMENT_CLAIMS = listOf(
    "issuer_name",
    "masked_account_reference",
    "payment_instrument_id",
    "holder_name",
    "expiry_date",
)

// The DPC (Digital Payment Credential) request for the payment step — the real
// `org.multipaz.payment.sca.1` mdoc the wallet holds, requesting the fields UPay's commit path
// needs plus the receipt fields. Payment-only: age was proven separately on the credential rail.
private val PAYMENT_ONLY_DCQL_QUERY: JsonObject = buildJsonObject {
    put("credentials", buildJsonArray {
        add(buildJsonObject {
            put("id", "payment")
            put("format", "mso_mdoc")
            put("meta", buildJsonObject { put("doctype_value", PAYMENT_DOCTYPE) })
            put("claims", buildJsonArray {
                for (claim in PAYMENT_CLAIMS) {
                    add(buildJsonObject {
                        put("path", buildJsonArray {
                            add(DigitalPaymentCredential.CARD_NAMESPACE)
                            add(claim)
                        })
                    })
                }
            })
        })
    })
    put("credential_sets", buildJsonArray {
        add(buildJsonObject {
            put("purpose", "Payment")
            put("options", buildJsonArray { add(buildJsonArray { add("payment") }) })
        })
    })
}

// ---------------------------------------------------------------------------
// POST /delegated/request — mint the DPC request for THIS order's payment step.
// ---------------------------------------------------------------------------

/**
 * Receives the gate's catalog-priced `binding` ({amount, currency, payee:{id=rpID}}). BINDS to it
 * (never supplies the amount): calls UPay [PaymentProcessor.createTransaction] for the real payee
 * account, then returns `{reference: transactionId, handoff: {dcql, transaction_data, nonce}}` for
 * the browser to drive `multipazVerifyCredentials`. Payment-only — age is proven separately on the
 * credential rail, so the DPC step asks for the payment card only.
 *
 * @throws InvalidRequestException if the body carries no `binding`, or its `amount` is missing or
 *  not a number — the gate always sends both, so this is a malformed caller, not a user error.
 * @throws IllegalStateException if `payee_account` is not configured on this server, i.e. the
 *  deployment cannot say who gets paid.
 */
suspend fun marketplaceDelegatedRequest(call: ApplicationCall) {
    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
    val binding = body["binding"]?.jsonObject
        ?: throw InvalidRequestException("'binding' is missing or invalid")
    val amount = binding["amount"]?.jsonPrimitive?.doubleOrNull
        ?: throw InvalidRequestException("'binding.amount' is missing or invalid")
    val currency = binding["currency"]?.jsonPrimitive?.contentOrNull ?: "USD"
    val gatePayeeId = binding["payee"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: ""

    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
    val payeeAccount = configuration.getValue("payee_account")
        ?: throw IllegalStateException("'payee_account' is not configured")
    val serviceUrl = configuration.enrollmentServerUrl!!
    val paymentProcessor = getPaymentProcessor(serviceUrl)
    val paymentTransactionData = withContext(RpcAuthClientSession()) {
        paymentProcessor.createTransaction(PaymentTransactionRequest(
            payeeAccount = payeeAccount,
            description = "Utopia Marketplace",
            amount = amount,
            currency = currency,
        ))
    }
    val reference = paymentTransactionData.transactionId

    val transactionData = buildJsonArray {
        add(buildJsonObject {
            put("type", PaymentTransaction.identifier)
            put("credential_ids", buildJsonArray { add(JsonPrimitive("payment")) })
            put("payload", buildJsonObject {
                put("transaction_id", paymentTransactionData.transactionId)
                put("payee", buildJsonObject {
                    put("name", paymentTransactionData.payeeName)
                    put("id", payeeAccount)
                })
                put("amount", amount)
                put("currency", currency)
            })
        })
    }

    // Record the gate's binding so /delegated/result can echo the payee the gate re-checks (rpID),
    // while reporting the amount/currency the wallet actually signed over.
    bindingByReference[reference] = buildJsonObject {
        put("amount", amount)
        put("currency", currency)
        put("payee", buildJsonObject { put("id", gatePayeeId) })
        put("transactionId", reference)
    }
    Logger.i(TAG, "Delegated /request: minted txn $reference for $amount $currency")

    val handoff = buildJsonObject {
        put("dcql", PAYMENT_ONLY_DCQL_QUERY)
        put("transaction_data", transactionData)
        put("nonce", paymentTransactionData.nonce.toByteArray().toBase64Url())
    }
    val response = buildJsonObject {
        put("reference", reference)
        put("handoff", handoff)
    }
    call.respondText(response.toString(), ContentType.Application.Json)
}

// ---------------------------------------------------------------------------
// GET /delegated/result?ref=<txnId> — the verify-only verdict (NO settlement).
// ---------------------------------------------------------------------------

/**
 * Returns the stored, issuer-trust-verified verdict for a reference. Pending (not approved) until
 * the wallet ceremony has posted and [DelegatedVerifierAssistant] stored it.
 *
 * @throws InvalidRequestException if the `ref` query parameter is absent. An UNKNOWN reference is
 *  not an error here: it answers a not-approved verdict, so a poll before the wallet has finished
 *  reads as "not ready" rather than failing.
 */
suspend fun marketplaceDelegatedResult(call: ApplicationCall) {
    val ref = call.request.queryParameters["ref"]
        ?: throw InvalidRequestException("'ref' query parameter is required")
    val pending = pendingByReference[ref]
    if (pending == null) {
        // Not ready — a refusal, never a silent approval.
        val notReady = buildJsonObject {
            put("approved", false)
            put("trust_level", "presence-only-demo")
            put("claims", buildJsonObject {})
            put("binding", buildJsonObject {
                put("amount", 0.0); put("currency", ""); put("payee", buildJsonObject { put("id", "") })
            })
            put("reason", "presentment not ready")
        }
        call.respondText(notReady.toString(), ContentType.Application.Json)
        return
    }
    call.respondText(pending.verdict.toString(), ContentType.Application.Json)
}

// ---------------------------------------------------------------------------
// POST /delegated/settle?ref=<txnId> — commit the transaction (UPay commitTransaction).
// ---------------------------------------------------------------------------

/**
 * Commits the stored presentment record via UPay. Called by the gate ONLY after its binding +
 * policy re-checks pass.
 *
 * Idempotent by `reference`: the money moves at most once, and every call for a reference that has
 * settled returns that same receipt. This matters because throwing on a retry would leave the order
 * authorized-but-not-settled — the funds gone and the purchase never completed. A first caller
 * claims the reference by inserting its receipt slot before committing; anyone else awaits that slot.
 *
 * @throws InvalidRequestException if the `ref` query parameter is absent, or names a reference that
 *  was never stored (no verified presentment is awaiting settlement under it).
 */
suspend fun marketplaceDelegatedSettle(call: ApplicationCall) {
    val ref = call.request.queryParameters["ref"]
        ?: throw InvalidRequestException("'ref' query parameter is required")

    // Claim the reference. `putIfAbsent` is the atomic gate: exactly one caller gets null back and
    // goes on to commit — everyone else awaits the receipt that caller produces.
    val slot = CompletableDeferred<JsonObject>()
    settlementByReference.putIfAbsent(ref, slot)?.let { inFlightOrSettled ->
        call.respondText(inFlightOrSettled.await().toString(), ContentType.Application.Json)
        return
    }

    val pending = pendingByReference.remove(ref)
    if (pending == null) {
        // Nothing was awaiting settlement, so this reference is not ours to settle. Drop the slot we
        // just claimed, or later callers would await a receipt that is never coming. Completing it
        // exceptionally (rather than cancelling) hands anyone already awaiting the same refusal,
        // instead of a cancellation that would propagate into their coroutine.
        settlementByReference.remove(ref)
        val unknown = InvalidRequestException("unknown reference")
        slot.completeExceptionally(unknown)
        throw unknown
    }

    val receipt = try {
        val configuration = BackendEnvironment.getInterface(Configuration::class)!!
        val serviceUrl = configuration.enrollmentServerUrl!!
        val paymentProcessor = getPaymentProcessor(serviceUrl)
        val confirmation = withContext(RpcAuthClientSession()) {
            paymentProcessor.commitTransaction(pending.presentmentRecord)
        }
        Logger.i(TAG, "Delegated /settle: committed txn $ref → $confirmation")
        buildJsonObject {
            put("network", "upay")
            put("txId", confirmation)
            put("status", "settled")
        }
    } catch (e: Throwable) {
        // The commit did not produce a receipt, so nothing is settled under this reference. Put the
        // presentment back and release the claim so a retry can genuinely try again.
        pendingByReference[ref] = pending
        settlementByReference.remove(ref)
        slot.completeExceptionally(e)
        throw e
    }

    slot.complete(receipt)
    bindingByReference.remove(ref)
    call.respondText(receipt.toString(), ContentType.Application.Json)
}

// ---------------------------------------------------------------------------
// DelegatedVerifierAssistant — the verify-and-store half of the two-phase split.
// ---------------------------------------------------------------------------

/**
 * Runs inside the Multipaz verifier's process_response callback. ONE assistant serves both
 * checkout paths, because the verifier framework registers a single one per server:
 *
 *  - The **delegated rail** (the MCP storefront's payment step): verifies issuer trust and STORES
 *    the presentment record keyed by the transactionId WITHOUT committing. Settlement is deferred
 *    to /delegated/settle, which the gate authorizes only after its own re-checks pass — so a
 *    purchase its policy would refuse never moves money first.
 *  - The **human storefront** (the product page's own checkout): unchanged, atomic behaviour —
 *    [MarketplaceVerifierAssistant] verifies and commits in one step, and returns the
 *    holder/issuer fields that page renders.
 *
 * The two are told apart by the transaction id: only [marketplaceDelegatedRequest] records one in
 * `bindingByReference`, so anything else is the human storefront's own transaction.
 */
class DelegatedVerifierAssistant : VerifierAssistant {
    private val storefrontAssistant = MarketplaceVerifierAssistant()

    override suspend fun processRequest(request: JsonObject): JsonObject? = null

    override suspend fun processResponse(presentment: VerifierPresentment): JsonObject {
        // The reference is the transaction_id the wallet signed over (minted at /delegated/request).
        val payload = presentment.transactions.firstOrNull()?.get("payload")?.jsonObject
        val reference = payload?.get("transaction_id")?.jsonPrimitive?.contentOrNull

        // Not a delegated transaction ⇒ the human storefront: keep its original atomic flow.
        if (reference == null || !bindingByReference.containsKey(reference)) {
            return storefrontAssistant.processResponse(presentment)
        }

        // Issuer trust — the REAL check TransactionProcessor does: the DPC's signer chain must
        // verify against the marketplace's TrustManager (the records-server IACA root).
        val trustManager = BackendEnvironment.getInterface(TrustManagerInterface::class)!!
        val payment = presentment.presentations.find {
            it is MdocVerifiedPresentation && it.docType == PAYMENT_DOCTYPE
        } as? MdocVerifiedPresentation
        val trusted = payment?.documentSignerCertChain?.let {
            trustManager.verify(it.certificates).isTrusted
        } == true
        val trustLevel = if (trusted) "issuer-verified" else "presence-only-demo"

        // Disclosed DPC claims (receipt only — keyed by the DCQL id "payment").
        val paymentClaims = presentment.response["payment"]?.jsonObject?.get("claims")?.jsonObject
            ?: buildJsonObject {}

        // Binding: amount/currency the wallet signed over; payee echoes the gate's rpID.
        val signedAmount = payload["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val signedCurrency = payload["currency"]?.jsonPrimitive?.contentOrNull ?: "USD"
        val gatePayeeId = bindingByReference[reference]?.get("payee")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull ?: ""

        val verdict = buildJsonObject {
            put("approved", trusted)
            put("trust_level", trustLevel)
            put("claims", buildJsonObject { put("payment", paymentClaims) })
            put("binding", buildJsonObject {
                put("amount", signedAmount)
                put("currency", signedCurrency)
                put("payee", buildJsonObject { put("id", gatePayeeId) })
                put("transactionId", reference)
            })
            if (!trusted) put("reason", "payment card is not from a trusted issuer")
        }

        // Store — DO NOT commit. Settlement is the gate's call.
        pendingByReference[reference] = PendingPresentment(verdict, presentment.presentmentRecord)
        Logger.i(TAG, "Delegated presentment stored for txn $reference (trusted=$trusted); awaiting settle")

        // The browser ignores this result — it only carries the sealed reference back to /verify.
        return buildJsonObject { put("approved", trusted) }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private suspend fun getPaymentProcessor(serviceUrl: String): PaymentProcessor {
    val exceptionMap = RpcExceptionMap.Builder().build()
    val dispatcher = RpcAuthorizedServerClient.connect(
        exceptionMap = exceptionMap,
        rpcEndpointUrl = "$serviceUrl/rpc",
        callingServerUrl = BackendEnvironment.getBaseUrl(),
        signingKey = getServerIdentity(ServerIdentity.PAYMENT_PROCESSOR)
    )
    return PaymentProcessorStub("payment", dispatcher, RpcNotifier.SILENT)
}

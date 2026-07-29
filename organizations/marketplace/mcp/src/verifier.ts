// The marketplace's host-side DelegatedVerifier adapter (S6, issue #16).
//
// This is the concrete adapter the gate's delegated rail calls — the gate stays
// implementation-agnostic (no Multipaz/UPay symbol in credentagent-gate), and all the
// Multipaz-verifier + UPay/DPC specifics live HERE, host-side. The adapter is a THIN HTTP
// client over three routes on the marketplace Kotlin backend:
//
//   buildRequest → POST {kotlinBase}/delegated/request  (createTransaction + wire DCQL + txData)
//   consume      → GET  {kotlinBase}/delegated/result?ref=<txnId>  (verify + issuer trust; NO settle)
//   settle       → POST {kotlinBase}/delegated/settle?ref=<txnId>  (commitTransaction)
//
// The `reference` threaded through all three is the UPay transactionId minted in buildRequest —
// it is signed into the transaction_data the wallet binds to, surfaces inside the Kotlin
// VerifierAssistant callback, and identifies the pending UPay transaction to commit.
import type { DelegatedVerifier, DelegatedVerdict, DelegatedHandoff } from "@openmobilehub/credentagent-gate";

export interface MultipazUpayVerifierOptions {
  /** Server-to-server base the adapter fetches (the Kotlin marketplace backend's delegated
   *  routes). NOT origin-bound — any reachable URL. See MARKETPLACE_KOTLIN_BASE in .env.example. */
  kotlinBase: string;
  /** Browser-facing base where `verify_credentials.js` is served (the Multipaz verifier origin).
   *  Carried in the handoff so the delegated page loads the script + drives the ceremony from
   *  the right origin. Same host as the storefront behind nginx. e.g. "/marketplace". */
  verifierBase: string;
}

const trimSlash = (s: string): string => s.replace(/\/+$/, "");

/**
 * The real adapter: delegates verification + settlement to the Multipaz verifier + UPay processor
 * running in the marketplace Kotlin backend. Binds to the gate's catalog-priced `binding`; never
 * supplies the amount (the gate re-checks the returned binding before it authorizes settlement).
 */
export function multipazUpayVerifier(options: MultipazUpayVerifierOptions): DelegatedVerifier {
  const kotlinBase = trimSlash(options.kotlinBase);
  const verifierBase = trimSlash(options.verifierBase);

  return {
    async buildRequest({ order, dcql, binding, origin }): Promise<DelegatedHandoff> {
      const res = await fetch(`${kotlinBase}/delegated/request`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ orderId: order.id, dcql, binding, origin: origin.rpID }),
      });
      if (!res.ok) throw new Error(`[multipaz-verifier] /delegated/request → ${res.status}`);
      const body = (await res.json()) as { reference: string; handoff: Record<string, unknown> };
      // THIS adapter names its verifier (the gate names none): the approve page loads
      // `clientScript` and calls `window[clientEntry](handoff)` — which is what opens the wallet.
      // `multipazVerifyCredentials` derives its own base URL from this script's src, so the script
      // path also determines where make_request / process_response are posted.
      return {
        reference: body.reference,
        handoff: body.handoff, // { dcql, transaction_data, nonce } — opaque to the gate
        clientScript: `${verifierBase}/verify_credentials.js`,
        clientEntry: "multipazVerifyCredentials",
      };
    },

    async consume({ reference }): Promise<DelegatedVerdict> {
      const res = await fetch(`${kotlinBase}/delegated/result?ref=${encodeURIComponent(reference)}`);
      if (!res.ok) {
        // A not-ready / missing result is a refusal, never a silent approval (the gate treats
        // `approved: false` as a failed gate and completes nothing).
        return {
          approved: false,
          trust_level: "presence-only-demo",
          claims: {},
          binding: { amount: 0, currency: "", payee: { id: "" } },
          reason: `verifier result unavailable (${res.status})`,
        };
      }
      return (await res.json()) as DelegatedVerdict;
    },

    async settle({ reference, amount, currency }) {
      const res = await fetch(`${kotlinBase}/delegated/settle?ref=${encodeURIComponent(reference)}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ amount, currency }),
      });
      if (!res.ok) throw new Error(`[multipaz-verifier] /delegated/settle → ${res.status}`);
      return (await res.json()) as Record<string, unknown> & { network: string; txId: string; status: string };
    },
  };
}

// ── Local stand-in (dev/offline) ─────────────────────────────────────────────
// A dev double that SIMULATES the external verifier + processor so the delegated rail is
// clickable offline — no Kotlin stack, no phone. It reports `trust_level: "presence-only-demo"`
// honestly (the real adapter reports "issuer-verified" when the DPC is trusted). The point of
// running with this is the FLOW + the gate's own re-checks: set VERDICT to watch the gate refuse.
export type StandInMode = "ok" | "wrong-amount" | "underage" | "declined";

export function standInVerifier(mode: StandInMode = "ok"): DelegatedVerifier {
  let captured: DelegatedVerdict["binding"] = { amount: 0, currency: "", payee: { id: "" } };
  return {
    async buildRequest({ binding }): Promise<DelegatedHandoff> {
      captured = { amount: binding.amount, currency: binding.currency, payee: { id: binding.payee.id } };
      return {
        reference: `dev-${Date.now()}`,
        handoff: { note: "LOCAL STAND-IN — no external verifier is contacted" },
      };
    },
    async consume(): Promise<DelegatedVerdict> {
      const verdict: DelegatedVerdict = {
        approved: true,
        trust_level: "presence-only-demo",
        claims: {
          age_mdl: { age_over_18: true, age_over_21: true },
          payment: { issuer_name: "Dev Bank of Utopia", holder_name: "Local Tester", masked_account_reference: "•••• 4242" },
        },
        binding: { ...captured },
      };
      if (mode === "wrong-amount") verdict.binding.amount = captured.amount - 1;
      // Genuinely under the order's threshold: the verifier still `approve`s (its own age rule may
      // be laxer), but the disclosed claims do NOT prove the required age — so the gate's own
      // re-check refuses AFTER the verifier approved, BEFORE settlement (the two-phase story).
      if (mode === "underage") verdict.claims.age_mdl = { age_over_18: false, age_over_21: false };
      if (mode === "declined") { verdict.approved = false; verdict.reason = "card not from a trusted issuer (stand-in)"; }
      return verdict;
    },
    async settle({ amount, currency }) {
      return { network: "dev-processor", txId: `dev_tx_${Date.now()}`, status: "settled", amount, currency };
    },
  };
}

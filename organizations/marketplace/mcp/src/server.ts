import { createStorefront } from "@openmobilehub/credentagent-storefront/server";
import type { CartStore, CompletedOrderRecord, OrderStore } from "@openmobilehub/credentagent-storefront/server";
import { LOYALTY_DISCOUNT_PCT, type Order } from "@openmobilehub/credentagent-storefront";
import { CredentAgent, required, optional, age, membership, payment } from "@openmobilehub/credentagent-gate";
import { catalog, reviews } from "./catalog.js";
import { multipazUpayVerifier, standInVerifier, type StandInMode } from "./verifier.js";

// One cart for every MCP session: Claude's remote connector opens a fresh session per tool call,
// so a per-session cart would come back empty on the next call. Fine for a single-user demo.
class GlobalCartStore implements CartStore {
  private cart = new Map<string, number>();
  async read(_sessionId: string): Promise<Map<string, number>> {
    return new Map(this.cart);
  }
  async write(_sessionId: string, cart: Map<string, number>): Promise<void> {
    this.cart = new Map(cart);
  }
}

export interface BuildStoreOptions {
  baseUrl?: string;
  /** Created-but-unpaid orders. Default: in-memory, which loses them on restart. */
  createdOrderStore?: OrderStore<Order>;
  /** Completed purchases, behind `get-order-status`. Default: in-memory. */
  completedOrderStore?: OrderStore<CompletedOrderRecord>;
}

/**
 * The MCP storefront with CredentAgent's delegated ceremony mounted (S6, issue #16): the real
 * Multipaz + UPay payment runs inside the mounted rail through a host-side DelegatedVerifier.
 * The gate policy is the same one the presence-only path uses — only the backend moved.
 */
export function buildStore(opts: BuildStoreOptions = {}) {
  const baseUrl = opts.baseUrl;
  // Note: the delegated age re-check takes its threshold from the order's catalog `minimumAge`,
  // so raising this alone does not raise the age actually enforced.
  const minAge = Number(process.env.MARKETPLACE_AGE ?? 18);
  const hasAlcohol = (o: { lines?: Array<{ minimumAge?: number }> }): boolean =>
    (o.lines ?? []).some((l) => (l.minimumAge ?? 0) >= 18);
  const ageCred = age.over(minAge).when(hasAlcohol);
  const payCred = payment.in("usd");
  // Optional by contract: a discount is opted into, never demanded, so it must not block checkout.
  // The percent must match the storefront's own re-price (LOYALTY_DISCOUNT_PCT), or the manifest
  // would advertise a discount the priced total doesn't give.
  const loyaltyCred = membership.discount(LOYALTY_DISCOUNT_PCT);

  // Real Multipaz + UPay when a backend is configured; otherwise a stand-in so the rail stays
  // clickable offline. VERDICT (wrong-amount | underage | declined) drives its refusal modes.
  const kotlinBase = process.env.MARKETPLACE_KOTLIN_BASE;
  const verifier = kotlinBase
    ? multipazUpayVerifier({ kotlinBase, verifierBase: process.env.MARKETPLACE_VERIFIER_BASE ?? kotlinBase })
    : standInVerifier((process.env.VERDICT as StandInMode) ?? "ok");

  const store = createStorefront({
    catalog,
    reviews,
    ...(baseUrl ?? process.env.MCP_BASE_URL ? { baseUrl: baseUrl ?? process.env.MCP_BASE_URL } : {}),
    ...(opts.createdOrderStore ? { createdOrderStore: opts.createdOrderStore } : {}),
    ...(opts.completedOrderStore ? { orderStore: opts.completedOrderStore } : {}),
    allowEphemeralKey: true,
    // Stateless: Claude's connector sends no session id, and the stateful transport would reject
    // those requests with "No valid session" — which surfaces as "could not load the MCP app".
    statelessMcp: true,
    cartStore: new GlobalCartStore(),
    verifier,
  });

  const credentagent = new CredentAgent({ credentials: [ageCred, loyaltyCred, payCred] });
  credentagent.mount(store.app);
  store.gate((order) =>
    credentagent.requirements(order, [required(ageCred), optional(loyaltyCred), required(payCred)]),
  );

  return { store, usingStandIn: !kotlinBase };
}

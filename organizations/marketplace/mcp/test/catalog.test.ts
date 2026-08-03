import { test } from "node:test";
import assert from "node:assert/strict";
import { priceCart, createOrder } from "@openmobilehub/credentagent-storefront";
import { catalog } from "../src/catalog.js";

test("grocery-only cart is not age-restricted", () => {
  const cart = priceCart([{ productId: "p1", quantity: 2 }], catalog); // Red Delicious Apples 4.50
  assert.equal(cart.hasAgeRestricted, false);
  assert.equal(cart.total, 9); // 4.50 * 2
});

test("an alcohol item makes the cart age-restricted (18+)", () => {
  const cart = priceCart([{ productId: "p16", quantity: 1 }], catalog); // Old Oak Bourbon
  assert.equal(cart.hasAgeRestricted, true);
  assert.equal(cart.lines[0].minimumAge, 18);
});

test("createOrder snapshots the priced total", () => {
  const order = createOrder(
    [
      { productId: "p1", quantity: 1 }, // 4.50
      { productId: "p2", quantity: 2 }, // 2.20 * 2
    ],
    "ORD-1",
    catalog,
  );
  assert.equal(order.total, 8.9);
  assert.equal(order.currency, "USD");
});

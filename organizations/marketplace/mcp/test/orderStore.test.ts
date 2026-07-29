import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createOrder } from "@openmobilehub/credentagent-storefront";
import { FileOrderStore } from "../src/orderStore.js";
import { catalog } from "../src/catalog.js";

function tempFile(): string {
  return join(mkdtempSync(join(tmpdir(), "mcp-orders-")), "orders.json");
}

test("read returns null for an unknown id", async () => {
  const file = tempFile();
  try {
    assert.equal(await new FileOrderStore(file).read("missing"), null);
  } finally {
    rmSync(file, { force: true });
  }
});

test("write then read round-trips the order; clear removes it", async () => {
  const file = tempFile();
  try {
    const store = new FileOrderStore(file);
    const order = createOrder([{ productId: "p15", quantity: 1 }], "ORD-9", catalog); // Reserve Red Wine 18.00
    await store.write("ORD-9", order);
    assert.equal((await store.read("ORD-9"))?.total, 18);
    await store.clear("ORD-9");
    assert.equal(await store.read("ORD-9"), null);
  } finally {
    rmSync(file, { force: true });
  }
});

// The reason this store is file-backed at all: a checkout link is minted, and the buyer opens it
// minutes later on a phone — possibly after a restart in between. An order lost there makes a
// perfectly valid link answer "Unknown order".
test("orders survive a restart (a second store over the same file sees them)", async () => {
  const file = tempFile();
  try {
    const order = createOrder([{ productId: "p16", quantity: 1 }], "ORD-restart", catalog); // 42.00
    await new FileOrderStore(file).write("ORD-restart", order);

    const reopened = new FileOrderStore(file);
    assert.equal((await reopened.read("ORD-restart"))?.total, 42);
  } finally {
    rmSync(file, { force: true });
  }
});

test("clear is persisted too, so a cleared order stays gone after a restart", async () => {
  const file = tempFile();
  try {
    const store = new FileOrderStore(file);
    await store.write("ORD-x", createOrder([{ productId: "p1", quantity: 1 }], "ORD-x", catalog));
    await store.clear("ORD-x");

    assert.equal(await new FileOrderStore(file).read("ORD-x"), null);
  } finally {
    rmSync(file, { force: true });
  }
});

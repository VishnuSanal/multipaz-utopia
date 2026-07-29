import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Order } from "@openmobilehub/credentagent-storefront";
import type { OrderStore } from "@openmobilehub/credentagent-storefront/server";

// A file-backed OrderStore. The storefront keeps two of them, and both need to survive a restart:
//
//   created orders   — the `checkout` tool mints a link the buyer opens minutes later on a phone,
//                      and the server may restart in between (a redeploy, a crash). Held in memory
//                      those orders vanish, and the checkout page answers "Unknown order" for a
//                      link that is perfectly valid.
//   completed orders — what `get-order-status` reports and what shows the checkout page's paid
//                      state. Held in memory a restart makes a real, settled purchase read back as
//                      "pending", even though the money moved and the System of Record has it.
//
// Single-process JSON file — enough for this demo. A multi-instance deployment would point the
// storefront at a shared store (Redis) instead.
/** Where created-but-unpaid orders live. */
export function createdOrdersFile(): string {
  return process.env.MCP_ORDERS_FILE ?? join(process.cwd(), ".orders.json");
}

/** Where completed purchases live. A SEPARATE file from {@link createdOrdersFile}: one store per
 *  file, or the two would overwrite each other's contents on every write. */
export function completedOrdersFile(): string {
  return process.env.MCP_COMPLETED_ORDERS_FILE ?? join(process.cwd(), ".completed-orders.json");
}

export class FileOrderStore<T = Order> implements OrderStore<T> {
  private orders = new Map<string, T>();
  private readonly file: string;

  constructor(file: string) {
    this.file = file;
    try {
      const raw = readFileSync(this.file, "utf8");
      for (const [id, order] of Object.entries(JSON.parse(raw) as Record<string, T>)) {
        this.orders.set(id, order);
      }
      console.log(`  restored ${this.orders.size} order(s) from ${this.file}`);
    } catch {
      /* no file yet — first run */
    }
  }

  private flush(): void {
    try {
      writeFileSync(this.file, JSON.stringify(Object.fromEntries(this.orders)), "utf8");
    } catch (err) {
      console.warn("could not persist orders:", (err as Error).message);
    }
  }

  async read(id: string): Promise<T | null> {
    return this.orders.get(id) ?? null;
  }

  async write(id: string, order: T): Promise<void> {
    this.orders.set(id, order);
    this.flush();
  }

  async clear(id: string): Promise<void> {
    this.orders.delete(id);
    this.flush();
  }
}

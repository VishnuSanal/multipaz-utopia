import type { Product, Review } from "@openmobilehub/credentagent-storefront";

// Utopia Marketplace catalog for the MCP storefront.
//
// Product ids are `p<N>` where N is the id in the server-authoritative catalog
// (organizations/marketplace/backend/.../MarketplaceCatalog.kt) — the checkout page derives that
// integer back from the digits. They are deliberately NON-numeric strings: a bare numeric id like
// "1" gets passed back by the model as the number 1, which fails the tool's `productId: string`
// schema (so items never get added). Price / age-restriction here must match MarketplaceCatalog.kt
// (and catalog.js); at checkout the backend re-prices every line by id, so these amounts are for
// display only. Images use picsum.photos (the storefront widget CSP only allows picsum + data:).
export const catalog: Product[] = [
  // Fresh Produce
  { id: "p1", name: "Red Delicious Apples", price: 4.5, currency: "USD", image: "https://picsum.photos/seed/apples/400/300", category: "Fresh Produce", description: "Crisp, honey-sweet orchard apples. 1 kg bag." },
  { id: "p2", name: "Organic Bananas", price: 2.2, currency: "USD", image: "https://picsum.photos/seed/bananas/400/300", category: "Fresh Produce", description: "Naturally ripened Fairtrade bananas. Bunch of 5." },
  { id: "p3", name: "Vine Tomatoes", price: 3.8, currency: "USD", image: "https://picsum.photos/seed/tomatoes/400/300", category: "Fresh Produce", description: "Sun-ripened tomatoes on the vine. 500 g." },
  // Bakery
  { id: "p4", name: "Artisan Sourdough", price: 5.0, currency: "USD", image: "https://picsum.photos/seed/sourdough/400/300", category: "Bakery", description: "Slow-fermented sourdough with a crackling crust." },
  { id: "p5", name: "Butter Croissants", price: 4.2, currency: "USD", image: "https://picsum.photos/seed/croissants/400/300", category: "Bakery", description: "Flaky all-butter croissants. Pack of 4." },
  // Dairy & Eggs
  { id: "p6", name: "Whole Milk", price: 1.8, currency: "USD", image: "https://picsum.photos/seed/milk/400/300", category: "Dairy & Eggs", description: "Fresh farm-sourced whole milk. 1 L." },
  { id: "p7", name: "Free-Range Eggs", price: 3.6, currency: "USD", image: "https://picsum.photos/seed/eggs/400/300", category: "Dairy & Eggs", description: "Free-range large eggs. Box of 12." },
  { id: "p8", name: "Aged Cheddar", price: 6.4, currency: "USD", image: "https://picsum.photos/seed/cheddar/400/300", category: "Dairy & Eggs", description: "Sharp, crumbly cheddar aged 12 months. 250 g." },
  // Pantry
  { id: "p9", name: "Spaghetti No. 5", price: 1.6, currency: "USD", image: "https://picsum.photos/seed/spaghetti/400/300", category: "Pantry", description: "Bronze-cut durum wheat spaghetti. 500 g." },
  { id: "p10", name: "Extra Virgin Olive Oil", price: 9.5, currency: "USD", image: "https://picsum.photos/seed/oliveoil/400/300", category: "Pantry", description: "Cold-pressed extra virgin olive oil. 750 ml." },
  { id: "p11", name: "Honey Oat Cereal", price: 4.0, currency: "USD", image: "https://picsum.photos/seed/cereal/400/300", category: "Pantry", description: "Wholegrain oat clusters with a touch of honey. 500 g." },
  // Beverages
  { id: "p12", name: "Fresh Orange Juice", price: 3.9, currency: "USD", image: "https://picsum.photos/seed/juice/400/300", category: "Beverages", description: "Not-from-concentrate squeezed orange juice. 1 L." },
  { id: "p13", name: "Ground Coffee", price: 8.2, currency: "USD", image: "https://picsum.photos/seed/coffee/400/300", category: "Beverages", description: "Medium-roast arabica, ground for filter. 340 g." },
  // Beer, Wine & Spirits (age-restricted, 18+)
  { id: "p14", name: "Craft Lager", price: 11.0, currency: "USD", image: "https://picsum.photos/seed/lager/400/300", category: "Beer, Wine & Spirits", description: "Crisp small-batch lager. 6 × 330 ml. 18+ only.", minimumAge: 18 },
  { id: "p15", name: "Reserve Red Wine", price: 18.0, currency: "USD", image: "https://picsum.photos/seed/redwine/400/300", category: "Beer, Wine & Spirits", description: "Full-bodied reserve red, oak-aged. 750 ml. 18+ only.", minimumAge: 18 },
  { id: "p16", name: "Old Oak Bourbon", price: 42.0, currency: "USD", image: "https://picsum.photos/seed/bourbon/400/300", category: "Beer, Wine & Spirits", description: "Small-batch bourbon aged in charred oak. 700 ml. 18+ only.", minimumAge: 18 },
];

export const reviews: Record<string, Review[]> = {
  "p1": [
    { author: "Mia R.", rating: 5, text: "Genuinely crisp and sweet — kids devour them." },
    { author: "Devin K.", rating: 4, text: "Fresh and consistent week to week." },
  ],
  "p13": [
    { author: "Carlos M.", rating: 5, text: "Smooth medium roast, great for pour-over." },
  ],
  "p16": [
    { author: "Quinn R.", rating: 5, text: "Smooth with a warm oak finish. Worth it." },
  ],
};

# Limit Order Book & Matching Engine Simulator

![CI](https://github.com/mikolajmarchelek/OrderBook/actions/workflows/ci.yml/badge.svg)

![Demo](docs/demo.gif)

##Features
- **Matching engine:** limit and market orders, price-time (FIFO) priority, partial fills, price imporvement
- **Order book:** best bid/ask, spread, mid, depth snapshots (L2), O91) cancel lookup
- **Execution Analysis:** fills reports with VWAP and slippage vs. arrival mid
- **Market simulation:** random-walk fair value with stochastic order flow (passive liquidity, aggressive orders, cancels)
- **Manual trading:** submit your own orders and watch your queue position (orders and shares ahead of you) update live, plus your fills and slippage

##Requirements
Requires **JDK 17+**. Gradle and JavaFX are downloaded automatically.

### Data structures

| Structure | Implementation | Why |
|---|---|---|
| Price levels (per side) | `TreeMap<Long, PriceLevel>` (red-black tree), bids in reverse order | Best price is always the first key; O(log L) insert/remove for L levels |
| Orders at one price | `ArrayDeque<Order>` | FIFO = time priority; O(1) at both ends |
| Order lookup by id | `HashMap<Long, Order>` | O(1) to find an order for cancels / queue position |

Prices are stored as **integer ticks** (`long`), never floating point, so price
levels compare exactly. Decimals exist only at the UI boundary.

### Matching rules

1. An incoming order matches while it **crosses** the best opposite price
   (buy ≥ best ask, sell ≤ best bid; market orders always cross).
2. At each level, the **oldest** order fills first.
3. Trades execute at the **resting** order's price (price improvement for the aggressor).
4. Leftover quantity: a **limit** order rests in the book, a **market** order is discarded.
5. Empty price levels are removed immediately, so the book never shows phantom prices.

## Simulation example

Example execution: a buy of 65 shares against the book below, with an arrival mid of 100.010.

| Fills | Average price | Slippage |
|---|---|---|
| 60 @ 100.02, 5 @ 100.03 | 100.0208 | **1.077 ticks** = 1.0 (half-spread) + 0.077 (market impact) |

- **Liquidity vs. aggressiveness:** at 5% aggressive flow, the spread stays at
  1 tick with deep levels; at 50%, the spread widens to 2–12 ticks and whole
  sides of the book empty out.
- **Adverse selection:** when the fair value moves, stale quotes on the wrong
  side get picked off. Resting orders fill *because* the price is moving against them.

  ## Simplifying assumptions (vs. real markets)

- **The fair value is known to all traders.** In reality nobody observes it.
- **Naive market makers:** cancels are random, so quotes lag the fair value
  much more than in real markets, where makers requote on every move.
- **Unbalanced flow:** at low aggressiveness, liquidity accumulates without
  limit; real makers withdraw quotes when they carry too much risk.
- **One instrument, one venue, zero latency:** no fragmentation, routing,
  network delay, or queue-position uncertainty.
- **No fees, hidden/iceberg orders, auctions, or tick-size regimes.**

## Design decisions

- **Single-threaded engine:** the simulation and UI share the JavaFX thread, so
  the book is never touched concurrently and needs no locks (the same model many
  real matching engines use).
- **Encapsulation:** only `OrderBook` modifies its trees and index; everyone
  else uses its public methods, so the structures cannot drift out of sync.
- **Immutable snapshots:** depth levels, trades, queue positions, and execution
  reports are `record`s, so readers can never modify live state.
- **Validation at boundaries:** invalid orders, duplicate ids, and non-tick
  prices are rejected before any state changes.

  ## Future updates

- [ ] Benchmarks (JMH): throughput and p50/p99 latency
- [ ] O(1) cancel via intrusive doubly linked list
- [ ] Position and P&L tracking for manual trading
- [ ] Price-aware market makers
- [ ] Replay of real recorded market data
- [ ] Network order-entry server
- [ ] C++ port of the core engine
  
  

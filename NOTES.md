A simple file to note some assumptions I've made in the process:
1. A market makers cancel randomly instead of reacting to price moves
2. The fair price is known to everyone
3. Order inflow and outflow are not balanced, so the book could potentially grow without limits.

Some Observations when comparing agressivness:
1. Having 0.05 Agressiveness results in roughly 1 tick in spread, comparing it to whooping 2-12 ticks for 0.5 Aggr.
2. Volume per second of Aggr. 0.5 is roughly thrice of the one with lower aggressiveness.

Ideas for later: 
1. Price-aware cancels
2. O(1) cancel with an intrusive linked list
3. Add some benchmarks (orders/sec, latency)

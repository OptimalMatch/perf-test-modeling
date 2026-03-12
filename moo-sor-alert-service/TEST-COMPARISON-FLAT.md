Both tests processed ~1.065M alerts with 0 failures and 0 not-found.

  Apples-to-Apples Comparison

  ┌───────────────────┬────────────┬────────────┬─────────────────┐
  │      Metric       │  Embedded  │    Flat    │     Winner      │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Duration          │ 2m 39s     │ 2m 30s     │ Flat (-6%)      │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Send rate         │ 12,527/sec │ 13,280/sec │ Flat (+6%)      │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Peak send rate    │ 24,247/sec │ 22,469/sec │ Embedded (+8%)  │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Avg throughput    │ 6,700/sec  │ 7,105/sec  │ Flat (+6%)      │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Alerts processed  │ 1,065,263  │ 1,065,815  │ ~same           │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Failures          │ 0          │ 0          │ tie             │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │                   │            │            │                 │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ P50 response      │ 817 ms     │ 736 ms     │ Flat (-10%)     │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ P95 response      │ 1,070 ms   │ 1,038 ms   │ Flat (-3%)      │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ P99 response      │ 1,404 ms   │ 1,425 ms   │ ~same           │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Max response      │ 3,262 ms   │ 3,810 ms   │ Embedded        │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │                   │            │            │                 │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Avg lookup        │ 1.20 ms    │ 1.77 ms    │ Embedded (-32%) │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Avg update        │ 1.58 ms    │ 2.46 ms    │ Embedded (-36%) │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Avg orch time     │ 3.33 ms    │ 4.40 ms    │ Embedded (-24%) │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │                   │            │            │                 │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Avg CPU (process) │ 5.2%       │ 6.0%       │ Embedded (-13%) │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Kafka pub time    │ 2.20 ms    │ 2.30 ms    │ ~same           │
  ├───────────────────┼────────────┼────────────┼─────────────────┤
  │ Cache hit ratio   │ 95.9%      │ 96.2%      │ ~same           │
  └───────────────────┴────────────┴────────────┴─────────────────┘

  Key Findings

  1. Throughput: Flat wins by ~6% — despite slower per-op DB times, the flat model achieved higher overall throughput and lower P50/P95 latencies.
  2. Per-op DB latency: Embedded wins — single _id lookup (1.20ms) beats multi-doc customerId query (1.77ms), and positional array update (1.58ms) beats separate doc update (2.46ms). Both are fast though — well under 3ms.
  3. End-to-end latency: Flat slightly better at P50/P95, embedded slightly better at tail (max). The difference is modest (<10%).
  4. CPU: Embedded is ~15% more efficient per-JVM, likely because it transfers less data per lookup (one doc vs ~5 docs).
  5. Bottom line: For this workload the two models are remarkably close. The embedded model has better per-operation efficiency, but the flat model's reduced write contention (no document-level locking on the same customer doc) gives it a slight
  throughput edge under heavy concurrent load. The difference (~6%) is within noise for most practical purposes.

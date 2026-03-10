The test completed. Here's the side-by-side comparison:

  MongoDB vs Oracle — 2M Webhook Market Crash Test

  ┌────────────────────────┬────────────┬────────────┬──────────────────────────────────┐
  │         Metric         │  MongoDB   │   Oracle   │              Delta               │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Duration               │ 2m 16s     │ 6m 37s     │ 2.9x slower                      │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Throughput (aggregate) │ 9,293 /sec │ 2,218 /sec │ 4.2x lower                       │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Per instance           │ 2,323 /sec │ 555 /sec   │                                  │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Alerts processed       │ 1,263,809  │ 880,672    │                                  │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Alerts failed          │ 0          │ 609,822    │ Oracle connection pool saturated │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ CallerRunsPolicy       │ 0          │ 406,933    │ Thread pool overwhelmed          │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ P50 response           │ 659ms      │ 995ms      │                                  │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ P99 response           │ 1,323ms    │ 7,558ms    │ 5.7x                             │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ DB lookup time         │ 1.42ms     │ 224.59ms   │ 158x slower                      │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ DB update time         │ 1.85ms     │ 142.62ms   │ 77x slower                       │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Kafka publish          │ 2.14ms     │ 1.22ms     │ Similar                          │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Cache hit ratio        │ 96.1%      │ 89.3%      │                                  │
  ├────────────────────────┼────────────┼────────────┼──────────────────────────────────┤
  │ Per-JVM CPU            │ 6.1% avg   │ 3.0% avg   │ I/O bound waiting on Oracle      │
  └────────────────────────┴────────────┴────────────┴──────────────────────────────────┘

  Key takeaways

  1. The DB lookup is the bottleneck. MongoDB returns the entire customer document (with embedded subscriptions + channels) in a single _id lookup (~1.4ms). Oracle must JOIN across 3 tables with EAGER fetching — 224ms avg, 158x slower.
  2. The DB update is also costly. MongoDB's positional $elemMatch update on the embedded array is ~1.85ms. Oracle's UPDATE ... WHERE customer_id = ? AND factset_trigger_id = ? takes 142ms — the connection pool (50 connections) becomes the bottleneck under 200 concurrent threads.
  3. 609K failures — these are likely HikariCP connection pool timeouts. All 50 connections are busy with slow queries, causing new requests to wait and eventually time out.
  4. 406K CallerRunsPolicy invocations — the async queue filled completely, forcing the HTTP controller threads to process alerts synchronously, further compounding latency.

  The MongoDB document model is a natural fit for this workload — everything needed for one customer is in one document, fetched by primary key. Oracle's normalized model requires JOINs and multiple round-trips, and the connection pool becomes a hard bottleneck under high
  concurrency.

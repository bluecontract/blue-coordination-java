# Known limitations

- The frozen Contracts API has no autonomous ownership-mask input. Coordination
  therefore uses an explicit ownership projection before frozen processing; it
  does not claim exact semantic-parent fidelity across child-owned subscription
  surfaces.
- Journal completeness is proven only for the current in-memory journal. There
  is no durable or distributed transaction protocol.
- Dynamic parent membership is unsupported and fails closed.
- `Process Embedded` collections are unsupported; embedded boundaries must be
  stable object fields.
- External frontier import is unsupported until cursor and provider
  completeness can be proven durably.
- Deterministic failed retries stabilize whole-object cache size for the same
  failure. Distinct failed results can leave unreachable immutable cache values;
  retention is an in-memory host policy.

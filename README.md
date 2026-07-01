# base-l2

`@etzhayyim/base-l2` — Base L2 MST-root anchor client (viem) + ERC-4337
sponsored-write helper.

| Module | What it does |
|---|---|
| `l2.ts` | `AnchorClient` — anchors MST root CIDs to an `EtzhayyimAnchor`-shaped contract via viem's public/wallet clients |
| `paymaster.ts` | `sponsoredWriteContract(opts)` — a near-drop-in for `walletClient.writeContract` that routes through an ERC-4337 `UserOperation` + bundler + paymaster instead of a direct EOA tx |

Provider-agnostic: no bundler/paymaster vendor binding (Pimlico/Stackup/
Coinbase/etc. are wired by the caller via dependency injection). Only
`ANCHOR_ABI` and its contract address carry a thin etzhayyim-flavored
default, same category as `kotoba-lang/checkpointer`'s default socket path.

## Provenance

Relocated 2026-07-01 from `etzhayyim/root:20-actors/etzhayyim-sdk/src/
{l2,paymaster}.ts` to `kotoba-lang/base-l2` per the org-taxonomy
library-placement rule (ADR-2606302300). Design authority remains
ADR-2605171800 Stage 5a (`l2.ts`) and ADR-2605172100 "Gas sponsorship"
(`paymaster.ts`), both in `etzhayyim/root`.

No live consumer imports `@etzhayyim/sdk/{l2,paymaster}` today (only the
`substrate-boundary` lint rule's guidance text references `l2`) —
`etzhayyim-sdk`'s own `src/{l2,paymaster}.ts` still become re-export shims
to honor that public API contract, matching the pattern established by
`kotoba-lang/{ipfs,checkpointer}`.

This is a **physical move only** (TypeScript unchanged) — no dedicated
tests existed to bring along, and a CLJC port is deferred to a later,
separate task.

**`dist/` is committed** (see `kotoba-lang/pqh`'s README for the rationale).

## Development

```bash
npm install
npm run build
```

## License

Apache 2.0 + Charter Compliance Rider v3.6 (`/CHARTER-RIDER.md`).

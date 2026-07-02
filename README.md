# base-l2

`kotoba.lang.base-l2` — Base L2 MST-root anchor client + ERC-4337 sponsored-write
helper, as **pure Clojure/CLJC cores over injected transports**.

The library performs **zero network I/O**: every JSON-RPC call goes through a
host-supplied `kotoba.lang.base-l2.rpc/ITransport`, and every sponsored
UserOperation goes through host-supplied `Bundler`/`SmartAccount` protocols
(`paymaster.clj`). The host backs `ITransport` with `babashka.http-client`
(JVM) / `fetch`. This is the kotoba-lang layer contract — pure Clojure, zero
network I/O, zero vendor SDK (ADR-2606302300 §Step-1). The original `viem`
vendor SDK has no place inside the library.

| Namespace | Ext | Role |
|---|---|---|
| `base-l2.abi` | `.clj` | narrow hand-rolled Ethereum ABI encode/decode (pure logic, but JVM-only transitively — needs a real Keccak-256/EIP-55 via `eth-crypto.core`, which has zero CLJS portability; see the namespace docstring) |
| `base-l2.rpc` | `.cljc` | JSON-RPC orchestration over injected `ITransport` (7 `eth_*` methods) — portable JVM+CLJS, except `wait-for-transaction-receipt` (`Thread/sleep`-blocking, `#?(:clj ...)`-gated JVM-only) |
| `base-l2.l2` | `.clj` | `AnchorClient` — anchor / rootCount / anchors; signs EIP-155 legacy tx server-side via `eth-crypto`; drives `rpc` (JVM-only: private-key custody policy AND direct `eth-crypto`/`abi` deps) |
| `base-l2.paymaster` | `.clj` | ERC-4337 sponsored write over injected `Bundler`/`SmartAccount` (never holds a key; the file's own logic is CLJS-agnostic, but it's transitively blocked by `abi.clj`) |

JVM-only (`.clj`): `abi` (transitively, via `eth-crypto`'s Keccak-256 — see its
docstring), `l2` (signs raw transactions with a caller-held private key,
server-side `anchor-cron` consumer, AND transitively via `abi`/`eth-crypto`),
`paymaster` (transitively via `abi`). `rpc` is portable `.cljc`; only its
`Thread/sleep`-polling `wait-for-transaction-receipt` is JVM-only.

## Provenance

Relocated 2026-07-01 from `etzhayyim/root:20-actors/etzhayyim-sdk/src/{l2,paymaster}.ts`
to `kotoba-lang/base-l2`. Ported to Clojure with an injected-transport seam; the
TypeScript has been **deleted** — the `.clj` cores are the single canonical
implementation. Reuses `kotoba-lang/eth-crypto` (Keccak-256 / secp256k1 / RLP / EIP-155).

## Develop

```bash
clojure -M:lint     # clj-kondo (errors fail)
clojure -M:test     # viem cross-checked ABI + signing vectors; mock JSON-RPC
```

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
tests existed to bring along.

**`dist/` is committed** (see `kotoba-lang/pqh`'s README for the rationale).

## Development

```bash
npm install
npm run build
```

## Clojure/CLJC port

`src/kotoba/lang/base_l2/{abi,rpc,l2,paymaster}.clj` is the Clojure port of
`l2.ts`/`paymaster.ts`, following the pattern established by
`kotoba-lang/ipfs` in this same porting wave.

### Scoping the actual on-chain surface

`viem` is a large general-purpose Ethereum library, but `l2.ts`/`paymaster.ts`
only ever touch a narrow slice of it:

- `l2.ts`'s `AnchorClient` calls exactly 3 contract methods (`anchor` /
  `rootCount` / `anchors`, all flat scalar/`bytes` argument lists — never
  arrays or nested structs) and does 2 kinds of RPC work: `eth_call` reads,
  and — for `anchorMstRoot` — a real signed write: build an unsigned
  transaction, sign it locally with a raw private key
  (`privateKeyToAccount` + `walletClient.writeContract`), submit via
  `eth_sendRawTransaction`, then poll `eth_getTransactionReceipt`.
- `paymaster.ts`'s `sponsoredWriteContract` ABI-encodes an arbitrary
  caller-supplied call and hands it to a **caller-injected** `BundlerClient`
  + `SmartAccount` (`bundle.bundler.sendUserOperation` /
  `waitForUserOperationReceipt`) — it never signs anything itself; the
  ERC-4337 UserOperation signing happens inside the injected `SmartAccount`.

### RPC transport: Option A (hand-rolled JSON-RPC), not a viem/web3j-style library

The transport is a ~150-line hand-rolled JSON-RPC-over-HTTP client
(`kotoba.lang.base-l2.rpc`, `babashka.http-client` + `cheshire`, the same
stack `kotoba-lang/ipfs` uses) exposing exactly the 7 `eth_*` methods this
SDK slice calls (`eth_call`, `eth_sendRawTransaction`,
`eth_getTransactionReceipt`, `eth_getTransactionCount`, `eth_gasPrice`,
`eth_chainId`, `eth_estimateGas`). ABI encode/decode
(`kotoba.lang.base-l2.abi`) is likewise hand-rolled but narrow: it supports
flat argument lists of `uintN`/`intN`/`address`/`bool`/`bytesN`/`bytes`/
`string` (covering every call this SDK makes, including
`paymaster.ts`'s own docstring example `join(bytes32, string)`) and
explicitly does **not** implement arrays or tuples/structs — out of scope
because nothing in this SDK's surface needs them (see the namespace
docstring for the exact boundary and how to extend it).

### Crypto: reuse `kotoba-lang/eth-crypto`, not BouncyCastle/web3j

`l2.ts`'s write path needs real secp256k1 ECDSA signing + Keccak-256 +
RLP + EIP-155 transaction serialization to sign a raw transaction with a
private key — implementing elliptic-curve signing by hand is exactly the
kind of cryptographic code that's unreasonably error-prone to get right
(nonce-reuse/malleability bugs in hand-rolled ECDSA can leak the private
key). Rather than hand-roll it a second time or pull in a heavy dependency
(BouncyCastle, web3j), this port depends on **`kotoba-lang/eth-crypto`**,
a sibling dependency-free Clojure library already in this org (verified
against the canonical EIP-712 "Ether Mail" and EIP-155 spec vectors) that
implements exactly these primitives in pure `clojure.*` + `java.math.BigInteger`.
This keeps the whole dependency chain in the same "zero/minimal deps,
babashka-portable" family as `ipfs`/`witness-quorum`'s crypto, instead of
introducing web3j's much larger surface for 4 functions' worth of need.

Every crypto/ABI known-answer vector this port is tested against
(function selectors, `encodeFunctionData`/`decodeFunctionResult` shapes,
and — most importantly — full raw signed legacy transactions across
several nonce/gasPrice/chainId combinations, including a real `anchor()`
call) was generated with **this repo's own `viem` dependency** via a
throwaway `node` script (not checked in; only the JSON output is, under
`test/resources/base_l2/`), then cross-checked byte-for-byte against this
port's output — not hand-typed, per the standing lesson from this porting
wave that hand-transcribing long hex strings reliably introduces bugs.

### Deliberate simplification: legacy (not EIP-1559) transactions

`l2.ts` delegates tx-type selection and fee estimation entirely to viem's
automatic negotiation (EIP-1559 vs. legacy, based on what the chain
reports). This port always builds an **EIP-155 legacy** transaction —
universally accepted by Base L2 and any anvil/Hardhat/Geth dev chain (this
SDK's own default target), simpler to keep byte-exact, and every
nonce/gas-price/gas/chain-id field is either caller-supplied via `opts` or
resolved with one RPC call each. EIP-1559 support (lower gas cost on
congested mainnet) can be added later by extending `eth-crypto` with a
`sign-tx-eip1559` and wiring it in here — deferred because nothing in this
SDK's own docstring or consumer history required fee-market optimization.

### CLJS scope: JVM-only (`.clj`), not `.cljc` — for two independent reasons

Both modules stay plain `.clj` (no CLJS reader-conditional branches),
mirroring the sibling `kotoba-lang/witness-quorum` port's JVM-only call,
but for reasons specific to this package:

1. **`l2.ts`'s `AnchorClient` holds a raw private key and signs
   transactions locally.** That is a server-side-signing concern — the
   production consumer this design serves is the `anchor-cron` K8s
   CronJob (`etzhayyim/root:50-infra/anchor-cron/`, ADR-2605171800 Stage
   5b), not a browser dapp. Holding a raw hex private key in an in-page
   JS/CLJS runtime would in fact cut against this org's own
   no-server-key/no-custodial-key doctrine for user-facing flows.
2. **`paymaster.ts`, by contrast, never touches a private key itself** —
   UserOperation signing happens inside the caller-injected `SmartAccount`
   (typically a WebAuthn-passkey-backed Coinbase Smart Wallet), so its own
   design is a genuinely plausible browser-dapp consumer (passkey-in-browser
   signing is exactly this org's no-server-key doctrine for user-facing
   writes) — a hypothetical CLJS branch would reach a bundler HTTP endpoint
   via `js/fetch`, mirroring `kotoba-lang/ipfs`'s CLJS branch reaching Kubo.
   It stays JVM-only anyway in this pass because it shares
   `kotoba.lang.base-l2.abi` for its generic (caller-supplied-signature)
   ABI encoding, and a faithful ABI encoder needs arbitrary-precision
   integer arithmetic (`uint256` exceeds a JS safe integer) that isn't
   natively portable to CLJS without adding a bignum dependency —
   deliberately out of scope for this pass.

If/when a concrete CLJS bundler-dapp consumer for `paymaster.clj`
materializes: split it into a CLJC orchestration layer (the
`Bundler`/`SmartAccount` protocols + `sponsored-write-contract!`, already
platform-agnostic as written — zero crypto, zero HTTP) over a per-platform
ABI-encoding implementation (this JVM one, plus a `:cljs` one — e.g. via
`goog.math.Integer` or `js/BigInt` — added at that time).

### Tests

`clojure -M:test` (JVM only — the write-path test mocks a JSON-RPC HTTP
endpoint via `com.sun.net.httpserver.HttpServer`, part of the JDK but not
loadable from babashka's restricted classlist, same constraint
`kotoba-lang/ipfs`'s test suite documents):

```bash
clojure -M:test
```

18 tests / 76 assertions across 3 namespaces: `abi-test` (every ABI
encode/decode case against viem-generated vectors), `l2-test` (the full
`anchor-mst-root!` write path against a mock RPC server, including a
byte-exact raw-transaction comparison to viem's own signed output for a
real `anchor()` call; `root-count`/`find-anchor-for-root` reads; the
revert-throws and no-private-key-throws error paths), and
`paymaster-test` (`sponsored-write-contract!` against in-memory fake
`Bundler`/`SmartAccount` implementations, since the real bundler/paymaster
are dependency-injected by the caller in both the TS and the Clojure
version).

`clj-kondo --lint src test` is clean (0 errors, 0 warnings).

## License

Apache 2.0 + Charter Compliance Rider v3.6 (`/CHARTER-RIDER.md`).

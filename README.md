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
`kotoba-lang/ipfs` in this same porting wave: a **pure core over an
injected transport**, zero vendor/HTTP-client dep in `src/`.

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

### RPC transport: hand-rolled JSON-RPC, PURE core over an injected `ITransport`

The transport is a ~150-line hand-rolled JSON-RPC-over-HTTP client
(`kotoba.lang.base-l2.rpc`) exposing exactly the 7 `eth_*` methods this
SDK slice calls (`eth_call`, `eth_sendRawTransaction`,
`eth_getTransactionReceipt`, `eth_getTransactionCount`, `eth_gasPrice`,
`eth_chainId`, `eth_estimateGas`) — but, following the design
`kotoba-lang/ipfs` established for `kotoba.lang.ipfs/IHttp` in this same
porting wave, it performs **zero network I/O itself**. `rpc.clj` defines
`ITransport`, a host-injected transport protocol:

```clojure
(defprotocol ITransport
  (-post [this url body] "POST the JSON string `body` to `url`. => {:status Int :body String}."))
```

Unlike `IHttp` (3 methods — Kubo's REST-ish HTTP API needs distinct
GET/POST/multipart-POST shapes), JSON-RPC-over-HTTP has exactly ONE wire
shape (POST a JSON request, get a JSON response), so `ITransport` is a
single method. `rpc.clj` builds every JSON-RPC 2.0 envelope, detects
`:error` objects, and extracts `:result` — the host only moves bytes.
JSON encode/decode uses `clojure.data.json` (the same minimal,
dependency-free choice `kotoba-lang/ipfs` makes) — that stays IN the pure
core, unlike the HTTP client, because the JSON-RPC wire format demands it
no matter what moves the bytes. **`src/`'s `deps.edn` entry carries zero
HTTP-client dependency** — `org.babashka/http-client` moved to the
`:test` alias, backing a reference `ITransport` adapter
(`test/kotoba/lang/base_l2/jvm_http_transport.clj`) used only to prove
the injection point works end-to-end against a real mock JSON-RPC HTTP
server in `l2-test`. A real consumer (e.g. the `anchor-cron` K8s CronJob)
supplies its own `ITransport` the same way.

`kotoba.lang.base_l2.l2/AnchorClient` carries the transport alongside
`rpc-url`/`contract`/`private-key` (`make-anchor-client`'s `cfg` map
gains a required `:transport` key) — every client-based call
(`anchor-mst-root!`/`root-count`/`find-anchor-for-root`) reads it off
`client` rather than taking it as a separate argument, since `client` is
already the natural connection bundle here. The two module-level
convenience functions that DON'T carry a client — `submit-mst-root!` /
`lookup-anchor-for-root` — take `transport` as an explicit new leading
argument instead (mirroring `kotoba.lang.ipfs`'s `(pin-blob http api-url
content)` convention); this is the one public-API signature change this
retrofit makes, and it's inherent to removing the embedded default HTTP
client — there's no more default to fall back to, so the caller must
supply one. `rpc.clj`'s own `eth-*` functions all take `transport` first
too, for the same reason.

ABI encode/decode (`kotoba.lang.base-l2.abi`) is likewise hand-rolled but
narrow, and needed **no changes** for this retrofit — it was already pure
(no I/O of any kind). It supports flat argument lists of
`uintN`/`intN`/`address`/`bool`/`bytesN`/`bytes`/`string` (covering every
call this SDK makes, including `paymaster.ts`'s own docstring example
`join(bytes32, string)`) and explicitly does **not** implement arrays or
tuples/structs — out of scope because nothing in this SDK's surface needs
them (see the namespace docstring for the exact boundary and how to
extend it).

`kotoba.lang.base-l2.paymaster` also needed **no changes**: it never did
its own HTTP in the first place — `sponsored-write-contract!` only
ABI-encodes calldata and hands it to the caller-injected `Bundler`
(`send-user-operation!` / `wait-for-user-op-receipt!`) and `SmartAccount`
protocols, so it was already a pure orchestration layer over injected
dependencies (see the namespace docstring's "WHY THIS MODULE NEVER SIGNS
ANYTHING ITSELF" section) — a good model this retrofit brought `rpc.clj`
in line with, rather than something that itself needed retrofitting.

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

29 tests / 103 assertions across 4 namespaces: `abi-test` (every ABI
encode/decode case against viem-generated vectors, unchanged by this
retrofit), `rpc-test` (NEW — unit tests for the pure JSON-RPC envelope
logic in `rpc.clj` — request construction, `:error` detection, `:result`
extraction, every `eth-*` wrapper, and `wait-for-transaction-receipt`'s
polling/timeout — against a fake in-memory `ITransport`, no real socket),
`l2-test` (the full `anchor-mst-root!` write path against a *real* mock
JSON-RPC HTTP server reached through the reference
`babashka.http-client`-backed `jvm-http-transport` adapter — proving the
injection point works end-to-end over an actual socket, including a
byte-exact raw-transaction comparison to viem's own signed output for a
real `anchor()` call; `root-count`/`find-anchor-for-root` reads; the
revert-throws and no-private-key-throws error paths), and
`paymaster-test` (`sponsored-write-contract!` against in-memory fake
`Bundler`/`SmartAccount` implementations, since the real bundler/paymaster
are dependency-injected by the caller in both the TS and the Clojure
version — unchanged by this retrofit, `paymaster.clj` needed none).

`clj-kondo --lint src test` is clean (0 errors, 0 warnings).

## License

Apache 2.0 + Charter Compliance Rider v3.6 (`/CHARTER-RIDER.md`).

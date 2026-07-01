(ns kotoba.lang.base-l2.paymaster
  "CLJ port of `paymaster.ts` -- an ERC-4337 sponsored-write helper. Per
  ADR-2605172100 \"Gas sponsorship\": when a sponsored bundle is wired, a
  Base-side write routes through a UserOperation sent to a bundler, with
  the etzhayyim Paymaster covering gas, instead of a direct EOA tx.

  Scope of this module (mirrors `paymaster.ts` exactly):
    - Pin the shape of the sponsored bundle so an app can wire a bundler
      + paymaster + SmartAccount via dependency injection (here:
      `Bundler` / `SmartAccount` protocols, not concrete viem clients).
    - Expose `sponsored-write-contract!`, a near-drop-in for a direct
      `write-contract!`-style call: same address/function-signature/args
      shape, returns the resulting Base L2 tx hash once the
      UserOperation is mined.
  Out of scope (same as `paymaster.ts`):
    - Wiring a specific bundler / paymaster provider (Pimlico / Stackup
      / Coinbase / etc. -- plug in per deployment, outside this module).
    - An off-chain paymaster signature endpoint (`validatePaymasterUserOp`
      sig from a registered signer) -- the etzhayyim paymaster v0 uses
      allowlist-only validation, so a bare paymaster address suffices.

  WHY THIS MODULE NEVER SIGNS ANYTHING ITSELF (load-bearing for the
  CLJS-scope decision below): `paymaster.ts`'s `sponsoredWriteContract`
  never touches a private key or does any ECDSA/EIP-712 signing -- it
  only ABI-encodes calldata and calls `bundle.bundler.sendUserOperation`
  / `waitForUserOperationReceipt`. The UserOperation signing happens
  inside the caller-supplied `SmartAccount` (typically a WebAuthn-passkey
  -backed Coinbase Smart Wallet), which this module treats as an opaque
  dependency. That makes `paymaster.ts`'s OWN design plausible for a
  browser dapp (passkey-in-browser signing is exactly this org's
  no-server-key doctrine for user-facing writes) -- unlike `l2.ts`'s
  `AnchorClient`, which holds a raw private key directly.

  CLJS SCOPE (deliberately deferred, not implemented here): despite the
  above, this port stays JVM-only (.clj) for now because
  `sponsored-write-contract!` still needs to ABI-encode arbitrary
  caller-supplied args (`kotoba.lang.base-l2.abi`), and that encoder
  needs arbitrary-precision integer arithmetic (uint256 exceeds a JS
  safe integer / a bare `js/BigInt` port isn't free) to stay correct --
  out of scope for this pass. If/when a concrete CLJS bundler-dapp
  consumer materializes: split this namespace into a CLJC orchestration
  layer (the `Bundler`/`SmartAccount` protocols + `sponsored-write-contract!`,
  zero crypto, already platform-agnostic as written) over a per-platform
  ABI-encoding implementation (this JVM one, plus a `:cljs` one added at
  that time)."
  (:require [kotoba.lang.base-l2.abi :as abi]))

;; ─── injection-point protocols (mirrors SponsoredBundle's fields) ─────

(defprotocol Bundler
  "The caller's chosen ERC-4337 bundler (Pimlico, Stackup, Alchemy AA,
  Coinbase Smart Wallet RPC, …). This module never implements a concrete
  bundler -- callers `reify`/`defrecord` this protocol over whatever
  bundler client they've wired for their deployment."
  (send-user-operation! [bundler op]
    "`op` is `{:account smart-account :calls [{:to :data :value}] :paymaster addr :gas-overrides {...}}`.
    Returns the `0x…` UserOperation hash.")
  (wait-for-user-op-receipt! [bundler user-op-hash]
    "Returns `{:success bool :receipt {:transaction-hash \"0x…\"}}` once
    the UserOperation is mined (or reverted)."))

(defprotocol SmartAccount
  "The adherent's SmartAccount -- typically a Coinbase Smart Wallet
  signing via WebAuthn passkey per ADR-2605172100 §\"Account model\".
  Caller constructs this; this module only ever reads its address."
  (account-address [account]
    "The SmartAccount's on-chain (counterfactual or deployed) address."))

;; ─── sponsored-write-contract! ─────────────────────────────────────────

(defn sponsored-write-contract!
  "Submit a single sponsored write to a Base-side contract via the
  bundle's `:bundler` + `:paymaster-address`, signed by `:smart-account`.
  Returns the L2 tx hash once the UserOperation receipt is finalized.

  `call` is `{:address \"0x…\" :function-signature \"join(bytes32,string)\"
  :arg-types [\"bytes32\" \"string\"] :arg-values [oath-hash \"alice-on-github\"]
  :value 0}` (`:value` optional, defaults to 0 -- most SDK calls, USDC
  transfers and registry writes, are valueless).

  `bundle` is `{:bundler (a Bundler) :smart-account (a SmartAccount)
  :paymaster-address \"0x…\" :gas-overrides {:call-gas-limit … :verification-gas-limit …
  :pre-verification-gas …}}` (`:gas-overrides` optional; when omitted the
  bundler is asked to estimate, same as `paymaster.ts`).

  Throws ex-info if the UserOperation receipt reports failure."
  [{:keys [address function-signature arg-types arg-values value]} bundle]
  (let [{:keys [bundler smart-account paymaster-address gas-overrides]} bundle
        call-data (abi/encode-function-call function-signature arg-types arg-values)
        op (merge {:account smart-account
                   :calls [{:to address :data call-data :value (or value 0)}]
                   :paymaster paymaster-address}
                  gas-overrides)
        user-op-hash (send-user-operation! bundler op)
        receipt (wait-for-user-op-receipt! bundler user-op-hash)]
    (when-not (:success receipt)
      (throw (ex-info (str "[kotoba.lang.base-l2.paymaster] sponsored UserOp " user-op-hash
                            " reverted; check paymaster allowlist (validatePaymasterUserOp)"
                            " and per-sender daily cap")
                       {:user-op-hash user-op-hash :receipt receipt})))
    (get-in receipt [:receipt :transaction-hash])))

(defn resolve-sponsored-holder
  "Resolve the adherent's effective Base-side address -- the
  SmartAccount's address. Useful for cases where `holder` defaults to
  \"whoever I am on Base\"."
  [{:keys [smart-account]}]
  (account-address smart-account))

/**
 * @etzhayyim/sdk/paymaster — ERC-4337 sponsored-write helper.
 *
 * Per ADR-2605172100 §"Gas sponsorship": when a `BIConfig.sponsored`
 * bundle is wired, the SDK routes `_baseJoin` (and any other Base-side
 * adherent-initiated write) through a UserOperation sent to a bundler,
 * with the etzhayyim Paymaster (contract at
 * `50-infra/etzhayyim-paymaster/`) covering gas. Without `sponsored`,
 * the SDK falls back to the EOA `writeContract` path so the rest of
 * the surface stays operable for testing and self-paid users.
 *
 * Scope of this module:
 *   - Pin the shape of the `BIConfig.sponsored` bundle so an app can
 *     wire a bundler + paymaster + SmartAccount via dependency
 *     injection.
 *   - Expose a `sponsoredWriteContract(opts)` whose signature is a
 *     near-drop-in for `walletClient.writeContract`: same `address` +
 *     `abi` + `functionName` + `args`, returns the resulting Base L2
 *     tx hash once the UserOperation is mined.
 *
 * Out of scope:
 *   - Wiring a specific bundler / paymaster provider. The production
 *     setup (Pimlico / Stackup / Coinbase) is plug-in per deployment
 *     and lives outside the SDK.
 *   - Off-chain paymaster signature endpoint (some paymasters require
 *     a `validatePaymasterUserOp` sig from a registered signer; the
 *     etzhayyim paymaster v0 uses allowlist-only validation so a
 *     bare `paymaster: address` form suffices).
 */

import {
  encodeFunctionData,
  type Abi,
  type Address,
  type Hash,
  type Hex,
} from "viem";
import type {BundlerClient, SmartAccount} from "viem/account-abstraction";

// ─── Config bundle ──────────────────────────────────────────────────

export interface SponsoredBundle {
  /**
   * viem `BundlerClient` instance pointing at the deployment's chosen
   * ERC-4337 bundler (Pimlico, Stackup, Alchemy AA, Coinbase Smart
   * Wallet RPC, …). The SDK does not bind to a specific provider.
   */
  bundler: BundlerClient;

  /**
   * The adherent's `SmartAccount`. Typically a Coinbase Smart Wallet
   * (`toCoinbaseSmartAccount`) signing via WebAuthn passkey per
   * ADR-2605172100 §"Account model". Caller constructs this; the SDK
   * consumes it.
   */
  smartAccount: SmartAccount;

  /**
   * Address of the etzhayyim Paymaster contract on Base. See
   * `50-infra/etzhayyim-paymaster/` for the deployed bytecode.
   * Passed to `bundler.sendUserOperation({paymaster: ...})`.
   */
  paymasterAddress: Address;

  /**
   * Optional fixed gas limits (caller-supplied). If omitted, the
   * bundler is asked to estimate.
   */
  gasOverrides?: {
    callGasLimit?: bigint;
    verificationGasLimit?: bigint;
    preVerificationGas?: bigint;
  };
}

// ─── sponsoredWriteContract ─────────────────────────────────────────

export interface SponsoredWriteOpts<TAbi extends Abi> {
  address: Address;
  abi: TAbi;
  functionName: string;
  args: readonly unknown[];
  /**
   * Optional ETH value. Most SDK calls (USDC transfers, registry
   * writes) are valueless.
   */
  value?: bigint;
}

/**
 * Submit a single sponsored write to a Base-side contract via the
 * provided bundler + paymaster. Returns the L2 tx hash once the
 * UserOperation receipt is finalized.
 *
 * @example
 *   const txHash = await sponsoredWriteContract(
 *     {
 *       address: ETZHAYYIM_MEMBERSHIP,
 *       abi: ETZHAYYIM_MEMBERSHIP_ABI,
 *       functionName: "join",
 *       args: [oathHash, "alice-on-github"],
 *     },
 *     cfg.sponsored
 *   );
 */
export async function sponsoredWriteContract<TAbi extends Abi>(
  opts: SponsoredWriteOpts<TAbi>,
  bundle: SponsoredBundle
): Promise<Hash> {
  // viem's encodeFunctionData has narrow inference that does not
  // match our generic TAbi pass-through; the runtime semantics are
  // identical, so we widen the inferred shape with a typed cast.
  const callData: Hex = encodeFunctionData({
    abi: opts.abi,
    functionName: opts.functionName,
    args: opts.args,
  } as Parameters<typeof encodeFunctionData>[0]);

  const userOpHash = await bundle.bundler.sendUserOperation({
    account: bundle.smartAccount,
    calls: [
      {
        to: opts.address,
        data: callData,
        value: opts.value ?? 0n,
      },
    ],
    paymaster: bundle.paymasterAddress,
    ...(bundle.gasOverrides ?? {}),
  });

  const receipt = await bundle.bundler.waitForUserOperationReceipt({
    hash: userOpHash,
  });
  if (!receipt.success) {
    throw new Error(
      `[etzhayyim-sdk/paymaster] sponsored UserOp ${userOpHash} reverted; ` +
        "check paymaster allowlist (validatePaymasterUserOp) and per-sender daily cap"
    );
  }
  return receipt.receipt.transactionHash as Hash;
}

/**
 * Resolve the adherent's effective Base-side address — the SmartAccount
 * address when sponsored, else the bundler's wallet account address.
 * Useful for cases where `holder` defaults to "whoever I am on Base".
 */
export async function resolveSponsoredHolder(bundle: SponsoredBundle): Promise<Address> {
  return bundle.smartAccount.address;
}

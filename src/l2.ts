/**
 * @etzhayyim/sdk/l2 — Base L2 anchor helpers.
 *
 * v0.1.0: real implementation using viem. Anchors MST root CIDs to the
 * EtzhayyimAnchor contract per ADR-2605171800 Stage 5a.
 *
 * Default chain is anvil-local-260425 (`http://localhost:8545`) for dev.
 * Production points at Base L2 mainnet (`https://mainnet.base.org`) once
 * the contract is deployed there.
 */

import {
  createPublicClient,
  createWalletClient,
  http,
  type Address,
  type Hash,
  type Hex,
  type PublicClient,
  type WalletClient,
} from "viem";
import { privateKeyToAccount } from "viem/accounts";

/**
 * Minimal ABI for the only methods the SDK touches on EtzhayyimAnchor.
 * Full ABI in 50-infra/l2-anchor-contract/out/.
 */
export const ANCHOR_ABI = [
  {
    type: "function",
    name: "anchor",
    inputs: [
      { name: "rootHash", type: "bytes32" },
      { name: "ipfsCid", type: "bytes" },
      { name: "batchSize", type: "uint64" },
    ],
    outputs: [],
    stateMutability: "nonpayable",
  },
  {
    type: "function",
    name: "rootCount",
    inputs: [],
    outputs: [{ name: "", type: "uint256" }],
    stateMutability: "view",
  },
  {
    type: "function",
    name: "anchors",
    inputs: [{ name: "", type: "bytes32" }],
    outputs: [
      { name: "rootHash", type: "bytes32" },
      { name: "ipfsCid", type: "bytes" },
      { name: "blockNumber", type: "uint256" },
      { name: "anchorer", type: "address" },
      { name: "batchSize", type: "uint64" },
      { name: "anchoredAt", type: "uint64" },
    ],
    stateMutability: "view",
  },
  {
    type: "event",
    name: "Anchored",
    inputs: [
      { name: "rootHash", type: "bytes32", indexed: true },
      { name: "anchorer", type: "address", indexed: true },
      { name: "ipfsCid", type: "bytes", indexed: false },
      { name: "blockNumber", type: "uint256", indexed: false },
      { name: "batchSize", type: "uint64", indexed: false },
    ],
  },
] as const;

export interface AnchorClientConfig {
  rpcUrl: string;
  contract: Address;
  privateKey: Hex; // 0x-prefixed 32-byte hex
}

export class AnchorClient {
  readonly publicClient: PublicClient;
  readonly walletClient: WalletClient;
  readonly contract: Address;
  readonly account;

  constructor(cfg: AnchorClientConfig) {
    this.contract = cfg.contract;
    this.account = privateKeyToAccount(cfg.privateKey);
    this.publicClient = createPublicClient({
      transport: http(cfg.rpcUrl),
    });
    this.walletClient = createWalletClient({
      account: this.account,
      transport: http(cfg.rpcUrl),
    });
  }

  /**
   * Submit a batched MST root anchor.
   * @param rootHash 32-byte hash of the canonical MST root CID.
   * @param ipfsCid raw bytes of the multibase-encoded CID.
   * @param batchSize informational record count in this batch.
   * @returns the receipt with on-chain finality.
   */
  async anchorMstRoot(
    rootHash: Hex,
    ipfsCid: Uint8Array | Hex,
    batchSize: bigint
  ): Promise<{ txHash: Hash; blockNumber: bigint }> {
    const ipfsCidHex: Hex =
      typeof ipfsCid === "string"
        ? ipfsCid
        : ("0x" +
            Array.from(ipfsCid)
              .map((b) => b.toString(16).padStart(2, "0"))
              .join("")) as Hex;

    const txHash = await this.walletClient.writeContract({
      address: this.contract,
      abi: ANCHOR_ABI,
      functionName: "anchor",
      args: [rootHash, ipfsCidHex, batchSize],
      account: this.account,
      chain: null,
    });
    const receipt = await this.publicClient.waitForTransactionReceipt({
      hash: txHash,
    });
    if (receipt.status !== "success") {
      throw new Error(`[etzhayyim-sdk/l2] anchor tx reverted: ${txHash}`);
    }
    return { txHash, blockNumber: receipt.blockNumber };
  }

  /** Read the global anchor count. */
  async rootCount(): Promise<bigint> {
    return (await this.publicClient.readContract({
      address: this.contract,
      abi: ANCHOR_ABI,
      functionName: "rootCount",
    })) as bigint;
  }

  /**
   * Look up the anchor record for a given root hash, or null if not anchored.
   */
  async findAnchorForRoot(rootHash: Hex): Promise<{
    txAnchorerAddress: Address;
    blockNumber: bigint;
    batchSize: bigint;
    anchoredAt: bigint;
  } | null> {
    const result = (await this.publicClient.readContract({
      address: this.contract,
      abi: ANCHOR_ABI,
      functionName: "anchors",
      args: [rootHash],
    })) as readonly [Hex, Hex, bigint, Address, bigint, bigint];
    const [, , blockNumber, anchorer, batchSize, anchoredAt] = result;
    if (blockNumber === 0n) return null;
    return {
      txAnchorerAddress: anchorer,
      blockNumber,
      batchSize,
      anchoredAt,
    };
  }
}

// ─── Functional API (matches earlier stub surface) ──────────────────

export async function anchorMstRoot(
  rpcUrl: string,
  contract: Address,
  rootCid: string,
  signer: { privateKey: Hex },
  ipfsCidBytes?: Uint8Array,
  batchSize: bigint = 1n
): Promise<{ txHash: Hash; blockNumber: bigint }> {
  const client = new AnchorClient({
    rpcUrl,
    contract,
    privateKey: signer.privateKey,
  });
  // If rootCid is a 0x-prefixed hex digest, use as-is; otherwise compute keccak256(rootCid bytes).
  let rootHash: Hex;
  if (rootCid.startsWith("0x") && rootCid.length === 66) {
    rootHash = rootCid as Hex;
  } else {
    const { keccak256, toBytes } = await import("viem");
    rootHash = keccak256(toBytes(rootCid));
  }
  const cidBytes = ipfsCidBytes ?? new TextEncoder().encode(rootCid);
  return client.anchorMstRoot(rootHash, cidBytes, batchSize);
}

export async function findAnchorForRoot(
  rpcUrl: string,
  contract: Address,
  rootHash: Hex
): Promise<{ blockNumber: bigint; anchorer: Address } | null> {
  const client = new AnchorClient({
    rpcUrl,
    contract,
    privateKey: ("0x" + "00".repeat(32)) as Hex, // read-only dummy
  });
  const result = await client.findAnchorForRoot(rootHash);
  if (!result) return null;
  return {
    blockNumber: result.blockNumber,
    anchorer: result.txAnchorerAddress,
  };
}

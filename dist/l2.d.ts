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
import { type Address, type Hash, type Hex, type PublicClient, type WalletClient } from "viem";
/**
 * Minimal ABI for the only methods the SDK touches on EtzhayyimAnchor.
 * Full ABI in 50-infra/l2-anchor-contract/out/.
 */
export declare const ANCHOR_ABI: readonly [{
    readonly type: "function";
    readonly name: "anchor";
    readonly inputs: readonly [{
        readonly name: "rootHash";
        readonly type: "bytes32";
    }, {
        readonly name: "ipfsCid";
        readonly type: "bytes";
    }, {
        readonly name: "batchSize";
        readonly type: "uint64";
    }];
    readonly outputs: readonly [];
    readonly stateMutability: "nonpayable";
}, {
    readonly type: "function";
    readonly name: "rootCount";
    readonly inputs: readonly [];
    readonly outputs: readonly [{
        readonly name: "";
        readonly type: "uint256";
    }];
    readonly stateMutability: "view";
}, {
    readonly type: "function";
    readonly name: "anchors";
    readonly inputs: readonly [{
        readonly name: "";
        readonly type: "bytes32";
    }];
    readonly outputs: readonly [{
        readonly name: "rootHash";
        readonly type: "bytes32";
    }, {
        readonly name: "ipfsCid";
        readonly type: "bytes";
    }, {
        readonly name: "blockNumber";
        readonly type: "uint256";
    }, {
        readonly name: "anchorer";
        readonly type: "address";
    }, {
        readonly name: "batchSize";
        readonly type: "uint64";
    }, {
        readonly name: "anchoredAt";
        readonly type: "uint64";
    }];
    readonly stateMutability: "view";
}, {
    readonly type: "event";
    readonly name: "Anchored";
    readonly inputs: readonly [{
        readonly name: "rootHash";
        readonly type: "bytes32";
        readonly indexed: true;
    }, {
        readonly name: "anchorer";
        readonly type: "address";
        readonly indexed: true;
    }, {
        readonly name: "ipfsCid";
        readonly type: "bytes";
        readonly indexed: false;
    }, {
        readonly name: "blockNumber";
        readonly type: "uint256";
        readonly indexed: false;
    }, {
        readonly name: "batchSize";
        readonly type: "uint64";
        readonly indexed: false;
    }];
}];
export interface AnchorClientConfig {
    rpcUrl: string;
    contract: Address;
    privateKey: Hex;
}
export declare class AnchorClient {
    readonly publicClient: PublicClient;
    readonly walletClient: WalletClient;
    readonly contract: Address;
    readonly account: {
        address: Address;
        nonceManager?: import("viem").NonceManager | undefined;
        sign: (parameters: {
            hash: Hash;
        }) => Promise<Hex>;
        signAuthorization: (parameters: import("viem").AuthorizationRequest) => Promise<import("viem/accounts").SignAuthorizationReturnType>;
        signMessage: ({ message }: {
            message: import("viem").SignableMessage;
        }) => Promise<Hex>;
        signTransaction: <serializer extends import("viem").SerializeTransactionFn<import("viem").TransactionSerializable> = import("viem").SerializeTransactionFn<import("viem").TransactionSerializable>, transaction extends Parameters<serializer>[0] = Parameters<serializer>[0]>(transaction: transaction, options?: {
            serializer?: serializer | undefined;
        } | undefined) => Promise<Hex>;
        signTypedData: <const typedData extends import("abitype").TypedData | Record<string, unknown>, primaryType extends keyof typedData | "EIP712Domain" = keyof typedData>(parameters: import("viem").TypedDataDefinition<typedData, primaryType>) => Promise<Hex>;
        publicKey: Hex;
        source: "privateKey";
        type: "local";
    };
    constructor(cfg: AnchorClientConfig);
    /**
     * Submit a batched MST root anchor.
     * @param rootHash 32-byte hash of the canonical MST root CID.
     * @param ipfsCid raw bytes of the multibase-encoded CID.
     * @param batchSize informational record count in this batch.
     * @returns the receipt with on-chain finality.
     */
    anchorMstRoot(rootHash: Hex, ipfsCid: Uint8Array | Hex, batchSize: bigint): Promise<{
        txHash: Hash;
        blockNumber: bigint;
    }>;
    /** Read the global anchor count. */
    rootCount(): Promise<bigint>;
    /**
     * Look up the anchor record for a given root hash, or null if not anchored.
     */
    findAnchorForRoot(rootHash: Hex): Promise<{
        txAnchorerAddress: Address;
        blockNumber: bigint;
        batchSize: bigint;
        anchoredAt: bigint;
    } | null>;
}
export declare function anchorMstRoot(rpcUrl: string, contract: Address, rootCid: string, signer: {
    privateKey: Hex;
}, ipfsCidBytes?: Uint8Array, batchSize?: bigint): Promise<{
    txHash: Hash;
    blockNumber: bigint;
}>;
export declare function findAnchorForRoot(rpcUrl: string, contract: Address, rootHash: Hex): Promise<{
    blockNumber: bigint;
    anchorer: Address;
} | null>;

(ns kotoba.lang.base-l2.rpc
  "Minimal JSON-RPC-over-HTTP client for the narrow slice of Ethereum
  `eth_*` methods `l2.ts`'s `AnchorClient` actually calls (via viem's
  `PublicClient`/`WalletClient`, transported over `http()`): `eth_call`,
  `eth_sendRawTransaction`, `eth_getTransactionReceipt`,
  `eth_getTransactionCount`, `eth_gasPrice`, `eth_chainId`,
  `eth_estimateGas`. Not a general Ethereum JSON-RPC client -- just these
  seven methods.

  PURE core over an injected `ITransport`, matching the design
  established by `kotoba-lang/ipfs` (`kotoba.lang.ipfs/IHttp`) in this
  same porting wave: this namespace builds every JSON-RPC request
  envelope and parses every response; the host only moves bytes over the
  wire. The library itself performs ZERO network I/O and carries ZERO
  HTTP-client/vendor dep -- `babashka.http-client` (or any other HTTP
  lib) is the HOST's dependency for a reference adapter, not this pure
  core's. `clojure.data.json` (a small, dependency-free JSON lib -- same
  choice `kotoba.lang.ipfs` makes) is used for the JSON-RPC envelope
  itself, which is genuinely part of the pure core (unlike an HTTP
  client, `eth_*`'s wire format demands JSON encode/decode no matter what
  moves the bytes).

  Unlike `kotoba.lang.ipfs/IHttp` (3 methods: `-get`/`-post`/`-post-file`,
  because Kubo's REST-ish HTTP API needs those distinct shapes),
  JSON-RPC-over-HTTP has exactly ONE request shape -- POST a JSON body to
  a single endpoint, get a JSON body back -- so `ITransport` below is a
  single method.

  JVM-only (.clj, not .cljc) -- see the namespace docstring on
  `kotoba.lang.base-l2.l2` for why this port doesn't attempt a CLJS
  branch."
  (:require [clojure.data.json :as json]))

;; ─── capability seam -- host-injected transport ───────────────────────

(defprotocol ITransport
  "Host-injected HTTP transport. Core builds the JSON-RPC request body
  and parses the JSON-RPC response body; the host only moves bytes over
  the wire (e.g. a `babashka.http-client`-backed adapter on the JVM).
  `body` is always a JSON string (JSON-RPC 2.0's wire format is textual,
  never a binary blob), so there is no bytes<->string shim to carry
  here."
  (-post [this url body]
    "POST the JSON string `body` to `url`. => `{:status Int :body String}`."))

(defonce ^:private id-counter (atom 0))

(defn rpc-call
  "POST a JSON-RPC 2.0 request `{:method method :params params}` to
  `rpc-url` via `transport` (an `ITransport`) and return the `:result`.
  Throws ex-info on a transport-level HTTP error or a JSON-RPC `:error`
  object."
  [transport rpc-url method params]
  (let [id (swap! id-counter inc)
        body (json/write-str {:jsonrpc "2.0" :id id :method method :params params})
        resp (-post transport rpc-url body)]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info (str "[kotoba.lang.base-l2.rpc] HTTP " (:status resp) " calling " method)
                       {:status (:status resp) :body (:body resp) :method method})))
    (let [parsed (json/read-str (:body resp) :key-fn keyword)]
      (if-let [err (:error parsed)]
        (throw (ex-info (str "[kotoba.lang.base-l2.rpc] JSON-RPC error calling " method ": " (:message err))
                         {:error err :method method :params params}))
        (:result parsed)))))

(defn- hex->long ^long [^String hex]
  (Long/parseLong (if (.startsWith hex "0x") (subs hex 2) hex) 16))

(defn- ->long ^long [x]
  (cond
    (string? x) (hex->long x)
    (number? x) (long x)
    :else (throw (ex-info "expected hex string or number" {:value x}))))

(defn ->hex
  "Coerce an integral value to its `0x…` JSON-RPC quantity encoding
  (minimal, no leading zeros, `0x0` for zero)."
  ^String [v]
  (let [bi (biginteger v)]
    (if (zero? (.signum bi))
      "0x0"
      (str "0x" (.toString bi 16)))))

;; ─── the seven eth_* methods this SDK actually calls ──────────────────
;; Every method takes `transport` first (mirrors `kotoba.lang.ipfs`'s
;; `(pin-blob http api-url content)` convention).

(defn eth-call
  "`eth_call` against `to` with calldata `data` (a `0x…` hex string) at
  block tag `block` (default \"latest\"). Returns the `0x…` return data."
  ([transport rpc-url to data] (eth-call transport rpc-url to data "latest"))
  ([transport rpc-url to data block]
   (rpc-call transport rpc-url "eth_call" [{:to to :data data} block])))

(defn eth-send-raw-transaction
  "`eth_sendRawTransaction` with a `0x…` raw signed tx. Returns the `0x…`
  transaction hash."
  [transport rpc-url raw-tx]
  (rpc-call transport rpc-url "eth_sendRawTransaction" [raw-tx]))

(defn eth-get-transaction-receipt
  "`eth_getTransactionReceipt` for `tx-hash`. Returns nil until mined,
  else the receipt map (keywordized, e.g. `:status` `:blockNumber`)."
  [transport rpc-url tx-hash]
  (rpc-call transport rpc-url "eth_getTransactionReceipt" [tx-hash]))

(defn eth-get-transaction-count
  "`eth_getTransactionCount` (the nonce) for `address` at block tag
  `block` (default \"pending\", matching viem's default nonce source for
  a tx about to be sent). Returns a long."
  ([transport rpc-url address] (eth-get-transaction-count transport rpc-url address "pending"))
  ([transport rpc-url address block]
   (->long (rpc-call transport rpc-url "eth_getTransactionCount" [address block]))))

(defn eth-gas-price
  "`eth_gasPrice`. Returns a long (wei)."
  [transport rpc-url]
  (->long (rpc-call transport rpc-url "eth_gasPrice" [])))

(defn eth-chain-id
  "`eth_chainId`. Returns a long."
  [transport rpc-url]
  (->long (rpc-call transport rpc-url "eth_chainId" [])))

(defn eth-estimate-gas
  "`eth_estimateGas` for a prospective call/tx `{:from :to :data :value}`.
  Returns a long (gas units)."
  [transport rpc-url tx]
  (->long (rpc-call transport rpc-url "eth_estimateGas" [tx])))

(defn wait-for-transaction-receipt
  "Poll `eth_getTransactionReceipt` for `tx-hash` every `interval-ms`
  (default 500) until it is non-nil or `timeout-ms` (default 60000)
  elapses. Mirrors viem's `waitForTransactionReceipt`. Throws ex-info on
  timeout."
  ([transport rpc-url tx-hash] (wait-for-transaction-receipt transport rpc-url tx-hash {}))
  ([transport rpc-url tx-hash {:keys [interval-ms timeout-ms] :or {interval-ms 500 timeout-ms 60000}}]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (if-let [receipt (eth-get-transaction-receipt transport rpc-url tx-hash)]
         receipt
         (if (> (System/currentTimeMillis) deadline)
           (throw (ex-info (str "[kotoba.lang.base-l2.rpc] timed out waiting for receipt: " tx-hash)
                            {:tx-hash tx-hash :timeout-ms timeout-ms}))
           (do (Thread/sleep ^long interval-ms)
               (recur))))))))

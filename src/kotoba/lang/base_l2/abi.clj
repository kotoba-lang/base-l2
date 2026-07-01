(ns kotoba.lang.base-l2.abi
  "Narrow, hand-rolled Ethereum ABI encode/decode -- NOT a general ABI
  library. Ported from what `l2.ts` / `paymaster.ts` actually touch via
  viem's `encodeFunctionData` / `readContract` / `decodeFunctionResult`:
  simple flat argument lists for a handful of concrete function
  signatures, never nested structs or arrays.

  Supported Solidity types:
    uint8..uint256 (step 8), int8..int256 (step 8), address, bool,
    bytes1..bytes32 (fixed-size), bytes (dynamic), string.

  Explicitly OUT OF SCOPE (throws on encode/decode):
    arrays (`T[]` / `T[k]`), tuples/structs, fixed-point types.
  `paymaster.ts`'s `sponsoredWriteContract` is generic over viem's `Abi`
  type in TypeScript, but every real call in this SDK's own docstring
  example (`join(bytes32, string)`) and every ABI ANCHOR_ABI function
  falls inside this supported subset. A caller that needs an array/tuple
  argument must pre-encode it to raw calldata bytes at a lower level, or
  this namespace can be extended (the head/tail offset-encoding scheme
  below generalizes to arrays/tuples in the standard way; it just isn't
  implemented here because nothing in this SDK's own surface needs it).

  All known-answer vectors this namespace is tested against were
  generated with viem itself (`encodeAbiParameters` / `decodeFunctionResult`
  / `toFunctionSelector`) -- see test/resources/base_l2/abi-vectors.json
  and its generator note in the test namespace."
  (:require [eth-crypto.core :as eth]))

(def ^:private ^java.math.BigInteger TWO-256
  (.pow (biginteger 2) 256))

(defn- ^"[B" pad-left-32 [^"[B" b]
  (let [n (alength b)]
    (cond
      (= n 32) b
      (< n 32) (let [out (byte-array 32)]
                 (System/arraycopy b 0 out (- 32 n) n)
                 out)
      :else (java.util.Arrays/copyOfRange b (- n 32) n))))

(defn- ^"[B" word-of-biginteger
  "Encode any integer (signed or unsigned, positive or negative) as its
  32-byte two's-complement big-endian ABI word. Works uniformly for both
  uintN and intN because `.mod TWO-256` wraps negative values into their
  two's-complement representation (Java BigInteger#mod always returns a
  non-negative result for a positive modulus)."
  [v]
  (let [m (.mod (biginteger v) TWO-256)
        b (.toByteArray m)]
    (pad-left-32 b)))

(defn- ^"[B" right-pad-32-multiple
  "Right-pad `b` with zero bytes to the next multiple of 32 (0 stays 0)."
  [^"[B" b]
  (let [n (alength b)
        rem (mod n 32)]
    (if (zero? rem)
      b
      (let [out (byte-array (+ n (- 32 rem)))]
        (System/arraycopy b 0 out 0 n)
        out))))

(defn- ->bytes ^"[B" [v]
  (cond
    (bytes? v) v
    (string? v) (eth/hex->bytes v)
    :else (throw (ex-info "expected bytes or 0x-hex string" {:value v}))))

;; ─── per-type static/dynamic classification + word-level codecs ──────

(defn dynamic-type?
  "true for the two dynamic ABI types this namespace supports (`bytes`,
  `string`); everything else here is a fixed 32-byte head word."
  [type]
  (contains? #{"bytes" "string"} type))

(defn- fixed-bytes-width
  "For \"bytesN\" (N 1..32) return N, else nil."
  [type]
  (when-let [[_ n] (re-matches #"bytes(\d{1,2})" type)]
    (let [n (Long/parseLong n)]
      (when (<= 1 n 32) n))))

(defn- uint-type? [type] (boolean (re-matches #"uint(8|16|24|32|40|48|56|64|72|80|88|96|104|112|120|128|136|144|152|160|168|176|184|192|200|208|216|224|232|240|248|256)?" type)))
(defn- int-type?  [type] (boolean (re-matches #"int(8|16|24|32|40|48|56|64|72|80|88|96|104|112|120|128|136|144|152|160|168|176|184|192|200|208|216|224|232|240|248|256)?" type)))

(defn- encode-static-word
  "Encode one static-type value into its 32-byte ABI head word."
  ^"[B" [type value]
  (cond
    (uint-type? type) (word-of-biginteger (biginteger value))
    (int-type? type)  (word-of-biginteger (biginteger value))
    (= type "address") (pad-left-32 (->bytes value))
    (= type "bool") (word-of-biginteger (if value 1 0))
    (fixed-bytes-width type)
    (let [b (->bytes value)
          out (byte-array 32)]
      (System/arraycopy b 0 out 0 (alength b))
      out)
    :else (throw (ex-info (str "[kotoba.lang.base-l2.abi] unsupported ABI type: " type
                                " (arrays/tuples are out of scope -- see namespace docstring)")
                           {:type type}))))

(defn- decode-static-word
  "Decode one static-type value from its 32-byte ABI head word."
  [type ^"[B" word]
  (if-let [n (fixed-bytes-width type)]
    (str "0x" (eth/bytes->hex (java.util.Arrays/copyOfRange word 0 n)))
    (cond
      (uint-type? type) (BigInteger. 1 word)
      (int-type? type)  (BigInteger. word) ;; signed two's-complement ctor
      (= type "address")
      (eth/eip55-checksum (java.util.Arrays/copyOfRange word 12 32))
      (= type "bool") (not (every? zero? (seq word)))
      :else (throw (ex-info (str "[kotoba.lang.base-l2.abi] unsupported ABI type: " type)
                             {:type type})))))

(defn- encode-dynamic-tail
  "Encode one dynamic-type value's tail contribution: length word ++
  right-padded data."
  ^"[B" [type value]
  (let [data (case type
               "bytes"  (->bytes value)
               "string" (eth/utf8 value))
        len-word (word-of-biginteger (alength data))
        padded (right-pad-32-multiple data)
        out (byte-array (+ 32 (alength padded)))]
    (System/arraycopy len-word 0 out 0 32)
    (System/arraycopy padded 0 out 32 (alength padded))
    out))

(defn- decode-dynamic-at
  "Decode one dynamic-type value living at byte offset `offset` within
  `data` (length word followed by the data itself)."
  [type ^"[B" data ^long offset]
  (let [len (.intValueExact (BigInteger. 1 (java.util.Arrays/copyOfRange data offset (+ offset 32))))
        start (+ offset 32)
        raw (java.util.Arrays/copyOfRange data start (+ start len))]
    (case type
      "bytes" (str "0x" (eth/bytes->hex raw))
      "string" (String. raw "UTF-8"))))

;; ─── head/tail ABI parameter encode/decode (flat arg lists only) ─────

(defn encode-abi-params
  "Encode a flat argument list per Solidity ABI head/tail rules. `types`
  is a seq of type-name strings (see namespace docstring for the
  supported subset); `values` is the matching seq of Clojure values
  (bytes/hex-string for byte types, any integral for uint/int, boolean
  for bool, hex-string/bytes for address). Returns a byte array -- the
  concatenation of the fixed-size head words (or, for dynamic types, an
  offset pointer) followed by the tail (the actual dynamic-type data, in
  argument order)."
  ^"[B" [types values]
  (let [n (count types)
        head-size (* 32 n)
        tails (mapv (fn [type value]
                      (when (dynamic-type? type) (encode-dynamic-tail type value)))
                    types values)
        offsets (loop [i 0 running (long head-size) acc (transient [])]
                  (if (= i n)
                    (persistent! acc)
                    (let [tail (nth tails i)]
                      (recur (inc i)
                             (if tail (+ running (alength ^"[B" tail)) running)
                             (conj! acc running)))))
        head (mapv (fn [type value offset]
                     (if (dynamic-type? type)
                       (word-of-biginteger offset)
                       (encode-static-word type value)))
                   types values offsets)
        parts (concat head (remove nil? tails))
        total (reduce + (map #(alength ^"[B" %) parts))
        out (byte-array total)]
    (loop [off 0 ps parts]
      (if (seq ps)
        (let [^"[B" p (first ps)]
          (System/arraycopy p 0 out off (alength p))
          (recur (+ off (alength p)) (rest ps)))
        out))))

(defn decode-abi-params
  "Inverse of `encode-abi-params`: decode a flat argument list out of
  `data` (a byte array or 0x-hex string) per `types`. Returns a vector of
  decoded values (BigInteger for uint/int, EIP-55 string for address,
  boolean for bool, `0x…` hex string for bytesN/bytes, String for
  string)."
  [types data]
  (let [^"[B" data (->bytes data)]
    (into []
          (map-indexed
           (fn [i type]
             (let [word (java.util.Arrays/copyOfRange data (* i 32) (+ 32 (* i 32)))]
               (if (dynamic-type? type)
                 (let [offset (.intValueExact (BigInteger. 1 word))]
                   (decode-dynamic-at type data offset))
                 (decode-static-word type word)))))
          types)))

;; ─── function selectors / whole-call encode+decode ───────────────────

(defn function-selector
  "keccak256(signature)[0:4] as a `0x…` string, e.g.
  `(function-selector \"anchor(bytes32,bytes,uint64)\")` =>
  \"0x4698c11d\"."
  ^String [^String signature]
  (str "0x" (eth/bytes->hex (java.util.Arrays/copyOfRange (eth/keccak256 (eth/utf8 signature)) 0 4))))

(defn encode-function-call
  "`selector` (the `function name(type,type,...)` signature string) +
  ABI-encoded `args` (types/values per `encode-abi-params`), concatenated
  into full calldata as a `0x…` hex string."
  ^String [signature types values]
  (str (function-selector signature) (eth/bytes->hex (encode-abi-params types values))))

(defn decode-function-result
  "Decode a `0x…` return-data hex string per `types` -- see
  `decode-abi-params`."
  [types ^String data]
  (decode-abi-params types (eth/hex->bytes data)))

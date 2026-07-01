(ns kotoba.lang.base-l2.jvm-http-transport
  "Reference JVM host adapter for `kotoba.lang.base-l2.rpc/ITransport`,
  backed by `babashka.http-client`. Lives in the TEST tree, not `src/` --
  the pure `rpc.clj`/`l2.clj`/`paymaster.clj` core carries zero
  HTTP-client dep (`org.babashka/http-client` is a `:test`-alias-only dep
  in `deps.edn`); a real consumer (e.g. the `anchor-cron` K8s CronJob)
  supplies its own `ITransport` the exact same way. This adapter exists
  here purely to prove the injection point actually works end-to-end
  against `l2-test`'s mock JSON-RPC HTTP server (a real socket
  round-trip), rather than only ever being exercised by an in-memory
  fake."
  (:require [babashka.http-client :as http]
            [kotoba.lang.base-l2.rpc :as rpc]))

(defn make-transport
  "A `kotoba.lang.base-l2.rpc/ITransport` backed by `babashka.http-client`.
  POSTs `body` (a JSON string) to `url` with a `content-type:
  application/json` header and returns `{:status Int :body String}`."
  []
  (reify rpc/ITransport
    (-post [_ url body]
      (let [resp (http/post url {:headers {"content-type" "application/json"}
                                  :body body})]
        {:status (:status resp) :body (:body resp)}))))

# Hearth Implementation Plan

Comprehensive, test-driven, performance-driven plan for implementing all missing
Pedestal middleware and async support in hearth.alpha.

## Architecture Context

**Pipeline execution order:**
`preform (tf-pre) -> in -> tf -> op/env-op -> out -> tf-end`

- `transformer-invoke` returns `(:res end-env)` — only `:res` is observable from outside
- Pipelines are `[keyword fn]` pair vectors — keywords enable deduplication
- `:with` is mixin inheritance — left-to-right execution, last-wins dedup

**Currently implemented middleware (13):**
query-params, json-body, json-response, logging, default-content-type, cors,
not-found-handler, form-params, body-params, keyword-params, content-negotiation,
html-body, json-body-response

---

## Phase 0: Async Foundation (Core Transformer Changes)

> "We may have to build the async ability into ti-yong/transformers themselves"

This is the most architecturally significant phase. Async support must be built into
`ti-yong.alpha.root` (the execution engine) so that any pipeline step — in any stage
— can return a deferred value, and the pipeline continues asynchronously.

### 0.1 — Design: Deferred Value Protocol

**The Problem:**
`transformer-invoke` in `root.clj` is a sequential `let` chain:
```
preform -> ins-fn -> tform-fn -> op/env-op -> outs-fn -> tform-end-fn -> (:res end-env)
```
Each stage internally uses `reduce`. None of this can handle a step returning a
CompletableFuture, Promise, or core.async channel.

**Design Decision: Protocol-Based Deferred Detection**

Use a protocol `IDeferred` to detect async return values, with zero overhead for
the synchronous path. This is the approach used by Manifold and Promesa.

```clojure
;; ti-yong.alpha.async (new namespace)
(defprotocol IDeferred
  (deferred? [x])
  (chain [x f]))    ;; like .thenApply / .then
```

**JVM implementations:**
- `CompletableFuture` — native, zero-dep, best interop
- `core.async channel` — optional, via extend-protocol
- `Manifold deferred` — optional, via extend-protocol

**CLJS implementations:**
- `js/Promise` — native, universal

**Why CompletableFuture over core.async for the default JVM path:**
- CompletableFuture is in `java.util.concurrent` — zero dependencies
- Better thread pool control (ForkJoinPool vs core.async's fixed 8 threads)
- Native interop with Java async libraries (Netty, virtual threads)
- core.async dispatch thread exhaustion is a known Pedestal pitfall

**Research hints for implementing agent:**
- Study Promesa's `chain` and `let` macros: https://github.com/funcool/promesa
- Study Manifold's `chain` and `let-flow`: https://github.com/clj-commons/manifold
- Study Pedestal's `io.pedestal.interceptor.chain/go-async` flow
- Review Java 21 virtual threads (Project Loom) as a simpler alternative

### 0.2 — Implement `ti-yong.alpha.async`

**File:** `src/ti_yong/alpha/async.clj`

```clojure
(ns ti-yong.alpha.async
  (:import [java.util.concurrent CompletableFuture CompletionStage]))

(defprotocol IDeferred
  (deferred? [x] "Returns true if x is a deferred/async value")
  (then [x f] "Chain f onto x: when x resolves, call (f resolved-value)"))

(extend-protocol IDeferred
  CompletionStage
  (deferred? [_] true)
  (then [x f] (.thenApply x (reify java.util.function.Function
                               (apply [_ v] (f v)))))

  Object
  (deferred? [_] false)
  (then [_ _] (throw (ex-info "Not a deferred value" {})))

  nil
  (deferred? [_] false)
  (then [_ _] (throw (ex-info "Not a deferred value" {}))))

(defn resolved [v]
  (CompletableFuture/completedFuture v))

(defn ->deferred
  "Convert a value to a CompletableFuture if it isn't one already."
  [v]
  (if (deferred? v) v (resolved v)))
```

**File:** `src/ti_yong/alpha/async.cljs`

```clojure
(ns ti-yong.alpha.async)

(defprotocol IDeferred
  (deferred? [x])
  (then [x f]))

(extend-protocol IDeferred
  js/Promise
  (deferred? [_] true)
  (then [x f] (.then x f))

  default
  (deferred? [_] false)
  (then [_ _] (throw (js/Error. "Not a deferred value"))))

(defn resolved [v] (js/Promise.resolve v))
(defn ->deferred [v] (if (deferred? v) v (resolved v)))
```

**Tests (TDD):**
```clojure
;; test/ti_yong/alpha/async_test.clj
(deftest deferred?-test
  (is (false? (async/deferred? 42)))
  (is (false? (async/deferred? nil)))
  (is (false? (async/deferred? {:a 1})))
  (is (true? (async/deferred? (CompletableFuture/completedFuture 42)))))

(deftest then-test
  (let [cf (async/then (CompletableFuture/completedFuture 10) inc)]
    (is (= 11 (.get cf)))))

(deftest resolved-test
  (is (= 42 (.get (async/resolved 42)))))
```

**Performance target:**
- `(deferred? x)` for non-deferred values: < 5ns (protocol dispatch on Object is fast)
- `(deferred? cf)` for CompletableFuture: < 5ns
- Sync path through transformer-invoke: < 1% overhead vs current (guard is just a `satisfies?`/`instance?` check)

### 0.3 — Async-Aware Pipeline Reduce

The internal `reduce` in `ins`, `tform`, `endform`, `outs` must handle a step
returning a deferred. The pattern: if any step returns a deferred, convert the
remaining reduce into a chain of `.thenApply` calls.

**File:** `src/ti_yong/alpha/root.clj` (modify existing)

Add an `async-reduce` helper:

```clojure
(defn- async-reduce
  "Like reduce, but if f returns a deferred value, chains remaining steps."
  [f init coll]
  (loop [acc init
         remaining coll]
    (if (empty? remaining)
      acc
      (if (async/deferred? acc)
        ;; Went async — chain the rest
        (reduce (fn [d step]
                  (async/then d #(f % step)))
                acc
                remaining)
        ;; Still sync — normal reduce step
        (recur (f acc (first remaining))
               (rest remaining))))))
```

This is the critical function. For the sync path, it's a normal `loop/recur` with
no overhead beyond a single `deferred?` check per step (protocol dispatch, ~5ns).
When any step goes async, the remainder is chained via `then`.

**Modify each pipeline function:**
- `ins`: Use `async-reduce` instead of `reduce`
- `tform`: Use `async-reduce` instead of `reduce`
- `endform`: Use `async-reduce` instead of `reduce`
- `outs`: Use `async-reduce` instead of `reduce`

### 0.4 — Async-Aware `transformer-invoke`

The `let` chain in `transformer-invoke` must propagate deferred values between stages.
Replace the linear `let` with a chain that detects deferred returns.

**Strategy: `chain-stages` macro/function**

```clojure
(defn- chain-stages
  "Execute a sequence of (fn [value] -> value-or-deferred) stages.
   If any stage returns a deferred, chain remaining stages via `then`."
  [init & stages]
  (loop [v init
         remaining stages]
    (if (empty? remaining)
      v
      (if (async/deferred? v)
        ;; Gone async — chain rest
        (reduce (fn [d stage] (async/then d stage))
                v
                remaining)
        ;; Still sync
        (recur ((first remaining) v)
               (rest remaining))))))
```

Then rewrite `transformer-invoke` to use `chain-stages` for its stage transitions:

```clojure
(defn transformer-invoke [original-env & args]
  (chain-stages
    (preform original-env)

    ;; Stage: ins + tform
    (fn [env]
      (let [combined-args (concat (:args env) args)
            tf* (update env :op #(or % u/identities))
            this (:this (:params tf*))
            tf* (merge tf* this)
            ins-fn (::ins tf* identity)]
        (let [processed-args (if-not (seq (:in tf*))
                               combined-args
                               (ins-fn tf* combined-args))
              ;; Handle if ins returned a deferred
              continue (fn [proc-args]
                         (let [arg-env (assoc tf* :args proc-args)
                               tform-fn (::tform tf* identity)]
                           (if-not tform-fn arg-env (tform-fn arg-env))))]
          (if (async/deferred? processed-args)
            (async/then processed-args continue)
            (continue processed-args)))))

    ;; Stage: op/env-op -> outs
    (fn [tf-env]
      (let [tf-args (:args tf-env [])
            op (or (:op tf-env) u/identities)
            env-op (:env-op tf-env)
            res (if env-op (env-op tf-env) (apply op tf-args))
            outs-fn (::outs tf-env)]
        (let [continue (fn [r]
                         (let [out-res (if-not outs-fn r (outs-fn (assoc tf-env :args tf-args :res r) r))]
                           (assoc tf-env :args tf-args :res out-res)))]
          (if (async/deferred? res)
            (async/then res continue)
            (continue res)))))

    ;; Stage: tf-end -> extract :res
    (fn [res-env]
      (let [tform-end-fn (::tform-end res-env identity)
            end-env (if-not tform-end-fn res-env (tform-end-fn res-env))]
        (if (async/deferred? end-env)
          (async/then end-env :res)
          (:res end-env))))))
```

**Key design principle:** When `transformer-invoke` returns a deferred, the *caller*
(i.e., the Ring adapter) is responsible for handling it. For the sync path, callers
see zero change. This is backwards-compatible.

**Tests (TDD):**
```clojure
(deftest sync-path-unchanged
  (let [adder (assoc transformer :op +)]
    (is (= 6 (adder 1 2 3)))))

(deftest async-op
  (let [async-adder (assoc transformer
                      :env-op (fn [env]
                                (CompletableFuture/supplyAsync
                                  (reify java.util.function.Supplier
                                    (get [_] (apply + (:args env)))))))]
    (let [result (async-adder 1 2 3)]
      (is (async/deferred? result))
      (is (= 6 (.get result))))))

(deftest async-tf-step
  (let [t (-> transformer
              (assoc :op +)
              (update :tf conj
                ::slow-tf
                (fn [env]
                  (CompletableFuture/supplyAsync
                    (reify java.util.function.Supplier
                      (get [_] (update env :args (partial mapv inc))))))))]
    (let [result (t 1 2 3)]
      (is (async/deferred? result))
      (is (= 9 (.get result))))))  ;; (+ 2 3 4) = 9

(deftest async-middleware-composition
  (let [delay-mw (-> transformer
                     (update :id conj ::delay)
                     (update :tf conj
                       ::delay
                       (fn [env]
                         (let [cf (CompletableFuture.)]
                           (future (.complete cf (assoc env :delayed? true)))
                           cf))))
        composed (-> transformer
                     (assoc :op +)
                     (update :with conj delay-mw))]
    (let [result (composed 1 2)]
      (is (async/deferred? result))
      (is (= 3 (.get result))))))
```

**Performance targets:**
- Sync path: < 2% overhead vs current (just a `deferred?` check per stage)
- Async path: < 10us overhead per stage transition (CompletableFuture.thenApply)
- Benchmark: 100k sync invocations should take ~same time as current

### 0.5 — Async Ring Adapter

**File:** `src/hearth/alpha/adapter/ring.clj` (modify existing)

Ring 1.6+ supports async handlers via 3-arity: `(fn [request respond raise])`.
Jetty's async adapter uses Servlet 3.1 async and `AsyncContext`.

```clojure
(defn service->handler
  "Convert a service transformer into a Ring handler function.
   Returns a fn that supports both sync (1-arity) and async (3-arity) Ring."
  [svc]
  (fn
    ;; Sync Ring handler
    ([ring-request]
     (let [resp (svc/response-for svc
                                   (:request-method ring-request)
                                   (:uri ring-request)
                                   (dissoc ring-request :request-method :uri))]
       (if (async/deferred? resp)
         (.get ^CompletableFuture (async/->deferred resp))  ;; Block for sync
         (normalize-response resp))))
    ;; Async Ring handler
    ([ring-request respond raise]
     (try
       (let [resp (svc/response-for svc
                                     (:request-method ring-request)
                                     (:uri ring-request)
                                     (dissoc ring-request :request-method :uri))]
         (if (async/deferred? resp)
           (-> (async/->deferred resp)
               (async/then (fn [r] (respond (normalize-response r))))
               ;; Handle exceptions
               (.exceptionally
                 (reify java.util.function.Function
                   (apply [_ ex] (raise ex) nil))))
           (respond (normalize-response resp))))
       (catch Throwable t
         (raise t))))))
```

**Modify `start` to enable Jetty async:**

```clojure
(defn start [server-cfg]
  (let [run-jetty (requiring-resolve 'ring.adapter.jetty/run-jetty)]
    (run-jetty (:handler server-cfg)
               {:port (:port server-cfg)
                :join? (:join? server-cfg)
                :async? true})))  ;; Enable async support
```

**Research hints:**
- Ring async spec: https://github.com/ring-clojure/ring/wiki/Concepts#async-handlers
- `ring.adapter.jetty` `:async?` option uses Servlet 3.1 startAsync()
- Jetty's async timeout default is 30s — make configurable via `::hearth.alpha/async-timeout`

**Tests:**
```clojure
(deftest async-handler-responds
  (let [svc (svc/service {:routes [["/slow" :get
                                    (fn [_]
                                      (let [cf (CompletableFuture.)]
                                        (future
                                          (Thread/sleep 10)
                                          (.complete cf {:status 200 :body "done"}))
                                        cf))]]})
        handler (ring/service->handler svc)
        response (promise)]
    (handler {:request-method :get :uri "/slow"}
             #(deliver response %)
             #(deliver response {:error %}))
    (is (= 200 (:status (deref response 1000 :timeout))))))
```

**Performance targets:**
- Async handler overhead vs sync: < 5us per request (CompletableFuture allocation + dispatch)
- Under load: async should allow higher concurrency (more in-flight requests per thread)

### 0.6 — Streaming Response Bodies

Support for streaming response bodies — when `:body` is a core.async channel or
a function, stream chunks to the client without buffering the full response.

**File:** `src/hearth/alpha/streaming.clj` (new)

```clojure
(ns hearth.alpha.streaming
  (:require [clojure.core.async :as async]))

(defprotocol IStreamableBody
  (stream-body [body output-stream]))

;; Channel body: each value from channel is written as a chunk
(extend-protocol IStreamableBody
  clojure.core.async.impl.channels.ManyToManyChannel
  (stream-body [ch output-stream]
    (async/go-loop []
      (when-let [chunk (async/<! ch)]
        (let [bytes (if (string? chunk) (.getBytes ^String chunk "UTF-8") chunk)]
          (.write output-stream bytes)
          (.flush output-stream)
          (recur)))
      (.close output-stream))))
```

**Research hints:**
- Pedestal's `WriteableBody` and `WriteableBodyAsync` protocols
- Ring's streaming body support (InputStream, ISeq)
- Jetty's `HttpOutput` for NIO writes with ByteBuffer

**Tests:**
```clojure
(deftest channel-body-streams-chunks
  (let [ch (async/chan 10)
        baos (java.io.ByteArrayOutputStream.)]
    (async/>!! ch "Hello, ")
    (async/>!! ch "World!")
    (async/close! ch)
    (let [done (streaming/stream-body ch baos)]
      (async/<!! done)
      (is (= "Hello, World!" (.toString baos "UTF-8"))))))
```

---

## Phase 1: Pedestal Default Interceptors

These are applied by `http/default-interceptors` in stock Pedestal. They should be
automatically included in hearth's default service setup.

### 1.1 — Secure Headers (`secure-headers`)

Adds security headers to every response.

**Pedestal default headers:**
- `X-Frame-Options: DENY`
- `X-Content-Type-Options: nosniff`
- `X-XSS-Protection: 1; mode=block`
- `Strict-Transport-Security: max-age=31536000; includeSubdomains`
- `X-Download-Options: noopen`
- `X-Permitted-Cross-Domain-Policies: none`
- `Content-Security-Policy: object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;`

**File:** `src/hearth/alpha/middleware.clj` (add to existing)

```clojure
(def ^:private default-secure-headers
  {"X-Frame-Options" "DENY"
   "X-Content-Type-Options" "nosniff"
   "X-XSS-Protection" "1; mode=block"
   "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
   "X-Download-Options" "noopen"
   "X-Permitted-Cross-Domain-Policies" "none"
   "Content-Security-Policy" "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"})

(defn secure-headers
  "Middleware that adds security headers to responses.
   Pass a map to override defaults, or no args for Pedestal defaults."
  ([] (secure-headers {}))
  ([overrides]
   (let [headers (merge default-secure-headers overrides)]
     (-> t/transformer
         (update :id conj ::secure-headers)
         (update :tf-end conj
                 ::secure-headers
                 (fn [env]
                   (let [res (:res env)]
                     (if (map? res)
                       (assoc env :res (update res :headers merge headers))
                       env))))))))
```

**Tests:**
```clojure
(deftest secure-headers-adds-defaults
  (let [svc (svc/service {:routes [["/test" :get (fn [_] {:status 200 :body "ok"})]]
                           :with [(mw/secure-headers)]})
        resp (svc/response-for svc :get "/test")]
    (is (= "DENY" (get-in resp [:headers "X-Frame-Options"])))
    (is (= "nosniff" (get-in resp [:headers "X-Content-Type-Options"])))))

(deftest secure-headers-overrides
  (let [svc (svc/service {:routes [["/test" :get (fn [_] {:status 200 :body "ok"})]]
                           :with [(mw/secure-headers {"X-Frame-Options" "SAMEORIGIN"})]})
        resp (svc/response-for svc :get "/test")]
    (is (= "SAMEORIGIN" (get-in resp [:headers "X-Frame-Options"])))))
```

**Performance target:** < 200ns overhead per request (merge of ~7 static headers).

**Research hints:**
- OWASP Secure Headers Project: https://owasp.org/www-project-secure-headers/
- Pedestal source: `io.pedestal.http.secure-headers`

### 1.2 — Method Override (`method-param`)

Allows HTTP method override via `_method` query param. Needed for HTML forms
that can only POST.

```clojure
(defn method-param
  "Middleware that overrides :request-method from a query/form param.
   Default param name is '_method'. Common for HTML forms."
  ([] (method-param "_method"))
  ([param-name]
   (-> t/transformer
       (update :id conj ::method-param)
       (update :tf conj
               ::method-param
               (fn [env]
                 (if (= :post (:request-method env))
                   (let [override (or (get (:query-params env) param-name)
                                      (get (:form-params env) param-name))]
                     (if override
                       (assoc env :request-method (keyword (str/lower-case override)))
                       env))
                   env))))))
```

**Tests:**
```clojure
(deftest method-param-overrides-post
  (let [svc (svc/service {:routes [["/resource" :put (fn [_] {:status 200 :body "updated"})]]
                           :with [(mw/query-params) (mw/method-param)]})
        resp (svc/response-for svc :post "/resource?_method=PUT")]
    (is (= 200 (:status resp)))))

(deftest method-param-ignores-get
  ;; Only POST requests should be overrideable
  (let [svc (svc/service {:routes [["/resource" :get (fn [_] {:status 200 :body "ok"})]]
                           :with [(mw/query-params) (mw/method-param)]})
        resp (svc/response-for svc :get "/resource?_method=DELETE")]
    (is (= 200 (:status resp)))))
```

**Performance target:** < 50ns for non-POST requests (single keyword comparison).

### 1.3 — Path Params URL Decoder

Currently `path-params-values` stores raw URL-encoded values. Need to URL-decode them.

```clojure
(def path-params-decoder
  "Middleware that URL-decodes path parameter values."
  (-> t/transformer
      (update :id conj ::path-params-decoder)
      (update :tf conj
              ::path-params-decoder
              (fn [env]
                (if-let [params (:path-params-values env)]
                  (assoc env :path-params-values
                         (reduce-kv (fn [m k v]
                                      (assoc m k (java.net.URLDecoder/decode v "UTF-8")))
                                    {} params))
                  env)))))
```

**Tests:**
```clojure
(deftest decodes-path-params
  (let [svc (svc/service {:routes [["/items/:id" :get
                                    (fn [req]
                                      {:status 200
                                       :body (get-in req [:path-params-values "id"])})]]
                           :with [(mw/path-params-decoder)]})
        resp (svc/response-for svc :get "/items/hello%20world")]
    (is (= "hello world" (:body resp)))))
```

**Performance target:** < 100ns per param (URLDecoder is fast for non-encoded strings).

---

## Phase 2: Session & Security

### 2.1 — Cookie Parsing/Setting (`cookies`)

**Pedestal equivalent:** Uses Ring's `wrap-cookies` under the hood.

```clojure
(def cookies
  "Middleware that parses Cookie header into :cookies map on request,
   and writes Set-Cookie headers from :cookies on response."
  (-> t/transformer
      (update :id conj ::cookies)
      (update :tf conj
              ::cookies-parse
              (fn [env]
                (let [cookie-header (get-in env [:headers "cookie"])]
                  (if cookie-header
                    (assoc env :cookies (parse-cookie-header cookie-header))
                    env))))
      (update :tf-end conj
              ::cookies-write
              (fn [env]
                (let [res (:res env)
                      cookies-to-set (:cookies res)]
                  (if (and (map? res) cookies-to-set)
                    (assoc env :res
                           (-> res
                               (dissoc :cookies)
                               (assoc-in [:headers "Set-Cookie"]
                                         (serialize-set-cookies cookies-to-set))))
                    env))))))
```

**Research hints:**
- RFC 6265 cookie parsing/serialization
- Ring `ring.middleware.cookies` source for format reference
- Cookie attributes: Path, Domain, Secure, HttpOnly, SameSite, Max-Age, Expires

**Tests:**
```clojure
(deftest parses-cookies
  (let [svc (svc/service {:routes [["/test" :get
                                    (fn [req] {:status 200 :body (pr-str (:cookies req))})]]
                           :with [(mw/cookies)]})
        resp (svc/response-for svc :get "/test"
               {:headers {"cookie" "session=abc123; theme=dark"}})]
    (is (str/includes? (:body resp) "session"))
    (is (str/includes? (:body resp) "abc123"))))

(deftest sets-cookies
  (let [svc (svc/service {:routes [["/login" :get
                                    (fn [_] {:status 200 :body "ok"
                                             :cookies {"session" {:value "xyz" :path "/"}}})]]
                           :with [(mw/cookies)]})
        resp (svc/response-for svc :get "/login")]
    (is (str/includes? (get-in resp [:headers "Set-Cookie"]) "session=xyz"))))
```

**Performance target:** < 500ns for typical 2-3 cookie header parsing.

### 2.2 — Session Management (`session`)

Server-side session support with pluggable stores.

```clojure
(defprotocol ISessionStore
  (read-session [store key])
  (write-session [store key data])
  (delete-session [store key]))

(defn memory-store
  "In-memory session store backed by an atom. For development only."
  []
  (let [sessions (atom {})]
    (reify ISessionStore
      (read-session [_ key] (get @sessions key))
      (write-session [_ key data] (swap! sessions assoc key data) key)
      (delete-session [_ key] (swap! sessions dissoc key)))))

(defn session
  "Middleware that loads/saves session data from a store.
   Options:
     :store     - ISessionStore impl (default: memory-store)
     :cookie-name - session cookie name (default: 'hearth-session')
     :cookie-attrs - map of cookie attributes"
  ([] (session {}))
  ([{:keys [store cookie-name cookie-attrs]
     :or {store (memory-store) cookie-name "hearth-session"}}]
   (-> t/transformer
       (update :id conj ::session)
       (update :tf conj
               ::session-load
               (fn [env]
                 (let [session-id (get-in env [:cookies cookie-name :value])
                       session-data (when session-id (read-session store session-id))]
                   (assoc env :session (or session-data {})))))
       (update :tf-end conj
               ::session-save
               (fn [env]
                 ;; If :session was modified, write it
                 (let [session (:session env)
                       session-id (or (get-in env [:cookies cookie-name :value])
                                      (str (java.util.UUID/randomUUID)))
                       _ (write-session store session-id session)]
                   (assoc-in env [:res :cookies cookie-name]
                             (merge {:value session-id} cookie-attrs))))))))
```

**Depends on:** Phase 2.1 (cookies middleware)

**Research hints:**
- Ring `ring.middleware.session` for API design
- Pedestal uses Ring's session middleware directly
- Consider cookie-store (encrypted, stateless) as a second store impl
- ring.middleware.session-cookie uses AES for encryption

**Tests:**
```clojure
(deftest session-roundtrip
  (let [store (mw/memory-store)
        svc (svc/service
              {:routes [["/set" :get (fn [req]
                                       {:status 200 :body "ok"
                                        :session (assoc (:session req) :user "alice")})]
                         ["/get" :get (fn [req]
                                       {:status 200 :body (get-in req [:session :user] "anon")})]]
               :with [(mw/cookies) (mw/session {:store store})]})]
    ;; First request sets session
    ;; Second request with same cookie reads it
    ))
```

**Performance target:** < 1us for memory-store read/write.

### 2.3 — CSRF Protection (`csrf`)

Anti-forgery token middleware.

```clojure
(defn csrf
  "Middleware that validates anti-forgery tokens on state-changing requests.
   Options:
     :read-token  - fn to extract token from request (default: form param + header)
     :cookie-name - name of cookie holding the token
     :error-response - response map for failed validation"
  ([] (csrf {}))
  ([{:keys [read-token error-response]
     :or {read-token (fn [env]
                       (or (get-in env [:form-params "__anti-forgery-token"])
                           (get-in env [:headers "x-csrf-token"])))
          error-response {:status 403 :body "Forbidden - CSRF token invalid"}}}]
   (-> t/transformer
       (update :id conj ::csrf)
       (update :tf conj
               ::csrf
               (fn [env]
                 (let [method (:request-method env)]
                   (if (#{:get :head :options} method)
                     ;; Safe methods: generate and attach token
                     (let [token (or (:csrf-token (:session env))
                                     (str (java.util.UUID/randomUUID)))]
                       (-> env
                           (assoc :csrf-token token)
                           (assoc-in [:session :csrf-token] token)))
                     ;; Unsafe methods: validate token
                     (let [expected (get-in env [:session :csrf-token])
                           actual (read-token env)]
                       (if (and expected actual (= expected actual))
                         env
                         (assoc env :res error-response))))))))))
```

**Depends on:** Phase 2.1 (cookies), Phase 2.2 (session)

**Research hints:**
- OWASP CSRF prevention: https://owasp.org/www-community/attacks/csrf
- Ring `ring-anti-forgery` for API design
- Double-submit cookie pattern as alternative to session-based
- Pedestal wraps Ring's anti-forgery directly

**Tests:**
```clojure
(deftest csrf-blocks-without-token
  (let [svc (make-csrf-svc)]
    (let [resp (svc/response-for svc :post "/submit")]
      (is (= 403 (:status resp))))))

(deftest csrf-allows-with-valid-token
  ;; GET to obtain token, then POST with it
  )

(deftest csrf-ignores-safe-methods
  (let [svc (make-csrf-svc)]
    (is (= 200 (:status (svc/response-for svc :get "/page"))))))
```

**Performance target:** < 200ns for safe method check, < 500ns for token validation.

---

## Phase 3: Request Processing

### 3.1 — Multipart Params (`multipart-params`)

Parse multipart/form-data uploads.

```clojure
(defn multipart-params
  "Middleware that parses multipart/form-data request bodies.
   Options:
     :store - fn to handle uploaded files (default: temp-file-store)"
  ([] (multipart-params {}))
  ([{:keys [store] :or {store temp-file-store}}]
   ;; Implementation uses javax.servlet multipart parsing or manual boundary parsing
   ))
```

**Research hints:**
- Ring `ring.middleware.multipart-params` source
- Apache Commons FileUpload for reference
- Jakarta Servlet API `Part` interface for direct Jetty integration
- Consider using Jetty's built-in multipart parsing via `request.getParts()`
- For non-servlet path: parse MIME boundary manually (RFC 2046)

**Tests:**
```clojure
(deftest parses-multipart-upload
  ;; Construct a multipart body with boundary
  ;; Verify :multipart-params contains file info
  ;; Verify file contents are accessible
  )

(deftest handles-multiple-files
  ;; Upload 2+ files in one request
  )

(deftest respects-size-limits
  ;; Exceed max size, expect 413
  )
```

**Performance target:** < 10us for small form fields, file writes bounded by I/O.

### 3.2 — Nested Params (`nested-params`)

Support Rails-style nested params: `user[name]=Alice&user[age]=30` -> `{:user {:name "Alice" :age "30"}}`.

```clojure
(def nested-params
  "Middleware that nests flat params with bracket notation into nested maps."
  (-> t/transformer
      (update :id conj ::nested-params)
      (update :tf conj
              ::nested-params
              (fn [env]
                (cond-> env
                  (:query-params env) (update :query-params nest-params)
                  (:form-params env) (update :form-params nest-params)
                  (:body-params env) (update :body-params nest-params))))))
```

Where `nest-params` transforms `{"user[name]" "Alice"}` to `{"user" {"name" "Alice"}}`.

**Research hints:**
- Ring `ring.middleware.nested-params`
- Rack/Rails nested param convention
- Recursive bracket parsing: split on `[` and `]`

**Tests:**
```clojure
(deftest nests-bracket-params
  (is (= {"user" {"name" "Alice" "age" "30"}}
         (nest-params {"user[name]" "Alice" "user[age]" "30"}))))

(deftest nests-deeply
  (is (= {"a" {"b" {"c" "1"}}}
         (nest-params {"a[b][c]" "1"}))))

(deftest handles-arrays
  (is (= {"ids" ["1" "2" "3"]}
         (nest-params {"ids[]" ["1" "2" "3"]}))))
```

**Performance target:** < 200ns for typical 5-param nesting.

### 3.3 — HEAD Request Support (`head`)

Automatically handle HEAD requests by running GET handler but stripping the body.

```clojure
(def head-method
  "Middleware that converts HEAD requests to GET, then strips the response body."
  (-> t/transformer
      (update :id conj ::head-method)
      (update :tf conj
              ::head-method
              (fn [env]
                (if (= :head (:request-method env))
                  (assoc env :request-method :get ::was-head? true)
                  env)))
      (update :tf-end conj
              ::head-method-strip
              (fn [env]
                (if (::was-head? env)
                  (let [res (:res env)]
                    (assoc env :res (assoc res :body nil)))
                  env)))))
```

**Tests:**
```clojure
(deftest head-returns-headers-no-body
  (let [svc (svc/service {:routes [["/test" :get (fn [_] {:status 200 :body "hello"
                                                           :headers {"Content-Length" "5"}})]]
                           :with [(mw/head-method)]})
        resp (svc/response-for svc :head "/test")]
    (is (= 200 (:status resp)))
    (is (nil? (:body resp)))
    (is (= "5" (get-in resp [:headers "Content-Length"])))))
```

**Performance target:** < 20ns overhead (two keyword comparisons).

### 3.4 — Not-Modified Support (`not-modified`)

Support conditional GET with ETag/If-None-Match and Last-Modified/If-Modified-Since.

```clojure
(def not-modified
  "Middleware that returns 304 Not Modified when appropriate."
  (-> t/transformer
      (update :id conj ::not-modified)
      (update :tf-end conj
              ::not-modified
              (fn [env]
                (let [res (:res env)
                      req-etag (get-in env [:headers "if-none-match"])
                      res-etag (get-in res [:headers "ETag"])
                      req-modified (get-in env [:headers "if-modified-since"])
                      res-modified (get-in res [:headers "Last-Modified"])]
                  (if (and (= 200 (:status res))
                           (or (and req-etag res-etag (= req-etag res-etag))
                               (and req-modified res-modified
                                    (not (modified-since? req-modified res-modified)))))
                    (assoc env :res {:status 304 :headers (select-keys (:headers res) ["ETag" "Last-Modified"]) :body nil})
                    env))))))
```

**Research hints:**
- RFC 7232 (Conditional Requests)
- Pedestal `io.pedestal.http.ring-middlewares/not-modified`
- Ring `ring.middleware.not-modified`

**Tests:**
```clojure
(deftest etag-match-returns-304
  ;; First request: get response with ETag
  ;; Second request: send If-None-Match with same ETag
  ;; Expect 304
  )

(deftest etag-mismatch-returns-200
  ;; Send If-None-Match with different ETag
  ;; Expect 200 with full body
  )
```

**Performance target:** < 100ns (string comparisons).

---

## Phase 4: Static Resources

### 4.1 — Classpath Resources (`resource`)

Serve files from the classpath (e.g., from a JAR).

```clojure
(defn resource
  "Middleware that serves static files from the classpath.
   Options:
     :prefix    - classpath prefix (default: 'public')
     :mime-types - additional MIME type mappings"
  [{:keys [prefix mime-types] :or {prefix "public"}}]
  (-> t/transformer
      (update :id conj ::resource)
      (update :tf conj
              ::resource
              (fn [env]
                (let [path (str prefix (:uri env))
                      resource (io/resource path)]
                  (if (and resource (not (:res env)))
                    (assoc env :res {:status 200
                                     :headers {"Content-Type" (mime-type-for path mime-types)}
                                     :body (io/input-stream resource)})
                    env))))))
```

**Research hints:**
- Pedestal `io.pedestal.http.ring-middlewares/resource`
- Ring `ring.middleware.resource` and `ring.util.response/resource-response`
- MIME type detection: `java.net.URLConnection/getFileNameMap` or custom mapping
- Consider `Last-Modified` / `ETag` headers for caching

**Tests:**
```clojure
(deftest serves-classpath-resource
  ;; Put a test file on test classpath
  ;; Verify it's served with correct MIME type
  )

(deftest returns-404-for-missing
  ;; Request non-existent resource
  ;; Middleware should pass through (no :res set)
  )
```

**Performance target:** < 5us for resource lookup (classpath scan is cached by JVM).

### 4.2 — File System Resources (`file`)

Serve files from a directory on the filesystem.

```clojure
(defn file
  "Middleware that serves static files from a filesystem directory.
   Options:
     :root - base directory path"
  [{:keys [root]}]
  ;; Similar to resource but uses java.io.File
  )
```

**Research hints:**
- Pedestal `io.pedestal.http.ring-middlewares/file`
- Ring `ring.middleware.file`
- Security: path traversal prevention (canonicalize + check prefix)
- Consider using Jetty's DefaultServlet for production static file serving

**Tests:**
```clojure
(deftest serves-filesystem-file
  ;; Create temp dir with test file
  ;; Verify serving works
  )

(deftest prevents-path-traversal
  ;; Request /../../../etc/passwd
  ;; Verify 403 or 404
  )
```

### 4.3 — Fast Resource Serving (`fast-resource`)

Optimized resource serving with caching and NIO.

**Research hints:**
- Pedestal `io.pedestal.http.ring-middlewares/fast-resource`
- Memory-mapped files via `java.nio.MappedByteBuffer`
- ETag generation from file hash or modification time
- Response caching: Cache-Control, Expires headers

---

## Phase 5: Serialization

### 5.1 — Transit JSON (`transit-json-body`)

```clojure
(defn transit-json-body
  "Middleware that parses Transit+JSON request bodies and serializes Transit+JSON responses."
  []
  ;; Requires com.cognitect/transit-clj
  )
```

**Research hints:**
- Transit format: https://github.com/cognitect/transit-format
- `cognitect/transit-clj` for JVM implementation
- Pedestal `io.pedestal.http.body-params` Transit support
- Content-Type: `application/transit+json`

### 5.2 — Transit MessagePack (`transit-msgpack-body`)

Same as above but for Transit+MessagePack binary format.

**Research hints:**
- Content-Type: `application/transit+msgpack`
- More compact than Transit+JSON, good for internal service communication
- Same API as Transit+JSON, just different format option

**Tests for both 5.1 and 5.2:**
```clojure
(deftest transit-json-roundtrip
  ;; Send Transit+JSON body
  ;; Verify parsed into Clojure data
  ;; Verify response serialized as Transit+JSON when Accept matches
  )
```

**Performance target:** Transit serialization itself is fast (~2-5x faster than JSON
for complex data). Middleware overhead should be < 500ns.

---

## Phase 6: Real-time

### 6.1 — Server-Sent Events (`sse`)

**File:** `src/hearth/alpha/sse.clj` (new)

SSE requires async support (Phase 0) because the response body is a long-lived stream.

```clojure
(ns hearth.alpha.sse
  (:require [clojure.core.async :as async]
            [ti-yong.alpha.async :as ta]))

(defn event-stream
  "Create an SSE handler that calls stream-ready-fn with an event channel.
   Events put on the channel are sent to the client as SSE events.
   Options:
     :heartbeat-ms - heartbeat interval (default: 10000)
     :on-close     - callback when client disconnects"
  [stream-ready-fn & [{:keys [heartbeat-ms on-close]
                        :or {heartbeat-ms 10000}}]]
  (fn [request]
    (let [event-ch (async/chan 32)
          body-ch (async/chan 32)]
      ;; Format SSE events and write to body channel
      (async/go-loop []
        (let [[v port] (async/alts! [event-ch (async/timeout heartbeat-ms)])]
          (cond
            ;; Event channel closed
            (and (nil? v) (= port event-ch))
            (do (async/close! body-ch)
                (when on-close (on-close)))

            ;; Heartbeat timeout
            (= port (async/timeout heartbeat-ms))
            (do (async/>! body-ch ":\n\n")  ;; SSE comment = heartbeat
                (recur))

            ;; Normal event
            :else
            (do (async/>! body-ch (format-sse-event v))
                (recur)))))
      ;; Call user's stream-ready function
      (stream-ready-fn event-ch request)
      ;; Return SSE response with channel body
      {:status 200
       :headers {"Content-Type" "text/event-stream"
                 "Cache-Control" "no-cache"
                 "Connection" "keep-alive"}
       :body body-ch})))

(defn format-sse-event
  "Format an event map as SSE text.
   Event map keys: :data (required), :event (optional), :id (optional), :retry (optional)"
  [event]
  (let [event (if (map? event) event {:data event})]
    (str
     (when-let [id (:id event)] (str "id: " id "\n"))
     (when-let [evt (:event event)] (str "event: " evt "\n"))
     (when-let [retry (:retry event)] (str "retry: " retry "\n"))
     "data: " (str (:data event)) "\n\n")))
```

**Depends on:** Phase 0 (async), Phase 0.6 (streaming response bodies)

**Research hints:**
- SSE spec: https://html.spec.whatwg.org/multipage/server-sent-events.html
- Pedestal `io.pedestal.http.sse` source
- Event format: `data: ...\n\n` with optional `event:`, `id:`, `retry:` fields
- Heartbeat: SSE comment line `:\n\n` keeps connection alive
- Client reconnection: `Last-Event-ID` header on reconnect

**Tests:**
```clojure
(deftest format-sse-event-test
  (is (= "data: hello\n\n"
         (sse/format-sse-event "hello")))
  (is (= "event: update\ndata: {\"x\":1}\n\n"
         (sse/format-sse-event {:event "update" :data "{\"x\":1}"})))
  (is (= "id: 42\ndata: test\n\n"
         (sse/format-sse-event {:id 42 :data "test"}))))

(deftest sse-handler-sends-events
  ;; Create SSE handler
  ;; Invoke it, collect events from body channel
  ;; Verify SSE format
  )
```

**Performance target:** < 1us per event formatting. Heartbeat overhead negligible.

### 6.2 — WebSocket Support

WebSocket requires direct Jetty integration (not via Ring/Servlet).

**Research hints:**
- Jetty 11 WebSocket API: `JettyWebSocketCreator`
- Ring doesn't support WebSocket — must use Jetty directly
- Consider using `ring-jetty-websocket-adapter` or implementing from scratch
- Pedestal doesn't include WebSocket out of the box
- Alternative: use a separate library (e.g., `http-kit` which has built-in WS)

**File:** `src/hearth/alpha/websocket.clj` (new)

```clojure
(defn ws-handler
  "Create a WebSocket handler.
   Options:
     :on-connect - (fn [session])
     :on-message - (fn [session message])
     :on-close   - (fn [session status-code reason])
     :on-error   - (fn [session error])"
  [{:keys [on-connect on-message on-close on-error]}]
  ;; Returns a Jetty WebSocketCreator
  )
```

This is the most complex phase and may require changes to the Jetty adapter
to support WebSocket upgrade alongside normal HTTP.

---

## Phase 7: Default Interceptor Stack

Once all phases are complete, assemble the default middleware stack that mirrors
Pedestal's `http/default-interceptors`:

```clojure
(defn default-middleware
  "Returns the default middleware stack, equivalent to Pedestal's default-interceptors.
   Applied automatically by create-server unless :raw? true."
  [service-map]
  [(mw/secure-headers)
   (mw/not-found-handler "Not Found")
   (mw/query-params)
   (mw/method-param)
   (mw/path-params-decoder)
   (mw/head-method)
   (mw/not-modified)])
```

---

## Implementation Order & Dependencies

```
Phase 0: Async Foundation
  0.1 Design         (no deps)
  0.2 async ns       (no deps)
  0.3 async-reduce   (depends on 0.2)
  0.4 async invoke   (depends on 0.3)
  0.5 async adapter  (depends on 0.4)
  0.6 streaming      (depends on 0.4)

Phase 1: Pedestal Defaults (can start in parallel with Phase 0 for sync-only parts)
  1.1 secure-headers (no deps)
  1.2 method-param   (no deps)
  1.3 path-params    (no deps)

Phase 2: Session & Security
  2.1 cookies        (no deps)
  2.2 session        (depends on 2.1)
  2.3 csrf           (depends on 2.1, 2.2)

Phase 3: Request Processing
  3.1 multipart      (no deps)
  3.2 nested-params  (no deps)
  3.3 head           (no deps)
  3.4 not-modified   (no deps)

Phase 4: Static Resources
  4.1 resource       (no deps)
  4.2 file           (no deps)
  4.3 fast-resource  (depends on 4.1)

Phase 5: Serialization
  5.1 transit-json   (no deps)
  5.2 transit-msgpack (no deps)

Phase 6: Real-time
  6.1 SSE            (depends on Phase 0)
  6.2 WebSocket      (depends on Phase 0)

Phase 7: Default Stack (depends on Phase 1, 3.3, 3.4)
```

---

## Performance Testing Strategy

For each middleware:

1. **Micro-benchmark** — Measure overhead of the middleware in isolation:
   ```clojure
   (let [mw-svc (svc/service {:routes [["/test" :get handler]] :with [middleware]})
         no-mw  (svc/service {:routes [["/test" :get handler]]})]
     (criterium/bench (svc/response-for mw-svc :get "/test"))
     (criterium/bench (svc/response-for no-mw :get "/test")))
   ```

2. **Stack benchmark** — Measure with full default stack (Phase 7).

3. **Load benchmark** — Use wrk (existing `bench/run_bench.sh`) to measure
   throughput/latency under load after each phase.

4. **Comparison** — After each phase, re-run the Pedestal vs hearth benchmarks
   to verify we maintain the ~21-26% throughput advantage.

**Performance regression gate:** If any phase causes > 5% throughput regression
in the load benchmark, investigate and optimize before proceeding.

---

## Testing Strategy

For each middleware:

1. **Unit tests** — Test the middleware transformer in isolation using `svc/response-for`
2. **Integration tests** — Test middleware composed with others via `:with`
3. **Edge cases** — Nil inputs, empty strings, malformed data, large payloads
4. **Security tests** — Path traversal, injection, CSRF bypass attempts
5. **Async tests** — Verify middleware works with both sync and async handlers (after Phase 0)

**Test naming convention:**
```
test/hearth/alpha/middleware/<name>_test.clj
```

**Run all tests:** `clojure -M:clj-test`

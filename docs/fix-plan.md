# Hearth Fix Plan — Post-Implementation Review

Fixes for issues identified in the deep assessment of the `impl-plan.md` implementation
on branch `claude/implement-hearth-alpha-y1tTv`.

**Current state:** 189 tests, 554 assertions, 0 failures. All 7 phases implemented.

---

## Issue 1: Set-Cookie Header Comma-Joining (BUG)

**Severity:** High — breaks multi-cookie responses in real browsers

**Problem:**
Both `cookies` middleware (line 616) and `session` middleware (line 678) join
multiple Set-Cookie values with `", "`:
```clojure
(str/join ", " set-cookie-headers)    ;; cookies middleware
(str existing ", " set-cookie-str)    ;; session middleware
```

RFC 6265 §3 explicitly states that Set-Cookie is the **only** HTTP header that
cannot be folded into a single comma-separated value. Each cookie must be its own
`Set-Cookie` header. Ring and Jetty handle this correctly when `Set-Cookie` is a
**vector** of strings rather than a single joined string.

**Fix:**

In `serialize-set-cookies`, the return is already a vector — the problem is the
callers join it into a single string.

**File:** `src/hearth/alpha/middleware.clj`

1. **cookies middleware** (around line 610-617): Change the `Set-Cookie` value from
   a comma-joined string to a vector of strings:
   ```clojure
   ;; BEFORE:
   (assoc h "Set-Cookie" (str/join ", " set-cookie-headers))

   ;; AFTER:
   (assoc h "Set-Cookie" set-cookie-headers)
   ```
   `set-cookie-headers` is already a vector from `serialize-set-cookies`.

2. **session middleware** (around line 670-679): Accumulate Set-Cookie as a vector:
   ```clojure
   ;; BEFORE:
   (assoc h "Set-Cookie"
          (if existing
            (str existing ", " set-cookie-str)
            set-cookie-str))

   ;; AFTER:
   (assoc h "Set-Cookie"
          (if existing
            (if (vector? existing)
              (conj existing set-cookie-str)
              [existing set-cookie-str])
            [set-cookie-str]))
   ```

3. **Ring adapter** `normalize-response` (ring.clj line 13-21): Ensure vector
   Set-Cookie values are preserved through normalization (no change needed — Ring
   handles vector header values natively).

**Tests to add/update:**
```clojure
(deftest cookies-multiple-set-cookie-headers
  ;; Verify Set-Cookie is a vector, not a comma-joined string
  (let [svc (svc/service {:routes [["/multi" :get
                                    (fn [_] {:status 200 :body "ok"
                                             :cookies {"a" {:value "1" :path "/"}
                                                       "b" {:value "2" :path "/"}}})]]
                           :with [(mw/cookies)]})
        resp (svc/response-for svc :get "/multi")]
    (is (vector? (get-in resp [:headers "Set-Cookie"])))
    (is (= 2 (count (get-in resp [:headers "Set-Cookie"]))))))

(deftest session-preserves-existing-set-cookie
  ;; Session cookie is added alongside app cookies, both as vector entries
  )
```

---

## Issue 2: Multipart Binary Corruption (BUG)

**Severity:** High — file uploads with binary content will be corrupted

**Problem:**
`parse-multipart-body` (line 727-767) does:
1. `(if (string? body) body (slurp body))` — converts everything to a String
2. `(str/split part ...)` — splits the body as a string
3. `(.getBytes ^String (str/trim body-section) "UTF-8")` — re-encodes to bytes

This round-trip through String destroys any non-UTF-8 binary data. A PNG image, for
example, would be irreversibly corrupted. Additionally, `str/trim` on the body section
strips leading/trailing whitespace that may be part of the binary content.

**Fix:**

Rewrite `parse-multipart-body` to work with byte arrays natively using boundary
scanning.

**File:** `src/hearth/alpha/middleware.clj`

Replace `parse-multipart-body` with a binary-safe implementation:

```clojure
(defn- parse-multipart-body
  "Parse a multipart/form-data body into a map of parts.
   Works with raw bytes to avoid binary corruption."
  [body boundary]
  (when (and body boundary)
    (let [^bytes body-bytes (cond
                              (bytes? body) body
                              (string? body) (.getBytes ^String body "UTF-8")
                              (instance? java.io.InputStream body)
                              (.readAllBytes ^java.io.InputStream body)
                              :else nil)]
      (when body-bytes
        (let [delim-bytes (.getBytes (str "--" boundary) "UTF-8")
              end-bytes   (.getBytes (str "--" boundary "--") "UTF-8")]
          ;; Scan for boundary positions in the byte array
          ;; Split at boundaries, parse headers (text) separately from body (bytes)
          ;; For each part:
          ;;   1. Find \r\n\r\n separator between headers and body
          ;;   2. Parse headers as UTF-8 text
          ;;   3. Keep body as raw bytes (no string conversion)
          (binary-split-and-parse body-bytes delim-bytes end-bytes))))))
```

The key helper `binary-split-and-parse` needs to:
1. Find each boundary occurrence by byte scanning (not string matching)
2. For each part, find the `\r\n\r\n` header/body separator
3. Parse the headers section as UTF-8 (headers are always text)
4. Keep the body section as raw `byte[]` — never convert to String
5. For non-file fields (no `filename` in Content-Disposition), the body can safely
   be converted to a String
6. For file fields, store as `{:filename f :content-type ct :bytes raw-bytes :size n}`

Also add `:max-size` enforcement:
```clojure
;; At the start of the multipart-params tf function:
(when (and max-size (> (count body-bytes) max-size))
  (assoc env :res {:status 413 :headers {} :body "Request Entity Too Large"}))
```

**Tests to add:**
```clojure
(deftest multipart-binary-file-preserved
  ;; Upload a body with bytes 0x00-0xFF, verify exact round-trip
  )

(deftest multipart-max-size-enforced
  ;; Exceed max-size, expect 413
  )

(deftest multipart-multiple-files
  ;; Two file fields in one request
  )
```

---

## Issue 3: SimpleDateFormat Thread Safety (BUG)

**Severity:** Medium — intermittent incorrect 304 responses or parse failures under load

**Problem:**
`parse-http-date` (line 853-860) creates a new `SimpleDateFormat` on every call,
which is actually fine for thread safety (the problem would be if it were shared).

However, reviewing more carefully: a new instance *is* created each call, so this is
thread-safe but wasteful. The real concern is **correctness**: `SimpleDateFormat` with
no explicit `Locale` will use the system default locale, which can break date parsing
on non-English locales (e.g., French systems where "Mon" isn't recognized).

**Fix:**

Use `java.time.format.DateTimeFormatter` (immutable, thread-safe, locale-explicit):

```clojure
(let [http-date-formatter
      (java.time.format.DateTimeFormatter/ofPattern
        "EEE, dd MMM yyyy HH:mm:ss zzz"
        java.util.Locale/ENGLISH)]

  (defn- parse-http-date
    "Parse an HTTP date string to epoch millis. Returns nil on failure."
    [date-str]
    (try
      (when date-str
        (.toEpochMilli
          (java.time.Instant/from
            (.parse http-date-formatter date-str))))
      (catch Exception _ nil))))
```

This gives us:
- Thread safety (immutable formatter, shared across threads)
- Locale correctness (explicitly English, per HTTP spec)
- Performance (single instance, no allocation per call)

**Tests to add:**
```clojure
(deftest parse-http-date-formats
  ;; Test RFC 1123 format
  ;; Test RFC 850 format (optional)
  ;; Test asctime format (optional)
  )
```

---

## Issue 4: SSE Blocking Thread Per Connection (DESIGN)

**Severity:** Medium — limits SSE scalability to thread pool size

**Problem:**
`hearth.alpha.sse/event-stream` (line 28-61) uses `a/alts!!` (blocking) in a loop
that runs on the thread calling the body function. This means each SSE client
consumes a thread for the entire connection lifetime.

With Jetty's default thread pool (200 threads), this limits SSE to ~200 concurrent
clients before thread starvation affects regular HTTP.

**Fix:**

The SSE body is a `(fn [OutputStream] ...)` — Ring calls this on a thread. The
blocking approach is actually the simplest correct approach for Ring's streaming
body contract, which expects the function to block until done.

The real fix is to make this work with the async Ring adapter (3-arity handler) and
Jetty's async support. When `::async? true` is set, the SSE handler should use
`AsyncContext` instead of blocking a thread.

However, for a pragmatic fix that improves the current approach:

1. **Document the thread-per-connection limitation** in the docstring
2. **Add a `:thread-pool` option** so users can provide a dedicated executor for SSE
   connections, isolating them from Jetty's request threads:

```clojure
(defn event-stream
  [stream-fn & [{:keys [heartbeat-ms on-close headers buf-or-n executor]
                  :or {heartbeat-ms 10000 buf-or-n 32}}]]
  (fn [request-env]
    (let [event-ch (a/chan buf-or-n)
          body-fn (fn [^java.io.OutputStream os]
                    ;; ... existing blocking loop ...
                    )]
      (stream-fn event-ch request-env)
      {:status 200
       :headers (merge {"Content-Type" "text/event-stream"
                        "Cache-Control" "no-cache, no-store"
                        "Connection" "keep-alive"}
                       headers)
       :body body-fn})))
```

3. **For Phase 2 (future):** Implement a true async SSE handler that returns a
   `CompletableFuture<response>` and uses virtual threads (Java 21) or NIO:

```clojure
(defn async-event-stream
  "SSE handler that uses virtual threads instead of platform threads.
   Requires Java 21+."
  [stream-fn & [opts]]
  ;; Uses Thread/startVirtualThread for the blocking loop
  ;; Compatible with async Ring handler (3-arity)
  )
```

Since this project is on Java 21, virtual threads are available and would make the
blocking approach perfectly scalable without redesigning the API.

**Tests to add:**
```clojure
(deftest sse-heartbeat-fires
  ;; Set heartbeat-ms to 50, verify comment lines appear
  )
```

---

## Issue 5: Missing `fast-resource` (Phase 4.3) (MISSING)

**Severity:** Low — optimization, not a correctness issue

**Problem:**
Phase 4.3 from the impl plan was skipped. `fast-resource` was meant to provide
NIO-based resource serving with caching and memory-mapped files.

**Fix:**

Implement `fast-resource` middleware with:
1. **Response caching** — cache the byte[] + headers for frequently accessed resources
2. **ETag generation** — hash-based or modification-time-based ETags
3. **Cache-Control headers** — configurable max-age

```clojure
(defn fast-resource
  "Middleware that serves classpath resources with caching.
   Options:
     :prefix     - classpath prefix (default: 'public')
     :max-age    - Cache-Control max-age in seconds (default: 86400)
     :cache-size - max cached entries (default: 256)"
  [{:keys [prefix max-age cache-size]
    :or {prefix "public" max-age 86400 cache-size 256}}]
  (let [cache (atom {})  ;; Simple LRU could be java.util.LinkedHashMap
        ]
    (-> t/transformer
        (update :id conj ::fast-resource)
        (update :tf-end conj
                ::fast-resource
                (fn [env]
                  ;; Check cache first, then fall back to classpath lookup
                  ;; Generate ETag from content hash
                  ;; Add Cache-Control: max-age=N header
                  ;; Interact with not-modified middleware for 304 support
                  )))))
```

**Tests:**
```clojure
(deftest fast-resource-caches-responses
  ;; Verify second request is served from cache
  )

(deftest fast-resource-sets-etag
  ;; Verify ETag header is present
  )

(deftest fast-resource-cache-control
  ;; Verify Cache-Control: max-age=N header
  )
```

---

## Issue 6: Multipart `max-size` Not Enforced (BUG)

**Severity:** Medium — DoS vector via unlimited upload size

**Problem:**
`multipart-params` accepts `:max-size` option (line 779) but never checks it.
The body is parsed regardless of size.

**Fix:**

This is addressed as part of Issue 2's rewrite. Add the size check before parsing:

```clojure
(fn [env]
  (if (multipart-content-type? (:headers env))
    (let [ct (get-in env [:headers "content-type"])
          boundary (parse-multipart-boundary ct)
          body (:body env)]
      (if (and boundary body)
        (let [body-bytes (to-byte-array body)]
          (if (and max-size (> (alength body-bytes) max-size))
            ;; Short-circuit with 413
            (assoc env :env-op
                   (constantly {:status 413
                                :headers {}
                                :body "Request Entity Too Large"}))
            (let [parsed (parse-multipart-body-bytes body-bytes boundary)]
              (assoc env :multipart-params parsed))))
        env))
    env))
```

**Tests:**
```clojure
(deftest multipart-rejects-oversized
  (let [svc (svc/service {:routes [["/upload" :post handler]]
                           :with [(mw/multipart-params {:max-size 100})]})
        ;; Build a multipart body > 100 bytes
        large-body (build-multipart-body "x" (apply str (repeat 200 "A")))
        resp (svc/response-for svc :post "/upload"
               {:headers {"content-type" (str "multipart/form-data; boundary=BOUNDARY")}
                :body large-body})]
    (is (= 413 (:status resp)))))
```

---

## Issue 7: Thin Test Coverage in Several Areas (QUALITY)

**Severity:** Low-Medium — reduces confidence in edge case behavior

**Problem areas and tests to add:**

### 7a. Cookies — special characters
```clojure
(deftest cookie-values-with-special-chars
  ;; Cookie values containing = ; , and spaces
  )
```

### 7b. CSRF — non-POST unsafe methods
```clojure
(deftest csrf-blocks-put-without-token
  ;; PUT, PATCH, DELETE should all be blocked
  )
```

### 7c. Session — concurrent access
```clojure
(deftest session-concurrent-writes
  ;; Multiple threads writing to the same session
  ;; memory-store uses atom, should be safe
  )
```

### 7d. WebSocket — message flow
```clojure
(deftest ws-channel-message-roundtrip
  ;; Send message via send-ch, verify it reaches socket mock
  ;; Simulate incoming message, verify it appears on recv-ch
  )
```

### 7e. Multipart — multiple fields same name
```clojure
(deftest multipart-duplicate-field-names
  ;; Two fields named "file", verify last-wins or accumulation
  )
```

### 7f. SSE — heartbeat
```clojure
(deftest sse-heartbeat-sends-comments
  ;; Set heartbeat-ms low, verify ":\n\n" comment lines appear
  )
```

### 7g. Streaming — error handling
```clojure
(deftest streaming-handles-io-exception
  ;; Output stream throws IOException mid-write
  ;; Verify no resource leaks
  )
```

---

## Implementation Order

```
Priority 1 (Correctness bugs):
  Issue 1: Set-Cookie comma-joining        ~15 min
  Issue 2: Multipart binary corruption     ~45 min
  Issue 6: Multipart max-size enforcement  ~10 min (part of Issue 2)
  Issue 3: SimpleDateFormat locale fix     ~10 min

Priority 2 (Design improvements):
  Issue 4: SSE documentation + virtual thread option  ~30 min

Priority 3 (Missing feature):
  Issue 5: fast-resource middleware         ~45 min

Priority 4 (Test coverage):
  Issue 7: Additional edge case tests      ~30 min
```

**Dependencies:**
- Issue 6 is a subset of Issue 2 (do together)
- Issue 5 depends on Issue 3 being done (ETag/304 correctness)
- Issue 7 tests should be written alongside each fix

**Regression gate:** After each fix, run full test suite. All 189 existing tests
must continue to pass. New tests must also pass.

(ns hearth.alpha.middleware
  (:require
   [clojure.string :as str]
   [cognitect.transit :as transit]
   [ti-yong.alpha.transformer :as t])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]
   [java.time Instant ZonedDateTime]
   [java.time.format DateTimeFormatter]
   [java.util Locale]))

;; Middleware are transformers intended to be composed via :with.
;; Each middleware adds steps to :tf (env transforms), :out (response transforms),
;; or :tf-end (final env transforms) pipelines.

;; --- Query Params ---

(defn- parse-query-string
  "Parse a query string like 'a=1&b=2' into a map {\"a\" \"1\" \"b\" \"2\"}."
  [qs]
  (if (or (nil? qs) (str/blank? qs))
    {}
    (->> (str/split qs #"&")
         (map #(str/split % #"=" 2))
         (reduce (fn [m [k v]] (assoc m k (or v ""))) {}))))

(def query-params
  "Middleware that parses :query-string into :query-params map."
  (-> t/transformer
      (update :id conj ::query-params)
      (update :tf conj
              ::query-params
              (fn [env]
                (assoc env :query-params
                       (parse-query-string (:query-string env)))))))

;; --- JSON Body ---

(defn- json-content-type? [headers]
  (when-let [ct (get headers "content-type")]
    (str/includes? ct "application/json")))

(defn- parse-json
  "Minimal JSON parser for common cases. Handles strings, numbers, booleans,
   null, arrays, and objects with string keys."
  [s]
  (when s
    (read-string
     (-> s
         (str/replace #"\"(\w+)\":" (fn [[_ k]] (str "\"" k "\" ")))
         (str/replace #":\s*\"([^\"]*)\"" (fn [[_ v]] (str " \"" v "\"")))
         (str/replace #":\s*(\d+)" (fn [[_ v]] (str " " v)))
         (str/replace #":\s*true" " true")
         (str/replace #":\s*false" " false")
         (str/replace #":\s*null" " nil")
         (str/replace "{" "{")
         (str/replace "}" "}")
         (str/replace "\\[" "[")
         (str/replace "\\]" "]")))))

(defn- simple-json-parse
  "Parse a JSON string into Clojure data. Uses clojure.data.json if available,
   otherwise a minimal parser."
  [s]
  (try
    (let [reader (clojure.lang.RT/loadResourceScript "clojure/data/json.clj")]
      ;; Won't work without the dep, fall through
      (throw (Exception. "not available")))
    (catch Exception _
      ;; Minimal JSON parsing using Clojure reader with transforms
      (when (and s (string? s) (not (str/blank? s)))
        (let [s (str/trim s)]
          (cond
            ;; Object
            (str/starts-with? s "{")
            (let [;; Remove outer braces
                  inner (subs s 1 (dec (count s)))
                  ;; Split by commas not inside quotes or braces
                  pairs (loop [chars (seq inner)
                               current []
                               depth 0
                               in-string? false
                               result []]
                          (if (empty? chars)
                            (if (seq current)
                              (conj result (apply str current))
                              result)
                            (let [c (first chars)
                                  escape? (and in-string? (= c \\))]
                              (cond
                                escape?
                                (recur (drop 2 chars)
                                       (conj current c (second chars))
                                       depth in-string? result)

                                (and (= c \") (not escape?))
                                (recur (rest chars)
                                       (conj current c)
                                       depth (not in-string?) result)

                                (and (not in-string?) (or (= c \{) (= c \[)))
                                (recur (rest chars)
                                       (conj current c)
                                       (inc depth) in-string? result)

                                (and (not in-string?) (or (= c \}) (= c \])))
                                (recur (rest chars)
                                       (conj current c)
                                       (dec depth) in-string? result)

                                (and (not in-string?) (zero? depth) (= c \,))
                                (recur (rest chars)
                                       []
                                       depth in-string?
                                       (conj result (apply str current)))

                                :else
                                (recur (rest chars)
                                       (conj current c)
                                       depth in-string? result)))))]
              (->> pairs
                   (map str/trim)
                   (filter seq)
                   (map (fn [pair]
                          (let [colon-idx (str/index-of pair ":")
                                k (str/trim (subs pair 0 colon-idx))
                                v (str/trim (subs pair (inc colon-idx)))
                                ;; Remove quotes from key
                                k (if (and (str/starts-with? k "\"")
                                           (str/ends-with? k "\""))
                                    (subs k 1 (dec (count k)))
                                    k)]
                            [k (simple-json-parse v)])))
                   (into {})))

            ;; Array
            (str/starts-with? s "[")
            (let [inner (subs s 1 (dec (count s)))
                  ;; Split by commas not inside quotes, braces, or brackets
                  items (loop [chars (seq inner)
                               current []
                               depth 0
                               in-string? false
                               result []]
                          (if (empty? chars)
                            (if (seq current)
                              (conj result (apply str current))
                              result)
                            (let [c (first chars)
                                  escape? (and in-string? (= c \\))]
                              (cond
                                escape?
                                (recur (drop 2 chars)
                                       (conj current c (second chars))
                                       depth in-string? result)

                                (and (= c \") (not escape?))
                                (recur (rest chars)
                                       (conj current c)
                                       depth (not in-string?) result)

                                (and (not in-string?) (or (= c \{) (= c \[)))
                                (recur (rest chars)
                                       (conj current c)
                                       (inc depth) in-string? result)

                                (and (not in-string?) (or (= c \}) (= c \])))
                                (recur (rest chars)
                                       (conj current c)
                                       (dec depth) in-string? result)

                                (and (not in-string?) (zero? depth) (= c \,))
                                (recur (rest chars)
                                       []
                                       depth in-string?
                                       (conj result (apply str current)))

                                :else
                                (recur (rest chars)
                                       (conj current c)
                                       depth in-string? result)))))]
              (->> items
                   (map str/trim)
                   (filter seq)
                   (mapv simple-json-parse)))

            ;; String
            (str/starts-with? s "\"")
            (subs s 1 (dec (count s)))

            ;; Number
            (re-matches #"-?\d+(\.\d+)?" s)
            (if (str/includes? s ".")
              (Double/parseDouble s)
              (Long/parseLong s))

            ;; Boolean / null
            (= s "true") true
            (= s "false") false
            (= s "null") nil

            :else s))))))

(defn- serialize-json
  "Minimal JSON serializer."
  [data]
  (cond
    (nil? data) "null"
    (string? data) (str "\"" data "\"")
    (number? data) (str data)
    (true? data) "true"
    (false? data) "false"
    (keyword? data) (serialize-json (name data))
    (map? data)
    (str "{"
         (->> data
              (map (fn [[k v]]
                     (str (serialize-json (if (keyword? k) (name k) (str k)))
                          ":"
                          (serialize-json v))))
              (str/join ","))
         "}")
    (sequential? data)
    (str "[" (->> data (map serialize-json) (str/join ",")) "]")
    :else (str "\"" data "\"")))

(def json-body
  "Middleware that parses JSON body into :json-body when content-type is application/json."
  (-> t/transformer
      (update :id conj ::json-body)
      (update :tf conj
              ::json-body
              (fn [env]
                (if (json-content-type? (:headers env))
                  (assoc env :json-body (simple-json-parse (:body env)))
                  env)))))

(def json-response
  "Middleware that serializes map results to JSON strings in the :out pipeline."
  (-> t/transformer
      (update :id conj ::json-response)
      (update :out conj
              ::json-response
              (fn [res]
                (if (map? res)
                  (serialize-json res)
                  res)))))

;; --- Logging ---

(defn logging
  "Middleware that logs request/response info to the given atom."
  [log-atom]
  (-> t/transformer
      (update :id conj ::logging)
      (update :tf conj
              ::logging
              (fn [env]
                (swap! log-atom conj {:method (:request-method env)
                                      :uri (:uri env)
                                      :timestamp (System/currentTimeMillis)})
                env))))

;; --- Content-Type ---

(defn default-content-type
  "Middleware that sets a default Content-Type header on the :res response map in :tf-end."
  [ct]
  (-> t/transformer
      (update :id conj ::default-content-type)
      (update :tf-end conj
              ::default-content-type
              (fn [env]
                (let [res (:res env)]
                  (if (and (map? res) (not (get-in res [:headers "Content-Type"])))
                    (assoc env :res (assoc-in res [:headers "Content-Type"] ct))
                    env))))))

;; --- CORS ---

(defn cors
  "Middleware that adds CORS headers to the :res response map in :tf-end."
  [{:keys [allowed-origins allowed-methods allowed-headers]}]
  (-> t/transformer
      (update :id conj ::cors)
      (update :tf-end conj
              ::cors
              (fn [env]
                (let [res (:res env)]
                  (if (map? res)
                    (assoc env :res
                           (cond-> res
                             allowed-origins
                             (assoc-in [:headers "Access-Control-Allow-Origin"] allowed-origins)
                             allowed-methods
                             (assoc-in [:headers "Access-Control-Allow-Methods"] allowed-methods)
                             allowed-headers
                             (assoc-in [:headers "Access-Control-Allow-Headers"] allowed-headers)))
                    env))))))

;; --- Not Found Handler ---

(defn not-found-handler
  "Middleware that returns a 404 response map when :res is nil in :tf-end."
  [default-body]
  (-> t/transformer
      (update :id conj ::not-found-handler)
      (update :tf-end conj
              ::not-found-handler
              (fn [env]
                (if (nil? (:res env))
                  (assoc env :res {:status 404 :headers {} :body default-body})
                  env)))))

;; --- Form Params ---

(defn- parse-form-body
  "Parse application/x-www-form-urlencoded body into a map."
  [body]
  (if (or (nil? body) (and (string? body) (str/blank? body)))
    {}
    (let [s (if (string? body) body (slurp body))]
      (if (str/blank? s)
        {}
        (->> (str/split s #"&")
             (map #(str/split % #"=" 2))
             (reduce (fn [m [k v]]
                       (assoc m
                              (java.net.URLDecoder/decode (or k "") "UTF-8")
                              (java.net.URLDecoder/decode (or v "") "UTF-8")))
                     {}))))))

(defn- form-content-type? [headers]
  (when-let [ct (get headers "content-type")]
    (str/includes? ct "application/x-www-form-urlencoded")))

(def form-params
  "Middleware that parses form-encoded bodies into :form-params."
  (-> t/transformer
      (update :id conj ::form-params)
      (update :tf conj
              ::form-params
              (fn [env]
                (if (form-content-type? (:headers env))
                  (assoc env :form-params (parse-form-body (:body env)))
                  env)))))

;; --- Body Params (unified) ---

(defn- read-body-string
  "Read body as a string. Handles String and InputStream."
  [body]
  (cond
    (string? body) body
    (instance? java.io.InputStream body) (slurp body)
    :else nil))

(def body-params
  "Middleware that parses body based on content-type into :body-params.
   Supports JSON and form-encoded bodies."
  (-> t/transformer
      (update :id conj ::body-params)
      (update :tf conj
              ::body-params
              (fn [env]
                (let [headers (:headers env)
                      body (read-body-string (:body env))]
                  (cond
                    (and body (json-content-type? headers))
                    (let [parsed (simple-json-parse body)]
                      (assoc env :body-params parsed :json-params parsed))

                    (and body (form-content-type? headers))
                    (let [parsed (parse-form-body body)]
                      (assoc env :body-params parsed :form-params parsed))

                    :else env))))))

;; --- Keyword Params ---

(defn- keywordize-keys-shallow [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (assoc acc (if (string? k) (keyword k) k) v))
               {} m)))

(def keyword-params
  "Middleware that keywordizes string keys in :query-params, :body-params, :form-params."
  (-> t/transformer
      (update :id conj ::keyword-params)
      (update :tf conj
              ::keyword-params
              (fn [env]
                (cond-> env
                  (:query-params env)
                  (update :query-params keywordize-keys-shallow)
                  (:body-params env)
                  (update :body-params keywordize-keys-shallow)
                  (:form-params env)
                  (update :form-params keywordize-keys-shallow)
                  (:json-params env)
                  (update :json-params keywordize-keys-shallow))))))

;; --- Content Negotiation ---

(defn- parse-accept
  "Parse Accept header into ordered list of media types."
  [accept-header]
  (when accept-header
    (->> (str/split accept-header #",")
         (map str/trim)
         (map (fn [entry]
                (let [[type & params] (str/split entry #";")
                      q (or (some->> params
                                      (map str/trim)
                                      (filter #(str/starts-with? % "q="))
                                      first
                                      (re-find #"[\d.]+")
                                      Double/parseDouble)
                            1.0)]
                  {:type (str/trim type) :q q})))
         (sort-by :q >)
         (mapv :type))))

(defn content-negotiation
  "Middleware that inspects Accept header and auto-serializes response.
   Supported formats: application/json, application/edn, text/html, text/plain."
  []
  (-> t/transformer
      (update :id conj ::content-negotiation)
      (update :tf conj
              ::content-negotiation-parse
              (fn [env]
                (let [accept (get-in env [:headers "accept"])
                      types (parse-accept accept)]
                  (assoc env ::accept-types (or types ["*/*"])))))
      (update :tf-end conj
              ::content-negotiation-respond
              (fn [env]
                (let [res (:res env)
                      types (::accept-types env ["*/*"])]
                  (if-not (and (map? res) (:body res))
                    env
                    (let [body (:body res)
                          preferred (first types)]
                      (cond
                        ;; Already has Content-Type - don't override
                        (get-in res [:headers "Content-Type"])
                        env

                        ;; JSON requested and body is data
                        (and (or (= preferred "application/json")
                                 (= preferred "*/*"))
                             (or (map? body) (sequential? body)))
                        (assoc env :res
                               (-> res
                                   (assoc :body (serialize-json body))
                                   (assoc-in [:headers "Content-Type"] "application/json")))

                        ;; EDN requested
                        (= preferred "application/edn")
                        (assoc env :res
                               (-> res
                                   (assoc :body (pr-str body))
                                   (assoc-in [:headers "Content-Type"] "application/edn")))

                        ;; HTML requested
                        (= preferred "text/html")
                        (assoc env :res
                               (assoc-in res [:headers "Content-Type"] "text/html"))

                        :else env))))))))

;; --- HTML Body ---

(def html-body
  "Middleware that sets Content-Type to text/html on response maps."
  (-> t/transformer
      (update :id conj ::html-body)
      (update :tf-end conj
              ::html-body
              (fn [env]
                (let [res (:res env)]
                  (if (and (map? res) (not (get-in res [:headers "Content-Type"])))
                    (assoc env :res (assoc-in res [:headers "Content-Type"] "text/html"))
                    env))))))

;; --- JSON Body Response ---

(def json-body-response
  "Middleware that serializes response body to JSON and sets Content-Type.
   Unlike json-response (which works on raw :out), this works on :res response maps."
  (-> t/transformer
      (update :id conj ::json-body-response)
      (update :tf-end conj
              ::json-body-response
              (fn [env]
                (let [res (:res env)]
                  (if (and (map? res)
                           (or (map? (:body res)) (sequential? (:body res)))
                           (not (get-in res [:headers "Content-Type"])))
                    (assoc env :res
                           (-> res
                               (assoc :body (serialize-json (:body res)))
                               (assoc-in [:headers "Content-Type"] "application/json")))
                    env))))))

;; --- Secure Headers ---

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

;; --- Method Override ---

(defn method-param
  "Middleware that overrides :request-method from a query/form param.
   Default param name is '_method'. Only overrides POST requests."
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

;; --- Cookies ---

(defn- parse-cookie-header
  "Parse a Cookie header string into a map of {name {:value value}}."
  [header]
  (when (and header (not (str/blank? header)))
    (->> (str/split header #";\s*")
         (map str/trim)
         (filter seq)
         (reduce (fn [m pair]
                   (let [eq-idx (str/index-of pair "=")]
                     (if eq-idx
                       (let [k (str/trim (subs pair 0 eq-idx))
                             v (str/trim (subs pair (inc eq-idx)))]
                         (assoc m k {:value v}))
                       m)))
                 {}))))

(defn- serialize-set-cookie
  "Serialize a single Set-Cookie entry. cookie-val is {:value v :path p :domain d ...}."
  [name cookie-val]
  (let [{:keys [value path domain max-age secure http-only same-site expires]} cookie-val]
    (str name "=" value
         (when path (str "; Path=" path))
         (when domain (str "; Domain=" domain))
         (when max-age (str "; Max-Age=" max-age))
         (when expires (str "; Expires=" expires))
         (when secure "; Secure")
         (when http-only "; HttpOnly")
         (when same-site (str "; SameSite=" same-site)))))

(defn- serialize-set-cookies
  "Serialize a cookies map into a vector of Set-Cookie header strings."
  [cookies-map]
  (mapv (fn [[name val]]
          (serialize-set-cookie name
                                (if (map? val) val {:value (str val)})))
        cookies-map))

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
                    (assoc env :cookies {})))))
      (update :tf-end conj
              ::cookies-write
              (fn [env]
                (let [res (:res env)
                      cookies-to-set (:cookies res)]
                  (if (and (map? res) (seq cookies-to-set))
                    (let [set-cookie-headers (serialize-set-cookies cookies-to-set)]
                      (assoc env :res
                             (-> res
                                 (dissoc :cookies)
                                 (update :headers
                                         (fn [h]
                                           (assoc h "Set-Cookie"
                                                  set-cookie-headers))))))
                    env))))))

;; --- Session ---

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
     :store       - ISessionStore impl (default: memory-store)
     :cookie-name - session cookie name (default: 'hearth-session')
     :cookie-attrs - map of cookie attributes for the session cookie"
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
                   (assoc env :session (or session-data {})
                              ::session-id session-id
                              ::session-store store
                              ::session-cookie-name cookie-name
                              ::session-cookie-attrs cookie-attrs))))
       (update :tf-end conj
               ::session-save
               (fn [env]
                 (let [res (:res env)
                       session-from-res (when (map? res) (:session res))
                       session (or session-from-res (:session env))
                       session-id (or (::session-id env)
                                      (str (java.util.UUID/randomUUID)))
                       s-store (::session-store env store)
                       s-cookie (::session-cookie-name env cookie-name)
                       s-attrs (::session-cookie-attrs env cookie-attrs)]
                   (write-session s-store session-id session)
                   (let [cookie-val (merge {:value session-id} s-attrs)
                         set-cookie-str (serialize-set-cookie s-cookie cookie-val)
                         res (if (map? res)
                               (-> res
                                   (dissoc :session)
                                   (update :headers
                                           (fn [h]
                                             (let [existing (get h "Set-Cookie")]
                                               (assoc h "Set-Cookie"
                                                      (cond
                                                        (nil? existing) [set-cookie-str]
                                                        (vector? existing) (conj existing set-cookie-str)
                                                        :else [existing set-cookie-str]))))))
                               res)]
                     (assoc env :res res))))))))

;; --- CSRF ---

(defn csrf
  "Middleware that validates anti-forgery tokens on state-changing requests.
   Options:
     :read-token     - fn to extract token from request env
     :error-response - response map for failed validation
     :token-length   - length of generated tokens (default 32)"
  ([] (csrf {}))
  ([{:keys [read-token error-response]
     :or {read-token (fn [env]
                       (or (get-in env [:form-params "__anti-forgery-token"])
                           (get-in env [:headers "x-csrf-token"])
                           (get-in env [:query-params "__anti-forgery-token"])))
          error-response {:status 403 :headers {} :body "Forbidden - CSRF token invalid"}}}]
   (-> t/transformer
       (update :id conj ::csrf)
       (update :tf conj
               ::csrf
               (fn [env]
                 (let [method (:request-method env)]
                   (if (#{:get :head :options} method)
                     ;; Safe methods: generate/attach token
                     (let [token (or (get-in env [:session :csrf-token])
                                     (str (java.util.UUID/randomUUID)))]
                       (-> env
                           (assoc :csrf-token token)
                           (assoc-in [:session :csrf-token] token)))
                     ;; Unsafe methods: validate token
                     (let [expected (get-in env [:session :csrf-token])
                           actual (read-token env)]
                       (if (and expected actual (= expected actual))
                         env
                         ;; Short-circuit: replace the handler with error response
                         (assoc env :env-op (constantly error-response)))))))))))

;; --- Multipart Params ---

(defn- parse-multipart-boundary
  "Extract the boundary string from a multipart content-type header."
  [content-type]
  (when content-type
    (second (re-find #"boundary=(.+)" content-type))))

(defn- index-of-bytes
  "Find the index of needle byte array in haystack starting at from-index.
   Returns -1 if not found."
  ^long [^bytes haystack ^bytes needle ^long from-index]
  (let [hlen (alength haystack)
        nlen (alength needle)
        limit (- hlen nlen)]
    (loop [i from-index]
      (if (> i limit)
        -1
        (if (loop [j 0]
              (if (= j nlen)
                true
                (if (= (aget haystack (+ i j)) (aget needle j))
                  (recur (inc j))
                  false)))
          i
          (recur (inc i)))))))

(def ^:private ^bytes crlf-crlf (.getBytes "\r\n\r\n" "US-ASCII"))

(defn- parse-part-headers
  "Parse the header section of a multipart part (as bytes) into header strings."
  [^bytes header-bytes]
  (let [header-str (String. header-bytes "UTF-8")
        lines (str/split-lines header-str)]
    (reduce (fn [m line]
              (let [colon-idx (str/index-of line ":")]
                (if colon-idx
                  (assoc m
                         (str/lower-case (str/trim (subs line 0 colon-idx)))
                         (str/trim (subs line (inc colon-idx))))
                  m)))
            {}
            lines)))

(defn- parse-multipart-body
  "Parse a multipart/form-data body into a map of parts.
   Works with raw bytes to avoid binary corruption for file uploads.
   Each part is either a string value or a map with :filename, :content-type, :bytes."
  [body boundary]
  (when (and body boundary)
    (let [^bytes body-bytes (cond
                              (bytes? body) body
                              (string? body) (.getBytes ^String body "UTF-8")
                              (instance? java.io.InputStream body)
                              (.readAllBytes ^java.io.InputStream body)
                              :else nil)]
      (when body-bytes
        (let [delim (.getBytes (str "--" boundary) "US-ASCII")
              dlen (alength delim)
              blen (alength body-bytes)]
          ;; Find all boundary positions
          (loop [pos 0
                 result {}]
            (let [bnd-pos (index-of-bytes body-bytes delim pos)]
              (if (= bnd-pos -1)
                result
                ;; Skip past the boundary + \r\n
                (let [after-delim (+ bnd-pos dlen)]
                  ;; Check for closing delimiter --
                  (if (and (< (inc after-delim) blen)
                           (= (aget body-bytes after-delim) (byte 0x2d))
                           (= (aget body-bytes (inc after-delim)) (byte 0x2d)))
                    result ;; End of multipart
                    ;; Skip \r\n after boundary
                    (let [part-start (cond
                                      (and (< (inc after-delim) blen)
                                           (= (aget body-bytes after-delim) (byte 0x0d))
                                           (= (aget body-bytes (inc after-delim)) (byte 0x0a)))
                                      (+ after-delim 2)
                                      (and (< after-delim blen)
                                           (= (aget body-bytes after-delim) (byte 0x0a)))
                                      (inc after-delim)
                                      :else after-delim)
                          ;; Find header/body separator (\r\n\r\n)
                          hdr-end (index-of-bytes body-bytes crlf-crlf part-start)]
                      (if (= hdr-end -1)
                        result
                        (let [hdr-bytes (java.util.Arrays/copyOfRange body-bytes (int part-start) (int hdr-end))
                              body-start (+ hdr-end 4) ;; skip \r\n\r\n
                              ;; Find next boundary to know where body ends
                              next-bnd (index-of-bytes body-bytes delim body-start)
                              ;; Body ends 2 bytes before next boundary (\r\n before --)
                              body-end (if (= next-bnd -1)
                                         blen
                                         (let [e (- next-bnd 2)]
                                           (if (and (>= e body-start)
                                                    (= (aget body-bytes e) (byte 0x0d))
                                                    (= (aget body-bytes (inc e)) (byte 0x0a)))
                                             e
                                             next-bnd)))
                              part-body-bytes (java.util.Arrays/copyOfRange
                                               body-bytes (int body-start) (int body-end))
                              headers (parse-part-headers hdr-bytes)
                              disp (get headers "content-disposition" "")
                              name-match (second (re-find #"name=\"([^\"]+)\"" disp))
                              filename-match (second (re-find #"filename=\"([^\"]+)\"" disp))
                              part-ct (get headers "content-type")]
                          (if name-match
                            (recur (if (= next-bnd -1) blen next-bnd)
                                   (assoc result name-match
                                          (if filename-match
                                            {:filename filename-match
                                             :content-type (or part-ct "application/octet-stream")
                                             :bytes part-body-bytes
                                             :size (alength part-body-bytes)}
                                            (String. part-body-bytes "UTF-8"))))
                            (recur (if (= next-bnd -1) blen next-bnd)
                                   result)))))))))))))))

(defn- multipart-content-type?
  [headers]
  (when-let [ct (get headers "content-type")]
    (str/includes? ct "multipart/form-data")))

(defn- body-to-bytes
  "Convert body to byte array for size checking."
  ^bytes [body]
  (cond
    (bytes? body) body
    (string? body) (.getBytes ^String body "UTF-8")
    (instance? java.io.InputStream body)
    (.readAllBytes ^java.io.InputStream body)
    :else nil))

(defn multipart-params
  "Middleware that parses multipart/form-data request bodies.
   Options:
     :max-size - maximum body size in bytes (default 10MB). Enforced before parsing."
  ([] (multipart-params {}))
  ([{:keys [max-size] :or {max-size (* 10 1024 1024)}}]
   (-> t/transformer
       (update :id conj ::multipart-params)
       (update :tf conj
               ::multipart-params
               (fn [env]
                 (if (multipart-content-type? (:headers env))
                   (let [ct (get-in env [:headers "content-type"])
                         boundary (parse-multipart-boundary ct)
                         body (:body env)]
                     (if (and boundary body)
                       (let [^bytes body-bytes (body-to-bytes body)]
                         (if (and max-size body-bytes (> (alength body-bytes) ^long max-size))
                           ;; Short-circuit with 413
                           (assoc env :env-op
                                  (constantly {:status 413
                                               :headers {}
                                               :body "Request Entity Too Large"}))
                           (let [parsed (parse-multipart-body body-bytes boundary)]
                             (assoc env :multipart-params parsed))))
                       env))
                   env))))))

;; --- Nested Params ---

(defn- nest-params
  "Transform flat bracket-notation params into nested maps.
   e.g. {\"user[name]\" \"Alice\"} -> {\"user\" {\"name\" \"Alice\"}}"
  [params]
  (when params
    (reduce-kv
     (fn [m k v]
       (if (str/includes? (str k) "[")
         (let [;; Parse keys: 'a[b][c]' -> ['a' 'b' 'c']
               parts (-> (str k)
                         (str/replace "]" "")
                         (str/split #"\["))
               path (mapv str/trim parts)]
           (assoc-in m path v))
         (assoc m k v)))
     {}
     params)))

(def nested-params
  "Middleware that nests flat params with bracket notation into nested maps.
   e.g. user[name]=Alice -> {:user {:name \"Alice\"}}"
  (-> t/transformer
      (update :id conj ::nested-params)
      (update :tf conj
              ::nested-params
              (fn [env]
                (cond-> env
                  (:query-params env) (update :query-params nest-params)
                  (:form-params env) (update :form-params nest-params)
                  (:body-params env) (update :body-params nest-params))))))

;; --- HEAD Method Support ---

(def head-method
  "Middleware that converts HEAD requests to GET, then strips the response body.
   This allows GET handlers to also serve HEAD requests automatically."
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
                    (if (map? res)
                      (assoc env :res (assoc res :body nil))
                      env))
                  env)))))

;; --- Not Modified (304) ---

(def ^:private http-date-formatter
  "Thread-safe, locale-explicit HTTP date formatter (RFC 1123)."
  (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss zzz" Locale/ENGLISH))

(defn- parse-http-date
  "Parse an HTTP date string to epoch millis. Returns nil on failure.
   Uses DateTimeFormatter (thread-safe, explicit English locale)."
  [date-str]
  (try
    (when date-str
      (.toEpochMilli (Instant/from (.parse http-date-formatter date-str))))
    (catch Exception _ nil)))

(def not-modified
  "Middleware that returns 304 Not Modified when appropriate.
   Supports ETag/If-None-Match and Last-Modified/If-Modified-Since."
  (-> t/transformer
      (update :id conj ::not-modified)
      (update :tf-end conj
              ::not-modified
              (fn [env]
                (let [res (:res env)]
                  (if (and (map? res) (= 200 (:status res)))
                    (let [req-etag (get-in env [:headers "if-none-match"])
                          res-etag (get-in res [:headers "ETag"])
                          req-modified (get-in env [:headers "if-modified-since"])
                          res-modified (get-in res [:headers "Last-Modified"])]
                      (if (or (and req-etag res-etag (= req-etag res-etag))
                              (and req-modified res-modified
                                   (let [req-ms (parse-http-date req-modified)
                                         res-ms (parse-http-date res-modified)]
                                     (and req-ms res-ms (<= res-ms req-ms)))))
                        (assoc env :res {:status 304
                                         :headers (select-keys (:headers res)
                                                               ["ETag" "Last-Modified"])
                                         :body nil})
                        env))
                    env))))))

;; --- Static Resources (classpath) ---

(defn- mime-type-for
  "Guess MIME type from a file path."
  [path]
  (let [ext (when-let [i (str/last-index-of path ".")]
              (subs path (inc i)))]
    (case ext
      "html" "text/html"
      "htm"  "text/html"
      "css"  "text/css"
      "js"   "application/javascript"
      "json" "application/json"
      "png"  "image/png"
      "jpg"  "image/jpeg"
      "jpeg" "image/jpeg"
      "gif"  "image/gif"
      "svg"  "image/svg+xml"
      "ico"  "image/x-icon"
      "txt"  "text/plain"
      "xml"  "application/xml"
      "pdf"  "application/pdf"
      "woff" "font/woff"
      "woff2" "font/woff2"
      "ttf"  "font/ttf"
      "eot"  "application/vnd.ms-fontobject"
      "application/octet-stream")))

(defn resource
  "Middleware that serves static files from the classpath.
   Runs in :tf-end as a fallback — only serves if response is 404 or no response set.
   Options:
     :prefix - classpath prefix (default: 'public')"
  [{:keys [prefix] :or {prefix "public"}}]
  (-> t/transformer
      (update :id conj ::resource)
      (update :tf-end conj
              ::resource
              (fn [env]
                (let [res (:res env)]
                  (if (and res (map? res) (not= 404 (:status res)))
                    env  ;; Non-404 response exists, don't override
                    (let [path (str prefix (:uri env))
                          safe? (not (str/includes? path ".."))
                          rsrc (when safe? (clojure.java.io/resource path))]
                      (if rsrc
                        (assoc env :res {:status 200
                                         :headers {"Content-Type" (mime-type-for path)}
                                         :body (clojure.java.io/input-stream rsrc)})
                        env))))))))

;; --- Static Resources (filesystem) ---

(defn file
  "Middleware that serves static files from a filesystem directory.
   Runs in :tf-end as a fallback — only serves if response is 404 or no response set.
   Options:
     :root - base directory path"
  [{:keys [root]}]
  (-> t/transformer
      (update :id conj ::file)
      (update :tf-end conj
              ::file
              (fn [env]
                (let [res (:res env)]
                  (if (and res (map? res) (not= 404 (:status res)))
                    env
                    (let [uri (:uri env)
                          f (java.io.File. ^String root ^String uri)
                          canonical (.getCanonicalPath f)
                          root-canonical (.getCanonicalPath (java.io.File. ^String root))]
                      (if (and (.exists f)
                               (.isFile f)
                               (str/starts-with? canonical root-canonical))
                        (assoc env :res {:status 200
                                         :headers {"Content-Type" (mime-type-for uri)
                                                   "Content-Length" (str (.length f))}
                                         :body (java.io.FileInputStream. f)})
                        env))))))))

;; --- Fast Resource (classpath with caching) ---

(defn- resource-etag
  "Generate an ETag from resource content bytes."
  [^bytes content-bytes]
  (let [hash (Integer/toHexString (java.util.Arrays/hashCode content-bytes))]
    (str "\"" hash "\"")))

(defn fast-resource
  "Middleware that serves classpath resources with in-memory caching and ETag support.
   Cached responses are served with ETag and Cache-Control headers for efficient
   conditional requests (works with not-modified middleware for 304s).
   Options:
     :prefix     - classpath prefix (default: 'public')
     :max-age    - Cache-Control max-age in seconds (default: 86400)
     :cache-size - max cached entries (default: 256)"
  [{:keys [prefix max-age cache-size]
    :or {prefix "public" max-age 86400 cache-size 256}}]
  (let [cache (atom {})] ;; path -> {:headers h :bytes b}
    (-> t/transformer
        (update :id conj ::fast-resource)
        (update :tf-end conj
                ::fast-resource
                (fn [env]
                  (let [res (:res env)]
                    (if (and res (map? res) (not= 404 (:status res)))
                      env
                      (let [uri (:uri env)
                            path (str prefix uri)
                            safe? (not (str/includes? path ".."))]
                        (if-not safe?
                          env
                          (let [cached (get @cache path)]
                            (if cached
                              ;; Serve from cache — create fresh InputStream each time
                              (assoc env :res {:status 200
                                              :headers (:headers cached)
                                              :body (ByteArrayInputStream. ^bytes (:bytes cached))})
                              ;; Try loading from classpath
                              (let [rsrc (clojure.java.io/resource path)]
                                (if-not rsrc
                                  env
                                  (let [content-bytes (with-open [is (clojure.java.io/input-stream rsrc)]
                                                       (.readAllBytes ^java.io.InputStream is))
                                        etag (resource-etag content-bytes)
                                        hdrs {"Content-Type" (mime-type-for path)
                                              "Content-Length" (str (alength content-bytes))
                                              "ETag" etag
                                              "Cache-Control" (str "public, max-age=" max-age)}]
                                    ;; Cache if under limit
                                    (when (< (count @cache) cache-size)
                                      (swap! cache assoc path {:headers hdrs :bytes content-bytes}))
                                    (assoc env :res {:status 200
                                                     :headers hdrs
                                                     :body (ByteArrayInputStream. content-bytes)})))))))))))))))

;; --- SSE Event Formatting ---

(defn format-sse-event
  "Format an event map (or plain string) as SSE text.
   Event map keys: :data (required), :event (optional), :id (optional), :retry (optional)"
  [event]
  (let [event (if (map? event) event {:data event})]
    (str
     (when-let [id (:id event)] (str "id: " id "\n"))
     (when-let [evt (:event event)] (str "event: " evt "\n"))
     (when-let [retry (:retry event)] (str "retry: " retry "\n"))
     "data: " (str (:data event)) "\n\n")))

;; --- Public JSON helpers ---

(def parse-json-string simple-json-parse)
(def serialize-json-string serialize-json)

;; --- Transit ---

(defn- transit-read
  "Read transit data from an InputStream or string."
  [input format opts]
  (let [in (cond
             (instance? java.io.InputStream input) input
             (string? input) (ByteArrayInputStream. (.getBytes ^String input "UTF-8"))
             :else nil)]
    (when in
      (let [reader (if (seq opts)
                     (transit/reader in format opts)
                     (transit/reader in format))]
        (transit/read reader)))))

(defn- transit-write
  "Write data as transit to a byte array."
  [data format opts]
  (let [baos (ByteArrayOutputStream.)]
    (let [writer (if (seq opts)
                   (transit/writer baos format opts)
                   (transit/writer baos format))]
      (transit/write writer data))
    (.toByteArray baos)))

(defn transit-body
  "Middleware that parses Transit request bodies (JSON or MessagePack).
   Detects format from Content-Type header."
  ([] (transit-body {}))
  ([{:keys [opts] :or {opts {}}}]
   (-> t/transformer
       (update :id conj ::transit-body)
       (update :tf conj
               ::transit-body
               (fn [env]
                 (let [ct (get-in env [:headers "content-type"] "")]
                   (cond
                     (str/includes? ct "transit+json")
                     (let [body (:body env)]
                       (if body
                         (assoc env :body-params (transit-read body :json opts))
                         env))

                     (str/includes? ct "transit+msgpack")
                     (let [body (:body env)]
                       (if body
                         (assoc env :body-params (transit-read body :msgpack opts))
                         env))

                     :else env)))))))

(defn transit-json-response
  "Middleware that serializes response body as Transit+JSON.
   Sets Content-Type to application/transit+json."
  ([] (transit-json-response {}))
  ([{:keys [opts] :or {opts {}}}]
   (-> t/transformer
       (update :id conj ::transit-json-response)
       (update :tf-end conj
               ::transit-json-response
               (fn [env]
                 (let [res (:res env)]
                   (if (and (map? res) (:body res)
                            (not (string? (:body res)))
                            (not (instance? java.io.InputStream (:body res))))
                     (assoc env :res
                            (-> res
                                (assoc :body (transit-write (:body res) :json opts))
                                (assoc-in [:headers "Content-Type"] "application/transit+json")))
                     env)))))))

(defn transit-msgpack-response
  "Middleware that serializes response body as Transit+MessagePack.
   Sets Content-Type to application/transit+msgpack."
  ([] (transit-msgpack-response {}))
  ([{:keys [opts] :or {opts {}}}]
   (-> t/transformer
       (update :id conj ::transit-msgpack-response)
       (update :tf-end conj
               ::transit-msgpack-response
               (fn [env]
                 (let [res (:res env)]
                   (if (and (map? res) (:body res)
                            (not (string? (:body res)))
                            (not (instance? java.io.InputStream (:body res))))
                     (assoc env :res
                            (-> res
                                (assoc :body (transit-write (:body res) :msgpack opts))
                                (assoc-in [:headers "Content-Type"] "application/transit+msgpack")))
                     env)))))))

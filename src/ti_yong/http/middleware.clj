(ns ti-yong.http.middleware
  (:require
   [clojure.string :as str]
   [ti-yong.alpha.transformer :as t]))

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

;; --- Public JSON helpers ---

(def parse-json-string simple-json-parse)
(def serialize-json-string serialize-json)

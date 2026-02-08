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
            (let [inner (subs s 1 (dec (count s)))]
              ;; Simple split for arrays (doesn't handle nested)
              (->> (str/split inner #",")
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

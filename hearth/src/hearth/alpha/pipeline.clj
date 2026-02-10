(ns hearth.alpha.pipeline
  "Res-aware pipeline utilities for Hearth's HTTP processing.
   These functions inject :res-based short-circuit semantics into
   ti-yong's transformer pipeline, without modifying ti-yong core.

   Short-circuit semantics:
     - A :res with a :body key is considered 'complete' (short-circuits)
     - A :res without :body (e.g. from default-response) is 'partial' (does not short-circuit)
     - When a complete :res is set by middleware, remaining :tf steps and the handler are skipped
     - :tf-end (leave) steps always run regardless"
  (:require
   [ti-yong.alpha.util :as u]
   [ti-yong.alpha.transformer :as t]))

(defn- complete-res?
  "Returns true if the env has a 'complete' :res — one with a :body key.
   A partial :res (e.g. {:status 200 :headers {}}) from default-response
   does NOT trigger short-circuiting."
  [env]
  (and (:res env) (contains? (:res env) :body)))

(defn res-aware-tform
  "Replacement for ti-yong.alpha.root/tform that checks :res between
   :tf pipeline steps. If any step sets a complete :res (with :body key),
   remaining steps are skipped via `reduced`."
  [env]
  (if-not (seq (:tf env))
    env
    (let [pipeline (u/uniq-by-pairwise-first (:tf env))]
      (if-not (seq pipeline)
        env
        (reduce (fn [current-env tf-fn]
                  (if (complete-res? current-env)
                    (reduced current-env)
                    ((or tf-fn identity) current-env)))
                env
                pipeline)))))

(defn res-aware-env-op
  "Wraps an env-op so it returns the existing :res when a complete response
   is already set (has :body key), skipping the handler."
  [original-env-op]
  (fn [env]
    (if (complete-res? env)
      (:res env)
      (original-env-op env))))

(def default-response
  "Transformer that initializes :res with a default 200 response.
   Composed automatically for transformer handlers so they can use
   (update env :res assoc :body ...) in their :tf steps.
   The partial :res (no :body key) does NOT trigger short-circuiting."
  (-> t/transformer
      (update :id conj ::default-response)
      (update :tf conj
              ::default-response
              (fn [env]
                (if (:res env)
                  env
                  (assoc env :res {:status 200 :headers {}}))))))

(defn handler-env-op
  "Env-op for transformer handlers. Extracts :res from the env so that
   Stage 2 in root.clj preserves the response set by :tf steps."
  [env]
  (:res env))

(ns ti-yong.alpha.util)

(defn if-cljs
  [env then else]
  (if (:ns env) then else))

(defn identities [& op-args]
  (case (count op-args)
    0 nil
    1 (first op-args)
    op-args))

(defn muff [s]
  (if-not s
    []
    (if (sequential? s)
      s
      [s])))

(defn uniq-by [f els]
  (->> els
       (reduce (fn [acc m]
                 (let [k (f m)]
                   (if (get-in acc [:seen k])
                     acc
                     (-> acc
                         (update :seen assoc k true)
                         (update :uniq conj m)))))
               {:seen {} :uniq []})
       :uniq))

(defn uniq-by-pairwise-first [s]
  (->> s (partition 2) (uniq-by first) (map second)))

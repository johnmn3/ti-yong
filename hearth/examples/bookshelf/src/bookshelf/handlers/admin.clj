(ns bookshelf.handlers.admin
  "Admin handlers — stats, exports, bulk operations.
   All handlers extend the admin-ns base transformer, which requires
   admin authentication for ALL admin handlers.
   All request data lives on :ctx."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Namespace transformer ---

(def admin-ns
  "Base transformer for all admin handlers. Requires admin role on every endpoint."
  (-> t/transformer
      (update :id conj ::admin)
      (update :with conj mw/json-body-response
              app-mw/authenticate app-mw/require-auth
              (app-mw/require-role :admin))))

;; --- Handlers ---

(def stats
  (-> admin-ns
      (assoc :doc "Dashboard statistics: entity counts and recent activity.")
      (update :id conj ::stats)
      (update :tf conj
              ::stats
              (fn [env]
                (update env :res assoc
                        :body {:books (count @db/books)
                               :authors (count @db/authors)
                               :reviews (count @db/reviews)
                               :users (count @db/users)
                               :reading-lists (count @db/reading-lists)
                               :notifications (count @db/notifications)
                               :api-keys (count @db/api-keys)})))))

(def export-books
  (-> admin-ns
      (assoc :doc "Export all books as JSON. Includes author names.")
      (update :id conj ::export-books)
      (update :tf conj
              ::export-books
              (fn [env]
                (let [books (mapv (fn [b]
                                    (let [author (get @db/authors (:author-id b))]
                                      (assoc b :author-name (:name author))))
                                  (vals @db/books))]
                  (update env :res assoc :body books))))))

(def notifications
  (-> admin-ns
      (assoc :doc "List recent system notifications (book added/deleted, etc.).")
      (update :id conj ::notifications)
      (update :with conj mw/query-params mw/keyword-params)
      (update :tf conj
              ::notifications
              (fn [env]
                (let [ctx (:ctx env)
                      params (:query-params ctx)
                      limit (try (Integer/parseInt (str (get params :limit "50")))
                                 (catch Exception _ 50))
                      recent (->> @db/notifications
                                  reverse
                                  (take limit)
                                  vec)]
                  (update env :res assoc :body recent))))))

(def seed-data
  (-> admin-ns
      (assoc :doc "Re-seed the database with sample data. Destructive operation.")
      (update :id conj ::seed-data)
      (update :tf conj
              ::seed-data
              (fn [env]
                (db/seed!)
                (update env :res assoc
                        :body {:message "Database re-seeded"
                               :counts {:books (count @db/books)
                                        :authors (count @db/authors)
                                        :reviews (count @db/reviews)
                                        :users (count @db/users)}})))))

(def bulk-delete-reviews
  (-> admin-ns
      (assoc :doc "Bulk delete reviews by list of IDs. Returns count of deleted reviews.")
      (update :id conj ::bulk-delete-reviews)
      (update :with conj mw/body-params mw/keyword-params)
      (update :tf conj
              ::bulk-delete-reviews
              (fn [env]
                (let [ctx (:ctx env)
                      params (:body-params ctx)
                      ids (:ids params [])
                      deleted (atom 0)]
                  (doseq [id ids]
                    (when (get @db/reviews id)
                      (swap! db/reviews dissoc id)
                      (swap! deleted inc)))
                  (update env :res assoc
                          :body {:message (str "Deleted " @deleted " reviews")
                                 :deleted @deleted}))))))

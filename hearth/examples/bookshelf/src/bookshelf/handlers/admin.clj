(ns bookshelf.handlers.admin
  "Admin handlers — stats, exports, bulk operations.
   All handlers extend the admin-ns base transformer, which requires
   admin authentication for ALL admin handlers."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Namespace transformer ---

(def admin-ns
  "Base transformer for all admin handlers.
   Requires admin auth and JSON response for every admin endpoint."
  (-> t/transformer
      (update :id conj ::admin)
      (update :with into [app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin)
                          mw/json-body-response])))

;; --- Read handlers ---

(def dashboard
  (-> admin-ns
      (update :id conj ::dashboard)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)])
      (update :tf conj
              ::dashboard
              (fn [env]
                (let [all-books (vals @db/books)
                      all-reviews (vals @db/reviews)
                      all-users (vals @db/users)]
                  (update env :res assoc
                          :body {:stats {:total-books (count all-books)
                                         :total-authors (count @db/authors)
                                         :total-users (count all-users)
                                         :total-reviews (count all-reviews)
                                         :total-reading-lists (count @db/reading-lists)
                                         :books-in-stock (count (filter :in-stock all-books))
                                         :average-book-price (when (seq all-books)
                                                               (/ (reduce + (map :price all-books))
                                                                  (double (count all-books))))
                                         :average-rating (when (seq all-reviews)
                                                           (/ (reduce + (map :rating all-reviews))
                                                              (double (count all-reviews))))}
                                 :recent-notifications (take 10 (reverse @db/notifications))
                                 :request-log-size (count @app-mw/request-log)}))))))

(def export-catalog
  (-> admin-ns
      (update :id conj ::export-catalog)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)])
      (update :tf conj
              ::export-catalog
              (fn [env]
                (let [catalog (->> (vals @db/books)
                                   (map (fn [b]
                                          (let [author (get @db/authors (:author-id b))
                                                reviews (db/find-reviews-for-book (:id b))]
                                            (assoc b
                                                   :author-name (:name author)
                                                   :review-count (count reviews)
                                                   :average-rating (when (seq reviews)
                                                                     (/ (reduce + (map :rating reviews))
                                                                        (double (count reviews))))))))
                                   (sort-by :title)
                                   vec)]
                  (update env :res assoc
                          :headers {"Content-Disposition" "attachment; filename=\"catalog.json\""}
                          :body {:exported-at (str (java.time.Instant/now))
                                 :count (count catalog)
                                 :books catalog}))))))

(def export-users
  (-> admin-ns
      (update :id conj ::export-users)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)])
      (update :tf conj
              ::export-users
              (fn [env]
                (let [users-list (->> (vals @db/users)
                                      (map #(dissoc % :password-hash))
                                      (sort-by :id)
                                      vec)]
                  (update env :res assoc
                          :headers {"Content-Disposition" "attachment; filename=\"users.json\""}
                          :body {:exported-at (str (java.time.Instant/now))
                                 :count (count users-list)
                                 :users users-list}))))))

;; --- Write handlers ---

(def bulk-update-prices
  (-> admin-ns
      (update :id conj ::bulk-update-prices)
      (update :with into [mw/body-params mw/keyword-params])
      (update :tf conj
              ::bulk-update-prices
              (fn [env]
                (let [params (:body-params env)
                      updates (:updates params [])
                      results (mapv (fn [{:keys [book-id price]}]
                                      (if-let [book (get @db/books book-id)]
                                        (do (swap! db/books assoc book-id (assoc book :price price))
                                            {:book-id book-id :status "updated" :new-price price})
                                        {:book-id book-id :status "not-found"}))
                                    updates)]
                  (update env :res assoc
                          :body {:updated (count (filter #(= "updated" (:status %)) results))
                                 :not-found (count (filter #(= "not-found" (:status %)) results))
                                 :results results}))))))

(def request-log
  (-> admin-ns
      (update :id conj ::request-log)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)])
      (update :tf conj
              ::request-log
              (fn [env]
                (let [pagination (:pagination env {:page 1 :per-page 50})
                      log (reverse @app-mw/request-log)
                      result (db/paginate log pagination)]
                  (update env :res assoc :body result))))))

(def clear-request-log
  (-> admin-ns
      (update :id conj ::clear-request-log)
      (update :tf conj
              ::clear-request-log
              (fn [env]
                (let [count-before (count @app-mw/request-log)]
                  (reset! app-mw/request-log [])
                  (update env :res assoc
                          :body {:message "Request log cleared"
                                 :entries-cleared count-before}))))))

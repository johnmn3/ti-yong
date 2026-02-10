(ns bookshelf.handlers.admin
  "Admin handlers — stats, exports, bulk operations.
   All handlers are transformers, composable with middleware after definition."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [ti-yong.alpha.transformer :as t]))

(def dashboard
  (-> t/transformer
      (update :id conj ::dashboard)
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
  (-> t/transformer
      (update :id conj ::export-catalog)
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
  (-> t/transformer
      (update :id conj ::export-users)
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

(def bulk-update-prices
  (-> t/transformer
      (update :id conj ::bulk-update-prices)
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
  (-> t/transformer
      (update :id conj ::request-log)
      (update :tf conj
              ::request-log
              (fn [env]
                (let [pagination (:pagination env {:page 1 :per-page 50})
                      log (reverse @app-mw/request-log)
                      result (db/paginate log pagination)]
                  (update env :res assoc :body result))))))

(def clear-request-log
  (-> t/transformer
      (update :id conj ::clear-request-log)
      (update :tf conj
              ::clear-request-log
              (fn [env]
                (let [count-before (count @app-mw/request-log)]
                  (reset! app-mw/request-log [])
                  (update env :res assoc
                          :body {:message "Request log cleared"
                                 :entries-cleared count-before}))))))

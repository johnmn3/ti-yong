(ns bookshelf.handlers.admin
  "Admin handlers — stats, exports, bulk operations."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]))

(defn dashboard
  "GET /api/admin/dashboard — admin dashboard with aggregate stats."
  [_env]
  (let [all-books (vals @db/books)
        all-reviews (vals @db/reviews)
        all-users (vals @db/users)]
    {:status 200
     :headers {}
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
            :request-log-size (count @app-mw/request-log)}}))

(defn export-catalog
  "GET /api/admin/export/catalog — export full book catalog as JSON."
  [_env]
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
    {:status 200
     :headers {"Content-Disposition" "attachment; filename=\"catalog.json\""}
     :body {:exported-at (str (java.time.Instant/now))
            :count (count catalog)
            :books catalog}}))

(defn export-users
  "GET /api/admin/export/users — export user list (admin only)."
  [_env]
  (let [users-list (->> (vals @db/users)
                        (map #(dissoc % :password-hash))
                        (sort-by :id)
                        vec)]
    {:status 200
     :headers {"Content-Disposition" "attachment; filename=\"users.json\""}
     :body {:exported-at (str (java.time.Instant/now))
            :count (count users-list)
            :users users-list}}))

(defn bulk-update-prices
  "POST /api/admin/bulk/prices — bulk update book prices.
   Body: {:updates [{:book-id 1 :price 14.99} ...]}"
  [env]
  (let [params (:body-params env)
        updates (:updates params [])
        results (mapv (fn [{:keys [book-id price]}]
                        (if-let [book (get @db/books book-id)]
                          (do (swap! db/books assoc book-id (assoc book :price price))
                              {:book-id book-id :status "updated" :new-price price})
                          {:book-id book-id :status "not-found"}))
                      updates)]
    {:status 200
     :headers {}
     :body {:updated (count (filter #(= "updated" (:status %)) results))
            :not-found (count (filter #(= "not-found" (:status %)) results))
            :results results}}))

(defn request-log
  "GET /api/admin/request-log — view recent request log entries."
  [env]
  (let [pagination (:pagination env {:page 1 :per-page 50})
        log (reverse @app-mw/request-log)
        result (db/paginate log pagination)]
    {:status 200
     :headers {}
     :body result}))

(defn clear-request-log
  "DELETE /api/admin/request-log — clear the request log."
  [_env]
  (let [count-before (count @app-mw/request-log)]
    (reset! app-mw/request-log [])
    {:status 200
     :headers {}
     :body {:message "Request log cleared"
            :entries-cleared count-before}}))

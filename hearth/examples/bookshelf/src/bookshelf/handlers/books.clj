(ns bookshelf.handlers.books
  "Book resource handlers — full CRUD with search, pagination, and stock management."
  (:require
   [bookshelf.db :as db]))

(defn list-books
  "GET /api/books — paginated book listing with optional filtering.
   Query params: page, per-page, genre, author, in-stock, min-price, max-price, sort"
  [env]
  (let [params (:query-params env)
        pagination (:pagination env {:page 1 :per-page 10})
        sort-field (get params :sort "title")
        sort-dir (get params :dir "asc")
        books (db/search-books params)
        sorted (cond->> books
                 (= sort-field "title") (sort-by :title)
                 (= sort-field "price") (sort-by :price)
                 (= sort-field "pages") (sort-by :pages)
                 (= sort-dir "desc") reverse)
        result (db/paginate sorted pagination)]
    {:status 200
     :headers {}
     :body result}))

(defn get-book
  "GET /api/books/:id — fetch a single book with author info."
  [env]
  (let [book (:book env)
        author (get @db/authors (:author-id book))
        reviews (db/find-reviews-for-book (:id book))
        avg-rating (when (seq reviews)
                     (/ (reduce + (map :rating reviews))
                        (double (count reviews))))]
    {:status 200
     :headers {}
     :body (assoc book
                   :author (select-keys author [:id :name :nationality])
                   :review-count (count reviews)
                   :average-rating avg-rating)}))

(defn create-book
  "POST /api/books — create a new book. Requires auth + admin/moderator role."
  [env]
  (let [params (:body-params env)
        id (db/next-id)
        book (assoc params :id id :in-stock true :stock-count 0)]
    (swap! db/books assoc id book)
    (db/add-notification! {:type :book-added
                           :book-id id
                           :title (:title book)
                           :by (get-in env [:current-user :username])})
    {:status 201
     :headers {"Location" (str "/api/books/" id)}
     :body book}))

(defn update-book
  "PUT /api/books/:id — full update of a book. Requires auth + admin/moderator."
  [env]
  (let [book (:book env)
        params (:body-params env)
        updated (merge book params {:id (:id book)})]
    (swap! db/books assoc (:id book) updated)
    {:status 200
     :headers {}
     :body updated}))

(defn patch-book
  "PATCH /api/books/:id — partial update of a book (e.g., stock, price)."
  [env]
  (let [book (:book env)
        params (:body-params env)
        ;; Only allow certain fields to be patched
        allowed-keys [:price :in-stock :stock-count :summary :cover-url]
        updates (select-keys params allowed-keys)
        updated (merge book updates)]
    (swap! db/books assoc (:id book) updated)
    {:status 200
     :headers {}
     :body updated}))

(defn delete-book
  "DELETE /api/books/:id — remove a book. Requires admin role."
  [env]
  (let [book (:book env)]
    (swap! db/books dissoc (:id book))
    ;; Also remove associated reviews
    (swap! db/reviews (fn [revs]
                        (into {} (remove (fn [[_ r]] (= (:id book) (:book-id r))) revs))))
    (db/add-notification! {:type :book-deleted
                           :book-id (:id book)
                           :title (:title book)
                           :by (get-in env [:current-user :username])})
    {:status 200
     :headers {}
     :body {:message (str "Deleted \"" (:title book) "\"")
            :id (:id book)}}))

(defn search-books
  "GET /api/books/search — search books with query params.
   Query params: q (full text), genre, author, min-price, max-price, in-stock"
  [env]
  (let [params (:query-params env)
        pagination (:pagination env {:page 1 :per-page 10})
        results (db/search-books params)
        paged (db/paginate results pagination)]
    {:status 200
     :headers {}
     :body paged}))

(defn book-stock
  "GET /api/books/:id/stock — check stock status."
  [env]
  (let [book (:book env)]
    {:status 200
     :headers {}
     :body {:id (:id book)
            :title (:title book)
            :in-stock (:in-stock book)
            :stock-count (:stock-count book)}}))

(defn update-stock
  "POST /api/books/:id/stock — update stock count (admin only)."
  [env]
  (let [book (:book env)
        params (:body-params env)
        new-count (:stock-count params 0)
        updated (assoc book :stock-count new-count
                            :in-stock (pos? new-count))]
    (swap! db/books assoc (:id book) updated)
    {:status 200
     :headers {}
     :body {:id (:id book)
            :stock-count new-count
            :in-stock (pos? new-count)}}))

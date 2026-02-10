(ns bookshelf.handlers.books
  "Book resource handlers — full CRUD with search, pagination, and stock management.
   All handlers extend the books-ns base transformer, which provides JSON response
   serialization. Per-handler middleware (auth, loaders, pagination) is declared
   directly on each handler via :with."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Entity loader ---

(def ^:private load-book
  (app-mw/load-entity db/books :book "Book"))

;; --- Namespace transformer: shared middleware for all book handlers ---

(def books-ns
  "Base transformer for all book handlers. JSON response serialization."
  (-> t/transformer
      (update :id conj ::books)
      (update :with into [mw/json-body-response])))

;; --- Read handlers ---

(def list-books
  (-> books-ns
      (update :id conj ::list-books)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)
                          (app-mw/cache-control "public, max-age=60")])
      (update :tf conj
              ::list-books
              (fn [env]
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
                  (update env :res assoc :body result))))))

(def get-book
  (-> books-ns
      (update :id conj ::get-book)
      (update :with into [load-book
                          (app-mw/cache-control "public, max-age=300")])
      (update :tf conj
              ::get-book
              (fn [env]
                (let [book (:book env)
                      author (get @db/authors (:author-id book))
                      reviews (db/find-reviews-for-book (:id book))
                      avg-rating (when (seq reviews)
                                   (/ (reduce + (map :rating reviews))
                                      (double (count reviews))))]
                  (update env :res assoc
                          :body (assoc book
                                       :author (select-keys author [:id :name :nationality])
                                       :review-count (count reviews)
                                       :average-rating avg-rating)))))))

(def search-books
  (-> books-ns
      (update :id conj ::search-books)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)])
      (update :tf conj
              ::search-books
              (fn [env]
                (let [params (:query-params env)
                      pagination (:pagination env {:page 1 :per-page 10})
                      results (db/search-books params)
                      paged (db/paginate results pagination)]
                  (update env :res assoc :body paged))))))

(def book-stock
  (-> books-ns
      (update :id conj ::book-stock)
      (update :with conj load-book)
      (update :tf conj
              ::book-stock
              (fn [env]
                (let [book (:book env)]
                  (update env :res assoc
                          :body {:id (:id book)
                                 :title (:title book)
                                 :in-stock (:in-stock book)
                                 :stock-count (:stock-count book)}))))))

;; --- Write handlers (require auth) ---

(def create-book
  (-> books-ns
      (update :id conj ::create-book)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin :moderator)
                          app-mw/require-json])
      (update :tf conj
              ::create-book
              (fn [env]
                (let [params (:body-params env)
                      id (db/next-id)
                      book (assoc params :id id :in-stock true :stock-count 0)]
                  (swap! db/books assoc id book)
                  (db/add-notification! {:type :book-added
                                         :book-id id
                                         :title (:title book)
                                         :by (get-in env [:current-user :username])})
                  (update env :res assoc
                          :status 201
                          :headers {"Location" (str "/api/books/" id)}
                          :body book))))))

(def update-book
  (-> books-ns
      (update :id conj ::update-book)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin :moderator)
                          load-book app-mw/require-json])
      (update :tf conj
              ::update-book
              (fn [env]
                (let [book (:book env)
                      params (:body-params env)
                      updated (merge book params {:id (:id book)})]
                  (swap! db/books assoc (:id book) updated)
                  (update env :res assoc :body updated))))))

(def patch-book
  (-> books-ns
      (update :id conj ::patch-book)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          load-book])
      (update :tf conj
              ::patch-book
              (fn [env]
                (let [book (:book env)
                      params (:body-params env)
                      allowed-keys [:price :in-stock :stock-count :summary :cover-url]
                      updates (select-keys params allowed-keys)
                      updated (merge book updates)]
                  (swap! db/books assoc (:id book) updated)
                  (update env :res assoc :body updated))))))

(def delete-book
  (-> books-ns
      (update :id conj ::delete-book)
      (update :with into [app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin)
                          load-book])
      (update :tf conj
              ::delete-book
              (fn [env]
                (let [book (:book env)]
                  (swap! db/books dissoc (:id book))
                  (swap! db/reviews (fn [revs]
                                      (into {} (remove (fn [[_ r]] (= (:id book) (:book-id r))) revs))))
                  (db/add-notification! {:type :book-deleted
                                         :book-id (:id book)
                                         :title (:title book)
                                         :by (get-in env [:current-user :username])})
                  (update env :res assoc
                          :body {:message (str "Deleted \"" (:title book) "\"")
                                 :id (:id book)}))))))

(def update-stock
  (-> books-ns
      (update :id conj ::update-stock)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin)
                          load-book])
      (update :tf conj
              ::update-stock
              (fn [env]
                (let [book (:book env)
                      params (:body-params env)
                      new-count (:stock-count params 0)
                      updated (assoc book :stock-count new-count
                                          :in-stock (pos? new-count))]
                  (swap! db/books assoc (:id book) updated)
                  (update env :res assoc
                          :body {:id (:id book)
                                 :stock-count new-count
                                 :in-stock (pos? new-count)}))))))

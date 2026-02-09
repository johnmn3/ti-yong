(ns bookshelf.handlers.authors
  "Author resource handlers — CRUD and bibliography."
  (:require
   [bookshelf.db :as db]))

(defn list-authors
  "GET /api/authors — list all authors with book counts."
  [env]
  (let [pagination (:pagination env {:page 1 :per-page 20})
        authors-list (->> (vals @db/authors)
                          (map (fn [a]
                                 (assoc a :book-count
                                        (count (db/find-books-by-author (:id a))))))
                          (sort-by :name))
        result (db/paginate authors-list pagination)]
    {:status 200
     :headers {}
     :body result}))

(defn get-author
  "GET /api/authors/:id — fetch author with full bibliography."
  [env]
  (let [author (:author env)
        books (db/find-books-by-author (:id author))
        book-summaries (mapv #(select-keys % [:id :title :published :genres :price]) books)]
    {:status 200
     :headers {}
     :body (assoc author :books book-summaries)}))

(defn create-author
  "POST /api/authors — create a new author record."
  [env]
  (let [params (:body-params env)
        id (db/next-id)
        author (assoc params :id id)]
    (swap! db/authors assoc id author)
    {:status 201
     :headers {"Location" (str "/api/authors/" id)}
     :body author}))

(defn update-author
  "PUT /api/authors/:id — update author info."
  [env]
  (let [author (:author env)
        params (:body-params env)
        updated (merge author params {:id (:id author)})]
    (swap! db/authors assoc (:id author) updated)
    {:status 200
     :headers {}
     :body updated}))

(defn delete-author
  "DELETE /api/authors/:id — delete an author (and optionally their books)."
  [env]
  (let [author (:author env)
        books (db/find-books-by-author (:id author))]
    (swap! db/authors dissoc (:id author))
    ;; Remove orphaned books
    (doseq [b books]
      (swap! db/books dissoc (:id b)))
    {:status 200
     :headers {}
     :body {:message (str "Deleted " (:name author) " and " (count books) " books")}}))

(defn author-books
  "GET /api/authors/:id/books — list books by a specific author."
  [env]
  (let [author (:author env)
        books (db/find-books-by-author (:id author))
        sorted (sort-by :published books)]
    {:status 200
     :headers {}
     :body {:author (select-keys author [:id :name])
            :books (vec sorted)
            :count (count books)}}))

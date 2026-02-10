(ns bookshelf.handlers.authors
  "Author resource handlers — CRUD and bibliography.
   All handlers are transformers, composable with middleware after definition."
  (:require
   [bookshelf.db :as db]
   [ti-yong.alpha.transformer :as t]))

(def list-authors
  (-> t/transformer
      (update :id conj ::list-authors)
      (update :tf conj
              ::list-authors
              (fn [env]
                (let [pagination (:pagination env {:page 1 :per-page 20})
                      authors-list (->> (vals @db/authors)
                                        (map (fn [a]
                                               (assoc a :book-count
                                                      (count (db/find-books-by-author (:id a))))))
                                        (sort-by :name))
                      result (db/paginate authors-list pagination)]
                  (update env :res assoc :body result))))))

(def get-author
  (-> t/transformer
      (update :id conj ::get-author)
      (update :tf conj
              ::get-author
              (fn [env]
                (let [author (:author env)
                      books (db/find-books-by-author (:id author))
                      book-summaries (mapv #(select-keys % [:id :title :published :genres :price]) books)]
                  (update env :res assoc
                          :body (assoc author :books book-summaries)))))))

(def create-author
  (-> t/transformer
      (update :id conj ::create-author)
      (update :tf conj
              ::create-author
              (fn [env]
                (let [params (:body-params env)
                      id (db/next-id)
                      author (assoc params :id id)]
                  (swap! db/authors assoc id author)
                  (update env :res assoc
                          :status 201
                          :headers {"Location" (str "/api/authors/" id)}
                          :body author))))))

(def update-author
  (-> t/transformer
      (update :id conj ::update-author)
      (update :tf conj
              ::update-author
              (fn [env]
                (let [author (:author env)
                      params (:body-params env)
                      updated (merge author params {:id (:id author)})]
                  (swap! db/authors assoc (:id author) updated)
                  (update env :res assoc :body updated))))))

(def delete-author
  (-> t/transformer
      (update :id conj ::delete-author)
      (update :tf conj
              ::delete-author
              (fn [env]
                (let [author (:author env)
                      books (db/find-books-by-author (:id author))]
                  (swap! db/authors dissoc (:id author))
                  (doseq [b books]
                    (swap! db/books dissoc (:id b)))
                  (update env :res assoc
                          :body {:message (str "Deleted " (:name author) " and " (count books) " books")}))))))

(def author-books
  (-> t/transformer
      (update :id conj ::author-books)
      (update :tf conj
              ::author-books
              (fn [env]
                (let [author (:author env)
                      books (db/find-books-by-author (:id author))
                      sorted (sort-by :published books)]
                  (update env :res assoc
                          :body {:author (select-keys author [:id :name])
                                 :books (vec sorted)
                                 :count (count books)}))))))

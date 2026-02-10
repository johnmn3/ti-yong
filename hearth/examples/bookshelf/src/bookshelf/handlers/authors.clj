(ns bookshelf.handlers.authors
  "Author resource handlers — CRUD and bibliography.
   All handlers extend the authors-ns base transformer."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Entity loader ---

(def ^:private load-author
  (app-mw/load-entity db/authors :author "Author"))

;; --- Namespace transformer ---

(def authors-ns
  "Base transformer for all author handlers. JSON response serialization."
  (-> t/transformer
      (update :id conj ::authors)
      (update :with into [mw/json-body-response])))

;; --- Read handlers ---

(def list-authors
  (-> authors-ns
      (update :id conj ::list-authors)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)
                          (app-mw/cache-control "public, max-age=300")])
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
  (-> authors-ns
      (update :id conj ::get-author)
      (update :with conj load-author)
      (update :tf conj
              ::get-author
              (fn [env]
                (let [author (:author env)
                      books (db/find-books-by-author (:id author))
                      book-summaries (mapv #(select-keys % [:id :title :published :genres :price]) books)]
                  (update env :res assoc
                          :body (assoc author :books book-summaries)))))))

(def author-books
  (-> authors-ns
      (update :id conj ::author-books)
      (update :with conj load-author)
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

;; --- Write handlers (require auth) ---

(def create-author
  (-> authors-ns
      (update :id conj ::create-author)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin)
                          app-mw/require-json])
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
  (-> authors-ns
      (update :id conj ::update-author)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin)
                          load-author app-mw/require-json])
      (update :tf conj
              ::update-author
              (fn [env]
                (let [author (:author env)
                      params (:body-params env)
                      updated (merge author params {:id (:id author)})]
                  (swap! db/authors assoc (:id author) updated)
                  (update env :res assoc :body updated))))))

(def delete-author
  (-> authors-ns
      (update :id conj ::delete-author)
      (update :with into [app-mw/authenticate app-mw/require-auth
                          (app-mw/require-role :admin)
                          load-author])
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

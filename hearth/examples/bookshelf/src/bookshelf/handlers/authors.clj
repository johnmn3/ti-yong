(ns bookshelf.handlers.authors
  "Author resource handlers — CRUD and bibliography.
   All handlers extend the authors-ns base transformer.
   All request data lives on :ctx."
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
      (update :with conj mw/json-body-response)))

;; --- Read handlers ---

(def list-authors
  (-> authors-ns
      (assoc :doc "List all authors with book counts and nationality.")
      (update :id conj ::list-authors)
      (update :with conj (app-mw/cache-control "public, max-age=300"))
      (update :tf conj
              ::list-authors
              (fn [env]
                (let [authors (mapv (fn [a]
                                     (assoc a :book-count
                                            (count (db/find-books-by-author (:id a)))))
                                   (vals @db/authors))]
                  (update env :res assoc :body authors))))))

(def get-author
  (-> authors-ns
      (assoc :doc "Get author by ID with full bibliography of books.")
      (update :id conj ::get-author)
      (update :with conj load-author
              (app-mw/cache-control "public, max-age=300"))
      (update :tf conj
              ::get-author
              (fn [env]
                (let [author (get-in env [:ctx :author])
                      books (db/find-books-by-author (:id author))]
                  (update env :res assoc
                          :body (assoc author :books (vec books))))))))

(def author-books
  (-> authors-ns
      (assoc :doc "List all books by a specific author.")
      (update :id conj ::author-books)
      (update :with conj load-author)
      (update :tf conj
              ::author-books
              (fn [env]
                (let [author (get-in env [:ctx :author])
                      books (db/find-books-by-author (:id author))]
                  (update env :res assoc :body (vec books)))))))

;; --- Write handlers ---

(def create-author
  (-> authors-ns
      (assoc :doc "Create a new author. Requires admin or moderator role. Returns 201.")
      (update :id conj ::create-author)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth
              (app-mw/require-role :admin :moderator))
      (update :tf conj
              ::create-author
              (fn [env]
                (let [ctx (:ctx env)
                      params (:body-params ctx)
                      id (db/next-id)
                      author (assoc params :id id)]
                  (swap! db/authors assoc id author)
                  (update env :res assoc
                          :status 201
                          :headers {"Location" (str "/api/authors/" id)}
                          :body author))))))

(def update-author
  (-> authors-ns
      (assoc :doc "Full update of an author by ID. Requires admin or moderator role.")
      (update :id conj ::update-author)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth
              (app-mw/require-role :admin :moderator)
              load-author)
      (update :tf conj
              ::update-author
              (fn [env]
                (let [ctx (:ctx env)
                      author (:author ctx)
                      params (:body-params ctx)
                      updated (merge author params {:id (:id author)})]
                  (swap! db/authors assoc (:id author) updated)
                  (update env :res assoc :body updated))))))

(def delete-author
  (-> authors-ns
      (assoc :doc "Delete an author and their books. Requires admin role.")
      (update :id conj ::delete-author)
      (update :with conj app-mw/authenticate app-mw/require-auth
              (app-mw/require-role :admin)
              load-author)
      (update :tf conj
              ::delete-author
              (fn [env]
                (let [author (get-in env [:ctx :author])]
                  ;; Remove the author's books first
                  (let [author-books (db/find-books-by-author (:id author))]
                    (doseq [b author-books]
                      (swap! db/books dissoc (:id b))
                      (swap! db/reviews (fn [revs]
                                          (into {} (remove (fn [[_ r]] (= (:id b) (:book-id r))) revs))))))
                  (swap! db/authors dissoc (:id author))
                  (update env :res assoc
                          :body {:message (str "Deleted author \"" (:name author) "\" and their books")
                                 :id (:id author)}))))))

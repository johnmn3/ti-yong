(ns bookshelf.handlers.reading-lists
  "Reading list handlers — curated book lists per user.
   All handlers extend the reading-lists-ns base transformer.
   All request data lives on :ctx."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Entity loader ---

(def ^:private load-list
  (app-mw/load-entity db/reading-lists :reading-list "Reading list"))

;; --- Namespace transformer ---

(def reading-lists-ns
  "Base transformer for all reading list handlers. JSON response serialization."
  (-> t/transformer
      (update :id conj ::reading-lists)
      (update :with conj mw/json-body-response)))

;; --- Read handlers ---

(def get-reading-list
  (-> reading-lists-ns
      (assoc :doc "Get a reading list by ID with expanded book details. 404 if private and not owner.")
      (update :id conj ::get-reading-list)
      (update :with conj load-list
              app-mw/authenticate)
      (update :tf conj
              ::get-reading-list
              (fn [env]
                (let [ctx (:ctx env)
                      rl (:reading-list ctx)
                      viewer (:current-user ctx)]
                  (if (and (not (:public? rl))
                           (or (nil? viewer) (not= (:id viewer) (:user-id rl))))
                    (update env :res assoc
                            :status 404
                            :body {:error "Reading list not found"})
                    (let [books (mapv #(get @db/books %) (:book-ids rl))
                          owner (get @db/users (:user-id rl))]
                      (update env :res assoc
                              :body (assoc rl
                                           :books (filterv some? books)
                                           :owner (:username owner))))))))))

(def list-public-reading-lists
  (-> reading-lists-ns
      (assoc :doc "List all public reading lists with owner usernames.")
      (update :id conj ::list-public-reading-lists)
      (update :with conj (app-mw/cache-control "public, max-age=120"))
      (update :tf conj
              ::list-public-reading-lists
              (fn [env]
                (let [lists (->> (vals @db/reading-lists)
                                 (filter :public?)
                                 (mapv (fn [rl]
                                         (let [owner (get @db/users (:user-id rl))]
                                           (assoc rl
                                                  :owner (:username owner)
                                                  :book-count (count (:book-ids rl)))))))]
                  (update env :res assoc :body lists))))))

;; --- Write handlers ---

(def create-reading-list
  (-> reading-lists-ns
      (assoc :doc "Create a new reading list. Requires authentication. Returns 201.")
      (update :id conj ::create-reading-list)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth)
      (update :tf conj
              ::create-reading-list
              (fn [env]
                (let [ctx (:ctx env)
                      user (:current-user ctx)
                      params (:body-params ctx)
                      id (db/next-id)
                      rl {:id id
                          :user-id (:id user)
                          :name (or (:name params) "Untitled")
                          :public? (boolean (:public params true))
                          :book-ids (vec (or (:book-ids params) []))
                          :created-at (str (java.time.Instant/now))}]
                  (swap! db/reading-lists assoc id rl)
                  (update env :res assoc
                          :status 201
                          :headers {"Location" (str "/api/reading-lists/" id)}
                          :body rl))))))

(def update-reading-list
  (-> reading-lists-ns
      (assoc :doc "Update a reading list. Must be the owner.")
      (update :id conj ::update-reading-list)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth
              load-list)
      (update :tf conj
              ::update-reading-list
              (fn [env]
                (let [ctx (:ctx env)
                      rl (:reading-list ctx)
                      user (:current-user ctx)
                      params (:body-params ctx)]
                  (if (not= (:user-id rl) (:id user))
                    (update env :res assoc
                            :status 403
                            :body {:error "Cannot modify another user's reading list"})
                    (let [updated (merge rl (select-keys params [:name :public :book-ids])
                                         {:public? (boolean (get params :public (:public? rl)))})]
                      (swap! db/reading-lists assoc (:id rl) updated)
                      (update env :res assoc :body updated))))))))

(def delete-reading-list
  (-> reading-lists-ns
      (assoc :doc "Delete a reading list. Must be the owner or admin.")
      (update :id conj ::delete-reading-list)
      (update :with conj app-mw/authenticate app-mw/require-auth
              load-list)
      (update :tf conj
              ::delete-reading-list
              (fn [env]
                (let [ctx (:ctx env)
                      rl (:reading-list ctx)
                      user (:current-user ctx)]
                  (if (and (not= (:user-id rl) (:id user))
                           (not= :admin (:role user)))
                    (update env :res assoc
                            :status 403
                            :body {:error "Cannot delete another user's reading list"})
                    (do
                      (swap! db/reading-lists dissoc (:id rl))
                      (update env :res assoc
                              :body {:message "Reading list deleted"
                                     :id (:id rl)}))))))))

(def add-book-to-list
  (-> reading-lists-ns
      (assoc :doc "Add a book to a reading list. Must be the owner. Returns 409 if already in list.")
      (update :id conj ::add-book-to-list)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth
              load-list)
      (update :tf conj
              ::add-book-to-list
              (fn [env]
                (let [ctx (:ctx env)
                      rl (:reading-list ctx)
                      user (:current-user ctx)
                      params (:body-params ctx)
                      book-id (:book-id params)]
                  (cond
                    (not= (:user-id rl) (:id user))
                    (update env :res assoc
                            :status 403
                            :body {:error "Cannot modify another user's reading list"})

                    (nil? (get @db/books book-id))
                    (update env :res assoc
                            :status 404
                            :body {:error "Book not found"})

                    (some #{book-id} (:book-ids rl))
                    (update env :res assoc
                            :status 409
                            :body {:error "Book already in list"})

                    :else
                    (let [updated (update rl :book-ids conj book-id)]
                      (swap! db/reading-lists assoc (:id rl) updated)
                      (update env :res assoc :body updated))))))))

(def remove-book-from-list
  (-> reading-lists-ns
      (assoc :doc "Remove a book from a reading list by book-id path param. Must be the owner.")
      (update :id conj ::remove-book-from-list)
      (update :with conj app-mw/authenticate app-mw/require-auth
              load-list)
      (update :tf conj
              ::remove-book-from-list
              (fn [env]
                (let [ctx (:ctx env)
                      rl (:reading-list ctx)
                      user (:current-user ctx)
                      book-id-str (get-in ctx [:path-params-values "book-id"])
                      book-id (when book-id-str
                                (try (Integer/parseInt book-id-str)
                                     (catch Exception _ nil)))]
                  (if (not= (:user-id rl) (:id user))
                    (update env :res assoc
                            :status 403
                            :body {:error "Cannot modify another user's reading list"})
                    (let [updated (update rl :book-ids #(vec (remove #{book-id} %)))]
                      (swap! db/reading-lists assoc (:id rl) updated)
                      (update env :res assoc :body updated))))))))

(ns bookshelf.routes
  "Route definitions for the BookShelf API.
   Demonstrates extensive use of per-route middleware (transformers)
   with different middleware stacks for different route groups."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [bookshelf.handlers.auth :as auth]
   [bookshelf.handlers.books :as books]
   [bookshelf.handlers.authors :as authors]
   [bookshelf.handlers.reviews :as reviews]
   [bookshelf.handlers.users :as users]
   [bookshelf.handlers.reading-lists :as rl]
   [bookshelf.handlers.admin :as admin]
   [bookshelf.handlers.pages :as pages]
   [bookshelf.handlers.realtime :as realtime]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.websocket :as ws]))

;; --- Middleware stacks for different route groups ---
;; This demonstrates composing different transformer pipelines per route.

(def ^:private json-api
  "Standard JSON API middleware: parses body, keywordizes params, returns JSON."
  [mw/body-params
   mw/keyword-params
   mw/json-body-response])

(def ^:private json-read
  "Read-only JSON API: parses query params, keywordizes, returns JSON."
  [mw/query-params
   mw/keyword-params
   mw/json-body-response])

(def ^:private paginated-read
  "Paginated read: adds pagination params on top of json-read."
  [mw/query-params
   mw/keyword-params
   (app-mw/pagination-params)
   mw/json-body-response])

(def ^:private auth-read
  "Authenticated read: adds auth check to paginated-read."
  [mw/query-params
   mw/keyword-params
   (app-mw/authenticate)
   (app-mw/require-auth)
   mw/json-body-response])

(def ^:private auth-write
  "Authenticated write: JSON body parsing + auth."
  [mw/body-params
   mw/keyword-params
   (app-mw/authenticate)
   (app-mw/require-auth)
   mw/json-body-response])

(def ^:private admin-write
  "Admin write: auth + admin role required."
  [mw/body-params
   mw/keyword-params
   (app-mw/authenticate)
   (app-mw/require-auth)
   (app-mw/require-role :admin)
   mw/json-body-response])

(def ^:private admin-read
  "Admin read: auth + admin role required."
  [mw/query-params
   mw/keyword-params
   (app-mw/authenticate)
   (app-mw/require-auth)
   (app-mw/require-role :admin)
   (app-mw/pagination-params)
   mw/json-body-response])

(def ^:private moderator-write
  "Moderator write: auth + admin or moderator role."
  [mw/body-params
   mw/keyword-params
   (app-mw/authenticate)
   (app-mw/require-auth)
   (app-mw/require-role :admin :moderator)
   mw/json-body-response])

(defn- load-book
  "Route middleware that loads a book by :id path param."
  []
  (app-mw/load-entity db/books :book "Book"))

(defn- load-author
  "Route middleware that loads an author by :id path param."
  []
  (app-mw/load-entity db/authors :author "Author"))

(defn- load-review
  "Route middleware that loads a review by :id path param."
  []
  (app-mw/load-entity db/reviews :review "Review"))

(defn- load-target-user
  "Route middleware that loads a target user by :id path param."
  []
  (app-mw/load-entity db/users :target-user "User"))

(defn- load-reading-list
  "Route middleware that loads a reading list by :id path param."
  []
  (app-mw/load-entity db/reading-lists :reading-list "Reading list"))

(def routes
  "All BookShelf routes. Each route demonstrates specific middleware composition.

   Route format: [path method handler & {:keys [route-name with]}]

   The :with key specifies per-route transformer middleware that gets composed
   with the global middleware stack. This is the hearth equivalent of Pedestal's
   per-route interceptor chains."
  [;; === Pages (HTML) ===
   ["/" :get pages/home-page
    :route-name ::home]

   ["/health" :get pages/health
    :route-name ::health
    :with [mw/json-body-response]]

   ["/api" :get pages/api-info
    :route-name ::api-info
    :with [mw/json-body-response]]

   ;; === Books (full CRUD with varied middleware per route) ===

   ["/api/books" :get books/list-books
    :route-name ::list-books
    :with [mw/query-params mw/keyword-params
           (app-mw/pagination-params)
           (app-mw/cache-control "public, max-age=60")
           mw/json-body-response]]

   ["/api/books/search" :get books/search-books
    :route-name ::search-books
    :with [mw/query-params mw/keyword-params
           (app-mw/pagination-params)
           mw/json-body-response]]

   ["/api/books/:id" :get books/get-book
    :route-name ::get-book
    :with [(load-book)
           (app-mw/cache-control "public, max-age=300")
           mw/json-body-response]]

   ["/api/books" :post books/create-book
    :route-name ::create-book
    :with (into moderator-write [(app-mw/require-json)])]

   ["/api/books/:id" :put books/update-book
    :route-name ::update-book
    :with (into moderator-write [(load-book) (app-mw/require-json)])]

   ["/api/books/:id" :patch books/patch-book
    :route-name ::patch-book
    :with (conj auth-write (load-book))]

   ["/api/books/:id" :delete books/delete-book
    :route-name ::delete-book
    :with [(app-mw/authenticate) (app-mw/require-auth) (app-mw/require-role :admin)
           (load-book) mw/json-body-response]]

   ["/api/books/:id/stock" :get books/book-stock
    :route-name ::book-stock
    :with [(load-book) mw/json-body-response]]

   ["/api/books/:id/stock" :post books/update-stock
    :route-name ::update-stock
    :with (into admin-write [(load-book)])]

   ;; === Book reviews (nested under books) ===

   ["/api/books/:id/reviews" :get reviews/list-reviews
    :route-name ::book-reviews
    :with [(load-book) mw/json-body-response]]

   ["/api/books/:id/reviews" :post reviews/create-review
    :route-name ::create-review
    :with [(load-book)
           mw/body-params mw/keyword-params
           (app-mw/authenticate) (app-mw/require-auth)
           mw/json-body-response]]

   ;; === Reviews (direct access) ===

   ["/api/reviews/:id" :get reviews/get-review
    :route-name ::get-review
    :with [(load-review) mw/json-body-response]]

   ["/api/reviews/:id" :put reviews/update-review
    :route-name ::update-review
    :with (into auth-write [(load-review)])]

   ["/api/reviews/:id" :delete reviews/delete-review
    :route-name ::delete-review
    :with [(app-mw/authenticate) (app-mw/require-auth)
           (load-review) mw/json-body-response]]

   ;; === Authors ===

   ["/api/authors" :get authors/list-authors
    :route-name ::list-authors
    :with [mw/query-params mw/keyword-params
           (app-mw/pagination-params)
           (app-mw/cache-control "public, max-age=300")
           mw/json-body-response]]

   ["/api/authors/:id" :get authors/get-author
    :route-name ::get-author
    :with [(load-author) mw/json-body-response]]

   ["/api/authors" :post authors/create-author
    :route-name ::create-author
    :with (into admin-write [(app-mw/require-json)])]

   ["/api/authors/:id" :put authors/update-author
    :route-name ::update-author
    :with (into admin-write [(load-author) (app-mw/require-json)])]

   ["/api/authors/:id" :delete authors/delete-author
    :route-name ::delete-author
    :with [(app-mw/authenticate) (app-mw/require-auth)
           (app-mw/require-role :admin) (load-author) mw/json-body-response]]

   ["/api/authors/:id/books" :get authors/author-books
    :route-name ::author-books
    :with [(load-author) mw/json-body-response]]

   ;; === Users ===

   ["/api/users" :get users/list-users
    :route-name ::list-users
    :with admin-read]

   ["/api/users/:id" :get users/get-user
    :route-name ::get-user
    :with [(load-target-user) mw/json-body-response]]

   ["/api/users/:id/reviews" :get reviews/user-reviews
    :route-name ::user-reviews
    :with [(load-target-user) mw/json-body-response]]

   ["/api/profile" :get users/get-profile
    :route-name ::get-profile
    :with auth-read]

   ["/api/profile" :put users/update-profile
    :route-name ::update-profile
    :with auth-write]

   ;; === Auth ===

   ["/api/auth/login" :post auth/login
    :route-name ::login
    :with [mw/body-params mw/keyword-params
           mw/cookies (mw/session) mw/json-body-response]]

   ["/api/auth/logout" :post auth/logout
    :route-name ::logout
    :with [mw/cookies (mw/session) mw/json-body-response]]

   ["/api/auth/register" :post auth/register
    :route-name ::register
    :with [mw/body-params mw/keyword-params
           mw/cookies (mw/session) mw/json-body-response]]

   ["/api/auth/whoami" :get auth/whoami
    :route-name ::whoami
    :with [mw/cookies (mw/session)
           (app-mw/authenticate) mw/json-body-response]]

   ;; === Reading Lists ===

   ["/api/reading-lists" :get rl/list-public-lists
    :route-name ::list-reading-lists
    :with paginated-read]

   ["/api/reading-lists/:id" :get rl/get-reading-list
    :route-name ::get-reading-list
    :with [mw/cookies (mw/session) (app-mw/authenticate)
           (load-reading-list) mw/json-body-response]]

   ["/api/reading-lists" :post rl/create-reading-list
    :route-name ::create-reading-list
    :with auth-write]

   ["/api/reading-lists/:id" :put rl/update-reading-list
    :route-name ::update-reading-list
    :with (into auth-write [(load-reading-list)])]

   ["/api/reading-lists/:id" :delete rl/delete-reading-list
    :route-name ::delete-reading-list
    :with [(app-mw/authenticate) (app-mw/require-auth)
           (load-reading-list) mw/json-body-response]]

   ["/api/reading-lists/:id/books" :post rl/add-book-to-list
    :route-name ::add-book-to-list
    :with (into auth-write [(load-reading-list)])]

   ["/api/reading-lists/:id/books/:book_id" :delete rl/remove-book-from-list
    :route-name ::remove-book-from-list
    :with [(app-mw/authenticate) (app-mw/require-auth)
           (load-reading-list) mw/json-body-response]]

   ;; === Admin ===

   ["/api/admin/dashboard" :get admin/dashboard
    :route-name ::admin-dashboard
    :with admin-read]

   ["/api/admin/export/catalog" :get admin/export-catalog
    :route-name ::export-catalog
    :with admin-read]

   ["/api/admin/export/users" :get admin/export-users
    :route-name ::export-users
    :with admin-read]

   ["/api/admin/bulk/prices" :post admin/bulk-update-prices
    :route-name ::bulk-prices
    :with admin-write]

   ["/api/admin/request-log" :get admin/request-log
    :route-name ::request-log
    :with admin-read]

   ["/api/admin/request-log" :delete admin/clear-request-log
    :route-name ::clear-request-log
    :with [(app-mw/authenticate) (app-mw/require-auth)
           (app-mw/require-role :admin) mw/json-body-response]]

   ;; === Real-time (SSE + WebSocket) ===

   ["/api/notifications/stream" :get (realtime/notification-stream)
    :route-name ::notification-stream]

   ["/api/activity/stream" :get (realtime/activity-feed)
    :route-name ::activity-stream]

   ["/ws/chat" :get (fn [env] env)  ;; placeholder, upgraded by ws-upgrade
    :route-name ::ws-chat
    :with [(ws/ws-upgrade (realtime/chat-handler))]]

   ["/ws/books/updates" :get (fn [env] env)
    :route-name ::ws-book-updates
    :with [(ws/ws-upgrade (realtime/book-updates-handler))]]])

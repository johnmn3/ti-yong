(ns bookshelf.routes
  "Route definitions for the BookShelf API.

   Middleware is now declared on each handler transformer via :with in the handler
   namespaces. Routes are clean data: just [path method handler :route-name name].

   The :with key is still available on routes for rare cases where you need
   route-specific middleware that doesn't belong on the handler itself."
  (:require
   [bookshelf.handlers.auth :as auth]
   [bookshelf.handlers.books :as books]
   [bookshelf.handlers.authors :as authors]
   [bookshelf.handlers.reviews :as reviews]
   [bookshelf.handlers.users :as users]
   [bookshelf.handlers.reading-lists :as rl]
   [bookshelf.handlers.admin :as admin]
   [bookshelf.handlers.pages :as pages]
   [bookshelf.handlers.realtime :as realtime]
   [hearth.alpha.websocket :as ws]))

(def routes
  "All BookShelf routes.

   Each handler is a transformer that carries its own middleware via :with.
   Namespace-level transformers (e.g. books/books-ns) provide shared middleware
   for all handlers in a namespace. Individual handlers extend the ns transformer
   and add per-handler middleware (auth, loaders, pagination, etc.)."
  [;; === Pages (HTML + JSON) ===
   ["/" :get pages/home-page
    :route-name ::home]

   ["/health" :get pages/health-check
    :route-name ::health]

   ["/api" :get pages/api-info
    :route-name ::api-info]

   ;; === Books (full CRUD) ===

   ["/api/books" :get books/list-books
    :route-name ::list-books]

   ["/api/books/search" :get books/search-books
    :route-name ::search-books]

   ["/api/books/:id" :get books/get-book
    :route-name ::get-book]

   ["/api/books" :post books/create-book
    :route-name ::create-book]

   ["/api/books/:id" :put books/update-book
    :route-name ::update-book]

   ["/api/books/:id" :patch books/patch-book
    :route-name ::patch-book]

   ["/api/books/:id" :delete books/delete-book
    :route-name ::delete-book]

   ["/api/books/:id/stock" :get books/book-stock
    :route-name ::book-stock]

   ["/api/books/:id/stock" :post books/update-stock
    :route-name ::update-stock]

   ;; === Book reviews (nested under books) ===

   ["/api/books/:id/reviews" :get reviews/list-book-reviews
    :route-name ::book-reviews]

   ["/api/books/:id/reviews" :post reviews/create-review
    :route-name ::create-review]

   ;; === Reviews (direct access) ===

   ["/api/reviews/:id" :get reviews/get-review
    :route-name ::get-review]

   ["/api/reviews/:id" :put reviews/update-review
    :route-name ::update-review]

   ["/api/reviews/:id" :delete reviews/delete-review
    :route-name ::delete-review]

   ;; === Authors ===

   ["/api/authors" :get authors/list-authors
    :route-name ::list-authors]

   ["/api/authors/:id" :get authors/get-author
    :route-name ::get-author]

   ["/api/authors" :post authors/create-author
    :route-name ::create-author]

   ["/api/authors/:id" :put authors/update-author
    :route-name ::update-author]

   ["/api/authors/:id" :delete authors/delete-author
    :route-name ::delete-author]

   ["/api/authors/:id/books" :get authors/author-books
    :route-name ::author-books]

   ;; === Users ===

   ["/api/users/:id" :get users/get-user-profile
    :route-name ::get-user]

   ["/api/users/:id/reviews" :get users/user-reviews
    :route-name ::user-reviews]

   ["/api/users/:id/reading-lists" :get users/user-reading-lists
    :route-name ::user-reading-lists]

   ["/api/users/:id" :put users/update-user-profile
    :route-name ::update-user-profile]

   ;; === Auth ===

   ["/api/auth/login" :post auth/login
    :route-name ::login]

   ["/api/auth/logout" :post auth/logout
    :route-name ::logout]

   ["/api/auth/register" :post auth/register
    :route-name ::register]

   ["/api/auth/whoami" :get auth/whoami
    :route-name ::whoami]

   ;; === Reading Lists ===

   ["/api/reading-lists" :get rl/list-public-reading-lists
    :route-name ::list-reading-lists]

   ["/api/reading-lists/:id" :get rl/get-reading-list
    :route-name ::get-reading-list]

   ["/api/reading-lists" :post rl/create-reading-list
    :route-name ::create-reading-list]

   ["/api/reading-lists/:id" :put rl/update-reading-list
    :route-name ::update-reading-list]

   ["/api/reading-lists/:id" :delete rl/delete-reading-list
    :route-name ::delete-reading-list]

   ["/api/reading-lists/:id/books" :post rl/add-book-to-list
    :route-name ::add-book-to-list]

   ["/api/reading-lists/:id/books/:book_id" :delete rl/remove-book-from-list
    :route-name ::remove-book-from-list]

   ;; === Admin ===

   ["/api/admin/stats" :get admin/stats
    :route-name ::admin-stats]

   ["/api/admin/export/books" :get admin/export-books
    :route-name ::export-books]

   ["/api/admin/notifications" :get admin/notifications
    :route-name ::admin-notifications]

   ["/api/admin/seed" :post admin/seed-data
    :route-name ::admin-seed]

   ["/api/admin/bulk/delete-reviews" :post admin/bulk-delete-reviews
    :route-name ::bulk-delete-reviews]

   ;; === Real-time (SSE + WebSocket) ===

   ["/api/notifications/stream" :get (realtime/notification-stream)
    :route-name ::notification-stream]

   ["/api/activity/stream" :get (realtime/activity-feed)
    :route-name ::activity-stream]

   ["/ws/chat" :get (fn [env] env)
    :route-name ::ws-chat
    :with [(ws/ws-upgrade (realtime/chat-handler))]]

   ["/ws/books/updates" :get (fn [env] env)
    :route-name ::ws-book-updates
    :with [(ws/ws-upgrade (realtime/book-updates-handler))]]])

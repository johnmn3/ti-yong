(ns bookshelf.service
  "BookShelf service definition — wires routes with global middleware.
   This is the main configuration that users would adapt for their own apps."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [bookshelf.routes :as routes]
   [hearth.alpha :as http]
   [hearth.alpha.middleware :as mw]))

;; Seed the database on load
(db/seed!)

(def service-map
  "The BookShelf service map.

   This demonstrates all the key configuration options:
   - ::http/routes     — route definitions (see bookshelf.routes)
   - ::http/with       — global middleware stack (applied to all routes)
   - ::http/port       — server port
   - ::http/join?      — block calling thread?

   Global middleware (::http/with) runs before default middleware.
   Per-handler middleware (:with on each handler transformer) runs inside the handler.

   Middleware pipeline for a request to e.g. GET /api/books/:id:
     1. Global: request-id, request-logger, rate-limit, cors
     2. Default: error-handler, secure-headers, not-found, head-method, not-modified, query-params, method-param
     3. Per-handler: json-body-response, load-book, cache-control (defined on books/get-book)
     4. Handler :tf: books/get-book business logic"
  {::http/routes routes/routes

   ::http/with [;; Request tracing (adds X-Request-Id header)
                app-mw/request-id

                ;; Request logging with timing
                app-mw/request-logger

                ;; Rate limiting (100 req/min per IP)
                (app-mw/rate-limit {:max-requests 100 :window-ms 60000})

                ;; CORS for browser clients
                (mw/cors {:allowed-origins "*"
                           :allowed-methods "GET, POST, PUT, PATCH, DELETE, OPTIONS"
                           :allowed-headers "Content-Type, Authorization, Accept, X-Request-Id"})

                ;; CORS preflight handler
                (app-mw/cors-preflight {:allowed-origins "*"
                                        :allowed-methods "GET, POST, PUT, PATCH, DELETE, OPTIONS"
                                        :allowed-headers "Content-Type, Authorization, Accept"})]

   ::http/port 8080
   ::http/join? false})

# Hearth

An HTTP framework built on [ti-yong](https://github.com/johnmn3/ti-yong) transformers. Pedestal-like routing and middleware composition with ~21-26% better throughput.

## Quick Start

```clojure
;; deps.edn
{:deps {johnmn3/hearth {:local/root "path/to/hearth"}
        ring/ring-jetty-adapter {:mvn/version "1.13.0"}
        ring/ring-core {:mvn/version "1.13.0"}}}
```

```clojure
(ns my-app
  (:require
   [hearth.alpha :as http]
   [hearth.alpha.middleware :as mw]))

(defn hello [_env]
  {:status 200 :headers {} :body {:message "Hello, world!"}})

(def service-map
  {::http/routes [["/hello" :get hello
                   :route-name ::hello
                   :with [mw/json-body-response]]]
   ::http/port 8080
   ::http/join? false})

(defn -main []
  (-> service-map http/create-server http/start)
  (println "Server running on http://localhost:8080"))
```

## Running the BookShelf Example

The BookShelf example demonstrates all hearth features with 40+ routes:

```bash
cd hearth/examples/bookshelf
clojure -M:run
# => Server running on http://localhost:8080
```

## Running Tests

```bash
cd hearth
clojure -M:test
```

## Features

- **Routing**: data-driven route definitions with path params
- **Middleware**: composable transformer-based middleware (per-route and global)
- **JSON**: body parsing, response serialization, content negotiation
- **Sessions**: cookie-based sessions with pluggable stores
- **CSRF**: anti-forgery token protection
- **Cookies**: parsing and serialization (RFC 6265 compliant)
- **Multipart**: binary-safe file upload parsing with size limits
- **Static files**: classpath and filesystem resources with caching
- **Security headers**: X-Frame-Options, CSP, HSTS, etc.
- **SSE**: Server-Sent Events with heartbeat support
- **WebSocket**: Ring 1.13+ WebSocket protocol support
- **Transit**: Transit+JSON and Transit+MessagePack serialization
- **Async**: CompletableFuture-based async pipeline support

## Architecture

Hearth middleware are ti-yong transformers. The pipeline for each request:

```
Global middleware → Default middleware → Per-route middleware → Handler
     (::with)        (error, headers,       (:with on route)
                      404, HEAD, 304,
                      query-params,
                      method-param)
```

Each middleware can hook into different pipeline stages:
- `:tf` — transform the request env (before handler)
- `:tf-end` — transform the response env (after handler)
- `:out` — transform the raw output value
- `:env-op` — short-circuit with a direct response

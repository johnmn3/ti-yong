(ns bookshelf.specs
  "Specs for the BookShelf API context maps.
   Defines domain entity specs and context shape specs that flow through
   the transformer pipeline via :ctx.

   Usage in handler transformers:
     (update :specs conj ::specs/book-ctx ::specs/book-ctx-spec)
   This validates :ctx at tf-pre time, ensuring required keys are present
   before the handler's :tf step runs."
  (:require
   [clojure.spec.alpha :as s]))

;; ============================================================
;; Domain entity specs
;; ============================================================

;; --- Book ---
(s/def ::book-id pos-int?)
(s/def ::title (s/and string? seq))
(s/def ::author-id pos-int?)
(s/def ::isbn string?)
(s/def ::published string?)
(s/def ::pages pos-int?)
(s/def ::language string?)
(s/def ::genres (s/coll-of string?))
(s/def ::summary string?)
(s/def ::cover-url string?)
(s/def ::price (s/and number? pos?))
(s/def ::in-stock boolean?)
(s/def ::stock-count nat-int?)

(s/def ::book
  (s/keys :req-un [::title ::author-id ::price]
          :opt-un [::isbn ::published ::pages ::language ::genres
                   ::summary ::cover-url ::in-stock ::stock-count]))

;; --- Author ---
(s/def ::author-name (s/and string? seq))
(s/def ::bio string?)
(s/def ::born string?)
(s/def ::died string?)
(s/def ::nationality string?)

(s/def ::author
  (s/keys :req-un [::author-name]
          :opt-un [::bio ::born ::died ::nationality ::genres]))

;; --- Review ---
(s/def ::rating (s/and int? #(<= 1 % 5)))
(s/def ::review-title string?)
(s/def ::review-body string?)
(s/def ::user-id pos-int?)
(s/def ::created-at string?)

(s/def ::review
  (s/keys :req-un [::book-id ::user-id ::rating]
          :opt-un [::review-title ::review-body ::created-at]))

;; --- User ---
(s/def ::username (s/and string? seq))
(s/def ::email (s/and string? #(clojure.string/includes? % "@")))
(s/def ::role #{:admin :moderator :user})
(s/def ::password-hash string?)

(s/def ::user
  (s/keys :req-un [::username ::email ::role]
          :opt-un [::password-hash ::created-at]))

(s/def ::current-user
  (s/keys :req-un [::username ::email ::role]
          :opt-un [::created-at]))

;; --- Reading List ---
(s/def ::list-name (s/and string? seq))
(s/def ::public? boolean?)
(s/def ::book-ids (s/coll-of pos-int? :kind vector?))

(s/def ::reading-list
  (s/keys :req-un [::user-id ::list-name]
          :opt-un [::public? ::book-ids ::created-at]))

;; ============================================================
;; Context shape specs — validated at handler boundaries
;; ============================================================

;; Base context: always present (set by route.clj)
(s/def ::uri string?)
(s/def ::request-method keyword?)
(s/def ::headers map?)
(s/def ::scheme keyword?)

(s/def ::base-ctx
  (s/keys :req-un [::uri ::request-method ::headers]))

;; Auth context: after authenticate middleware
(s/def ::auth-method #{:session :api-key})
(s/def ::api-key-scope keyword?)

(s/def ::authed-ctx
  (s/and ::base-ctx
         (s/keys :req-un [::current-user ::auth-method])))

;; Book context: after load-book middleware
(s/def ::book-ctx
  (s/and ::base-ctx
         (s/keys :req-un [::book])))

;; Authed book context: auth + book loaded
(s/def ::authed-book-ctx
  (s/and ::authed-ctx
         (s/keys :req-un [::book])))

;; Author context: after load-author middleware
(s/def ::author-ctx
  (s/and ::base-ctx
         (s/keys :req-un [::author])))

;; Review context: after load-review middleware
(s/def ::review-ctx
  (s/and ::base-ctx
         (s/keys :req-un [::review])))

;; Paginated context: after pagination-params middleware
(s/def ::pagination (s/keys :req-un [::page ::per-page]))
(s/def ::page pos-int?)
(s/def ::per-page pos-int?)

;; Body params context: after body-params middleware
(s/def ::body-params map?)
(s/def ::query-params map?)

;; Session context: after session middleware
(s/def ::session map?)
(s/def ::cookies map?)

;; ============================================================
;; Spec validation helper
;; ============================================================

(defn validate-ctx
  "Validate the :ctx map against a spec. Returns env unchanged if valid.
   Returns env with :res error if invalid. Useful in :tf steps."
  [env spec]
  (if (s/valid? spec (:ctx env))
    env
    (assoc env :res
           {:status 400
            :headers {}
            :body {:error "Invalid request context"
                   :details (s/explain-str spec (:ctx env))}})))

(ns clj-htmx.core
  (:require
    [clj-htmx.form :as form]
    [clj-htmx.render :as render]
    [clojure.string :as string]
    [clojure.walk :as walk]))

(def parsers
  {:int #(list 'Integer/parseInt %)
   :lower #(list 'some-> % '.trim '.toLowerCase)
   :trim #(list 'some-> % '.trim)
   :string #(list 'or % "")
   :boolean #(list 'contains? #{"true" "on"} %)})

(defn sym->f [sym]
  (or
    (some (fn [[k f]]
            (when (-> sym meta k)
              f))
          parsers)
    identity))

(defn- make-f [args expanded]
  (case (count args)
    0 (throw (Exception. "zero args not supported"))
    1 `(fn ~args ~expanded)
    `(fn this#
       (~(subvec args 0 1)
         (let [~'params (-> ~(args 0) :params form/trim-keys)]
           (this#
             ~(args 0)
             ~@(for [arg (rest args)]
                 ((sym->f arg) `(~(keyword arg) ~'params))))))
       (~args ~expanded))))

(def ^:dynamic *stack* [])

(defn set-id [req]
  (string/join
    "_"
    (if-let [target (get-in req [:headers "hx-target"])]
      (assoc *stack* 0 target)
      *stack*)))

(defn with-stack [n [req] body]
  `(binding [*stack* (conj *stack* ~(name n))]
     (let [~'id (set-id ~req)
           {:keys [~'params]} ~req
           ~'params-json (form/json-params ~'params)]
       ~@body)))

(defn map-indexed-stack [f s]
  (doall
    (map-indexed #(binding [*stack* (conj *stack* %1)] (f %1 %2)) s)))

(defn get-syms [body]
  (->> body
       flatten
       (filter symbol?)
       distinct
       (mapv #(list 'quote %))))

(defn defcomp [name args body endpoint?]
  `(def ~(vary-meta name assoc :endpoint? endpoint? :syms (get-syms body))
     ~(make-f args (with-stack name args body))))

(defmacro defcomponent [name args & body]
  (defcomp name args body false))
(defmacro defendpoint [name args & body]
  (defcomp name args body true))

(defn- mapmerge [f s]
  (apply merge (map f s)))

(defn extract-endpoints
  ([sym] (extract-endpoints *ns* sym #{}))
  ([ns sym exclusions]
   (when-let [v (ns-resolve ns sym)]
     (let [{:keys [name endpoint? syms ns]} (meta v)]
       (when (not (exclusions name))
         (let [exclusions (conj exclusions name)
               mappings (mapmerge #(extract-endpoints ns % exclusions) syms)]
           (if endpoint?
             (assoc mappings name v)
             mappings)))))))

(defn extract-endpoints-root [f]
  (->> f
       flatten
       (filter symbol?)
       distinct
       (mapmerge extract-endpoints)))

(defendpoint a [req])
(defendpoint b [req] a)

(defn extract-endpoints-all [f]
  (for [[sym v] (extract-endpoints-root f)
        :let [full-symbol (symbol (-> v .ns ns-name str) (str sym))]]
    [(str "/" sym) `(fn [x#] (-> x# ~full-symbol render/snippet-response))]))

(defmacro make-routes [root f]
  `[[~root {:get ~f}]
    ~@(extract-endpoints-all f)])

(defmacro with-req [req & body]
  `(let [{:keys [~'request-method]} ~req
         ~'get? (= :get ~'request-method)
         ~'post? (= :post ~'request-method)
         ~'put? (= :put ~'request-method)
         ~'patch? (= :patch ~'request-method)
         ~'delete? (= :delete ~'request-method)]
     ~@body))

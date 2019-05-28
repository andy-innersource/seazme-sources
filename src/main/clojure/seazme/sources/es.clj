(ns seazme.sources.es
  (:require
   [clojure.core.match :refer [match]]
   [seazme.sources.twiki :as t]
   [seazme.sources.confluence :as c]
   [seazme.sources.mbox :as m]
   [seazme.sources.confluence-api :as a]
   [clojurewerkz.elastisch.rest :as esr]
   [clojurewerkz.elastisch.rest.index :as esi]
   [clojurewerkz.elastisch.rest.document :as esd])
  (:use seazme.sources.scheduler))

(def db-ini "initial-scan")
(def db-inc "incremental-scans")
(def db-inc-pro "processed")

(defn mk-es-connection[profile & {:keys [debug debug-body] :or {debug false debug-body false}}]
  (esr/connect (profile :host) {:socket-timeout 60000 :conn-timeout 60000 :insecure? true :basic-auth (profile :basic-auth) :debug debug :debug-body debug-body}))

(defn mk-conf-api[profile]
  (match [profile]
         [{:url u :api-cookie ac}] (a/mk-conf-api :get u ac 60000)
         [{:url u :basic-auth ba}] (a/mk-conf-api-auth :get u ba 60000)
         :else (throw (java.lang.Exception. (str "bad profile:" profile)))))

(defn- print-and-pass[a] (println (:url a)) a)

;;
;; ElasticSearch
;;
(def sna {:type "string" :index "not_analyzed"})
(defn reinit![{:keys [index kind]} conn]
  (let [mapping-types {kind
                       {:properties
                        {:url sna
                         :kind-name sna
                         :bu-name sna
                         :instance-name sna
                         :level0 sna
                         :level1 sna
                         :parent-id sna
                         :last-author sna
                         :last-ts sna
                         :text {:type "string" :analyzer "snowball"}
                         :text-size {:type "integer"}
                         }}}]
    [(esi/delete conn index)
    (esi/create conn index {:mappings mapping-types})]))

(defn reinit-datasources![conn]
  (let [index "datasources"
        mapping-types {"datasources"
                       {:properties
                        {:current_status sna
                         :name sna
                         :owners sna
                         :business_unit sna
                         :last_updated_time {:type "integer"}
                         :tag sna
                         :notes sna
                         }}}]
    [(esi/delete conn index)
     (esi/create conn index {:mappings mapping-types})]))

;;TODO can we create two same docs? test it
(defn put-doc![conn indx ttype doc]
  (esd/put conn indx ttype (:id doc) doc)) ;;TODO can we create two same docs? test it


;;
;; Twiki
;;
(defn twiki-scan![{:keys [index kind instance base-url]} conn {:keys [path]}]
  (->>
   path
   t/find-topics
   #_(take 10)
   (remove nil?)
   (map t/read-topic!)
   (remove nil?)
   (map (partial t/parse-topic kind instance "main" base-url)) ;;TODO "main" into config
   (map print-and-pass)
   (map (partial put-doc! conn index kind))
   (map :created)
   frequencies))

;;
;; Email
;;
(defn mbox-scan![conn {:keys [path]} {:keys [index kind]}]
  (->>
   path
   m/process-mbox
   (map m/format-mbox)
   (map (partial put-doc! conn index kind))
   (map :created)
   frequencies))

;;
;; Confluence
;;
(defn confluence-scan-2cache![{:keys []} {:keys [path]} api]
  (->>
   api
   c/find-spaces
   (map (partial c/save-space-from-search api (str path "/" db-ini)))
   doall))

(defn confluence-update-cache![{:keys [index kind url instance]} api [path]]
  (c/pull-confl-incr api (str path "/" db-inc)))

(defn confluence-scan-2index![{:keys [index kind url instance]} conn {:keys [path]}]
  (->>
   [db-ini (str db-inc "/" db-inc-pro)] ;;order is essential for below sort to work
   (map (partial str path "/"))
   (map c/find-pages)
   (mapcat sort) ;;sort to make sure we update from oldest to newest (only for incremental scan) - never remove it
   (map c/read-page!)
   (map (partial c/parse-page "confluence" instance "main" url)) ;;TODO "main" into config
   (map print-and-pass)
   (map (partial put-doc! conn index kind))
   (map :created)
   frequencies))

(defn confluence-update-index![conn [path indx ttype base-url instance-name]]
  #_{:file "2017-04-10_15:50", :path+file "db/ppconf/incremental-scans/2017-04-10_15:50"}
  (letfn [(update-fn [args]
            (println "incremental indexing " (:file args))
            (->>
             args
             :path+file
             c/find-pages
             (map c/read-page!)
             (map (partial c/parse-page "confluence" instance-name "main" base-url))
             (map (partial put-doc! conn indx ttype))
             (map :created)
             frequencies))]
    (invoke-and-backup! (str path "/" db-inc) db-inc-pro update-fn)))

;;TODO
;; document junk-in garbage-out
;; run by cron every 15 minutes
;; do profiles vs individual settings
;; ERROR handling, confluence is down, session expires
;; make all pure functions, use ! if not possible

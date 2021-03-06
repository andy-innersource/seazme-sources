(ns seazme.sources.snapshot
  (:require [clojure.data.json :as json]
            [seazme.common.config :as config]
            [seazme.common.hbase :as hb]
            [cbass.tools :refer [to-bytes]]
            [cbass :refer [pack-un-pack]][taoensso.nippy :as n];;this is hack waiting for fix: https://github.com/tolitius/cbass/issues/9
            [clj-time.format :as tf] [clj-time.core :as tr] [clj-time.coerce :as te])
  (:use seazme.sources.common
        seazme.common.datahub
        seazme.common.common))

(defn- transform-value[f v] (if (vector? v) (clojure.string/join "," (map f v)) (str (f v))))

;;When the key is a map/hash, it provides name mapping from to. It has to have one entry
;;When the key is a keyword (thing with : in front), the name of the field will remain the same
;;The value is a vector/array and if it is empty, e.i [], the actual value will be taken from the original JSON
;;If it is not empty, it will indicate a “path” to the element: e.g. [“name”] is a common path
;;Keywords (things with : in front) are used instead of strings for better input validation.
;;Unlike in JSON, keys in maps/hashes could be a composite data structure, not only strings
(defn apply-field-mapping[m d]
  (let [nm (into [] (for [[k v] m] (if (map? k) [(ffirst k) (second (first k)) v] [k k v])))];;k should have one pair
    (mapcat
     (fn [[ok fk v]]
       (let [dv (get d ok "")]
         (condp #(%1 %2) v
           map? (apply-field-mapping v dv) ;;fk disregarded
           vector? [[(name fk) (transform-value #(get-in % v) dv)]]
           :else (assert false "wrong type"))))
     nm)))


(defn- update-snapshot![session]
  (let [initiated (jts-now)
        session-id (-> session :self :meta :id)
        session-tsx (-> session :self :meta :tsx)
        session-count (-> session :self :count)
        user-name (System/getProperty "user.name")
        user-dir (System/getProperty "user.dir")
        pid (-> (java.lang.management.ManagementFactory/getRuntimeMXBean) .getName)
        host-name (.getCanonicalHostName (java.net.InetAddress/getLocalHost))
        update-hbase! (fn [expl]
                        (let [jts (jts-now)
                              payload (-> expl second :self :payload)
                              document-key (clojure.string/reverse (:id payload))
                              hive-cf-payload (into {} (apply-field-mapping config/mapping payload))
                              datahub-cf-payload {"id" (:id payload)
                                                  "key" (:key payload)
                                                  "updated" (-> payload :fields :updated)
                                                  "status" (-> payload :fields :status :name)
                                                  "meta" (json/write-str {:key document-key
                                                                          :user-name user-name
                                                                          :user-dir user-dir
                                                                          :pid pid
                                                                          :host-name host-name
                                                                          :session-id session-id
                                                                          :session-count session-count
                                                                          :source "sources"
                                                                          :type "document"
                                                                          :initiated initiated
                                                                          :created jts})
                                                  "payload" (json/write-str payload)}]
                          (println "\tposting:" (:key payload) (:id payload) (-> payload :fields :updated))
                          (pack-un-pack {:p #(to-bytes %)})
                          (hb/store** "datahub:snapshot-data" document-key "hive" hive-cf-payload)
                          (hb/store** "datahub:snapshot-data" document-key "datahub" datahub-cf-payload)
                          (pack-un-pack {:p n/freeze})))
        real-cnt (->> dist-bytes
                      (map #(->> % (get-data-entries-seq session-id) (map update-hbase!) doall))
                      (mapcat identity)
                      count)]
    real-cnt))

(defn- action-snap![prefix ts prev-ts app session]
  (if (= "jira" (-> app :kind))
    (update-snapshot! session)
    (do
      (println "\tskipped:" (-> session :self :meta :tsx))
      nil)))

(defn process-sessions![{:keys [prefix]} action]
  (println "STARTING jira-patch:"prefix action(str "@"(tr/now)))
  (run-update-log! prefix action action-snap! action-snap!)
  (println "SUCCEEDED"(str "@"(tr/now)))
  )

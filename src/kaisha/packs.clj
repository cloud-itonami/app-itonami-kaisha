(ns kaisha.packs
  "egov の制度パック（vendor/egov/seido）を読む。画面（ClojureScript）は通信しないので、
  ビルド時に埋め込む（macro）。JVM と Babashka は実行時に読む。"
  (:require [clojure.edn :as edn]))

(def files ["vendor/egov/seido/jp.edn"])

(defn read-packs [] (mapv #(edn/read-string (slurp %)) files))

(defmacro bundled-packs [] (read-packs))

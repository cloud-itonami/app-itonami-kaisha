(ns kaisha.identity
  "本人確認・電子署名・電子申請の仕組みとの連携（AWAI-3483）。

  owner 決定（2026-11-18）: 日本から段階的に進める。個人番号（12 桁）は読まない・求めない・保存しない。
  ここにあるのは、仕組みごとの目的・扱うデータの種類・前提（認定や ID の発行）と、外部操作の宣言だけ。
  共通の同意 broker（kotoba-client-runtime）が確認できるまで、どの操作も有効にしない。"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------- 法人番号（公開情報）

(defn corporate-number-valid?
  "法人番号（13 桁）の検査用数字を確かめる（法人番号の指定等に関する省令 第2条）。
  先頭 1 桁が検査用数字で、残り 12 桁（基礎番号）の最下位を 1 桁目として
  9 − (Σ Pn × Qn を 9 で割った余り)、Qn は n が奇数なら 1、偶数なら 2。"
  [s]
  (boolean
   (when (and (string? s) (re-matches #"\d{13}" s))
     (let [digits (map #?(:clj #(Character/digit (char %) 10) :cljs #(js/parseInt % 10)) s)
           check (first digits)
           base (reverse (rest digits))
           total (reduce + (map-indexed (fn [i p] (* p (if (even? i) 1 2))) base))]
       (= check (- 9 (mod total 9)))))))

;; ---------------------------------------------------------------- 仕組みの一覧

(def never-handled
  "どの仕組みでも、Kaisha が扱わないデータ。"
  #{:individual-number})

(def broker-available?
  "共通の同意 broker が確認できたか。確認できるまで false のまま（AWAI-155 の blocker）。"
  false)

(def services
  [{:id :jp/jpki
    :country :jp
    :purposes #{:identity :signature}
    :holder :representative
    :data #{:signing-certificate :authentication-certificate}
    :requirements [:jpki-verifier-certification-or-platform]
    :status :blocked
    :note :jpki}
   {:id :jp/commercial-registration-certificate
    :country :jp
    :purposes #{:signature}
    :holder :representative
    :data #{:signing-certificate}
    :requirements [:certificate-issued-to-representative]
    :status :planned}
   {:id :jp/houjin-bangou
    :country :jp
    :purposes #{:company-lookup}
    :holder :company
    :data #{:corporate-number}
    :requirements [:nta-application-id]
    :status :blocked
    :operations
    [{:id :houjin-bangou/lookup
      :service "国税庁 法人番号システム Web-API"
      :origin "https://api.houjin-bangou.nta.go.jp"
      :method "GET"
      :path "/4/num"
      :query-names #{"id" "number" "type" "history"}
      :data-categories #{:corporate-number :service-application-id}
      :consent :each-operation
      :enabled false}]}
   {:id :jp/gbizid
    :country :jp
    :purposes #{:identity}
    :holder :company
    :data #{:account}
    :requirements [:unverified]
    :status :research}
   {:id :jp/egov-efiling
    :country :jp
    :purposes #{:filing}
    :holder :company
    :data #{:application-form :signing-certificate}
    :requirements [:egov-api-user-registration :egov-conformance-test]
    :status :blocked}
   {:id :jp/touki-online
    :country :jp
    :purposes #{:filing}
    :holder :representative
    :data #{:application-form :signing-certificate}
    :requirements [:unverified]
    :status :research}
   {:id :eu/eudi-wallet :country :eu :purposes #{:identity :signature} :data #{} :requirements [:unverified] :status :research}
   {:id :ee/id-card :country :ee :purposes #{:identity :signature} :data #{} :requirements [:unverified] :status :research}
   {:id :sg/singpass-corppass :country :sg :purposes #{:identity} :data #{} :requirements [:unverified] :status :research}])

(defn by-country [country] (filterv #(= country (:country %)) services))

(defn operations [] (vec (mapcat :operations services)))

(defn enabled-operations
  "有効な外部操作。broker が確認できるまで、宣言があっても空。"
  []
  (if broker-available? (filterv :enabled (operations)) []))

(defn operation-problems
  "外部操作の宣言の確認: https の固定 origin、固定の method/path、query 名の列挙、操作ごとの同意。"
  [op]
  (cond-> []
    (not (str/starts-with? (str (:origin op)) "https://")) (conj :origin-not-https)
    (re-find #"^https://[^/]+/" (str (:origin op))) (conj :origin-has-path)
    (not (#{"GET" "HEAD" "POST" "PUT" "DELETE"} (:method op))) (conj :invalid-method)
    (not (str/starts-with? (str (:path op)) "/")) (conj :invalid-path)
    (not (set? (:query-names op))) (conj :query-names-not-listed)
    (not= :each-operation (:consent op)) (conj :consent-not-each-operation)
    (some never-handled (:data-categories op)) (conj :handles-individual-number)))

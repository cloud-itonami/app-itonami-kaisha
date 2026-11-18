(ns kaisha.procedures
  "行政手続き。判定は egov（itonami.egov.core、vendor/egov に固定）が正で、ここは Kaisha の
  会社・役員の記録を egov の入力に変えるだけ。候補と期限を返し、提出・送信はしない。"
  (:require [itonami.egov.core :as egov]
            [kaisha.company :as co]
            #?(:clj [kaisha.packs :as packs]))
  #?(:cljs (:require-macros [kaisha.packs :refer [bundled-packs]])))

(def packs #?(:clj (packs/read-packs) :cljs (bundled-packs)))

(def egov-form
  "Kaisha の会社の形態 → egov の主体の種類。"
  {:kk :kabushiki-kaisha :godo :godo-kaisha})

(defn subdivisions
  "国の制度パックにある市町村 [[id name fixture?] ...]。"
  [country]
  (let [pack (egov/find-pack packs country)]
    (vec (for [[id {:keys [name fixture]}] (sort-by key (:pack/subdivisions pack))]
           [id name (boolean fixture)]))))

(defn subdivision-name [country id]
  (get-in (egov/find-pack packs country) [:pack/subdivisions id :name]))

(defn- representative-confirmed? [c]
  (boolean (some (fn [o]
                   (and (some #{:representative-director :representative-member} (:officer/roles o))
                        (= :ok (:status (co/representative? c (:officer/id o))))))
                 (:company/officers c))))

(defn head-office-move-plan
  "本店移転 move {:to {:country :subdivision} :moved-on :address-confirmed?} の候補手続き。
  返り値は egov/kaisha-plan の結果（:candidates / :blocked / :undetermined）。"
  [c move today]
  (let [org (:company/id c)]
    (egov/kaisha-plan
     {:packs packs
      :today today
      ;; 会社自身の記録として計画する。社内の役職は代表権の証明ではないので、
      ;; 代表権は登記事項で確認済みかどうかを別に渡す（egov が未確認なら止める）。
      :grant {:principal org :actor :self :scope #{:plan}}
      :facts {:organization org
              :form (egov-form (:company/form c))
              :from (:company/address c)
              :to (:to move)
              :moved-on (:moved-on move)
              :address-confirmed? (boolean (:address-confirmed? move))
              :representative-confirmed? (representative-confirmed? c)}})))

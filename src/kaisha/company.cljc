(ns kaisha.company
  "会社と機関設計、役員の任期、変更登記の期限。

  対象は株式会社（監査等委員会設置会社・指名委員会等設置会社を除く）と合同会社。
  それ以外の形態や機関設計は :undetermined を返し、推測で埋めない。"
  (:require [clojure.string :as str]
            [kaisha.date :as d]
            [kaisha.identity :as idn]))

(def supported-forms #{:kk :godo})

(defn kk? [c] (= :kk (:company/form c)))

(defn problem [severity code & {:as more}]
  (merge {:severity severity :code code} more))

(defn- term-limit
  "役員の任期の上限（年）。332条1・2項、336条1・2項。"
  [c role]
  (case role
    :director (if (:company/public? c) 2 10)
    :auditor (if (:company/public? c) 4 10)
    nil))

(defn- term-floor [role] (case role :director 0 :auditor 4 nil))

(defn validate
  "会社の設定の構造上の問題を返す（法的判断ではなく、このアプリで扱えるかの確認）。"
  [c]
  (let [form (:company/form c)
        arts (:company/articles c)]
    (cond-> []
      (not (supported-forms form))
      (conj (problem :error :unsupported-form :form form))

      (not (<= 1 (or (:company/fy-end-month c) 0) 12))
      (conj (problem :error :invalid-fy-end-month))

      (and (kk? c) (:company/committees c))
      (conj (problem :error :unsupported-governance))

      (and (kk? c) (:director-term-years arts)
           (not (<= 1 (:director-term-years arts) (term-limit c :director))))
      (conj (problem :error :director-term-out-of-range
                     :limit (term-limit c :director) :source :kaisha/a332))

      (and (kk? c) (:company/auditor? c) (:auditor-term-years arts)
           (not (<= (term-floor :auditor) (:auditor-term-years arts) (term-limit c :auditor))))
      (conj (problem :error :auditor-term-out-of-range
                     :limit (term-limit c :auditor) :source :kaisha/a336))

      (and (kk? c) (:company/public? c) (not (:company/board? c)))
      (conj (problem :warning :public-without-board))

      (and (:shareholder-notice-days arts)
           (or (:company/public? c) (:company/board? c)))
      (conj (problem :error :notice-shortening-not-allowed :source :kaisha/a299))

      (and (some? (:board-notice-days arts))
           (not (and (integer? (:board-notice-days arts)) (<= 1 (:board-notice-days arts) 7))))
      (conj (problem :error :board-notice-out-of-range :source :kaisha/a368))

      (some (fn [k] (let [q (get-in arts [:quorum k])]
                      (or (= q :none)
                          (and (vector? q) (< (* 3 (first q)) (second q))))))
            [:officer :special])
      (conj (problem :error :quorum-below-one-third :source :kaisha/a341))

      (str/blank? (:company/name c))
      (conj (problem :error :missing-name))

      (and (some? (:company/corporate-number c))
           (not (idn/corporate-number-valid? (:company/corporate-number c))))
      (conj (problem :error :invalid-corporate-number :source :hojinbango-shoureki/a2)))))

(def officer-roles-by-form
  {:kk #{:director :representative-director :auditor}
   :godo #{:representative-member :executive-member}})

(defn officer-problems
  "役員を加える前の確認。返り値は problem の列（空なら追加できる）。"
  [c o]
  (let [roles (set (:officer/roles o))
        allowed (officer-roles-by-form (:company/form c))]
    (cond-> []
      (str/blank? (:officer/id o)) (conj (problem :error :missing-id))
      (some #(= (:officer/id o) (:officer/id %)) (:company/officers c)) (conj (problem :error :duplicate-officer))
      (str/blank? (:officer/name o)) (conj (problem :error :missing-name))
      (or (empty? roles) (not (every? allowed roles))) (conj (problem :error :invalid-role))
      (and (roles :auditor) (not (:company/auditor? c))) (conj (problem :error :no-auditor-seat))
      (not (d/valid? (:officer/elected-on o))) (conj (problem :error :missing-election-date)))))

(defn term-years
  "役員の任期（年）。定款の定めがなければ法定（取締役 2 年、監査役 4 年）。"
  [c role]
  (let [arts (:company/articles c)]
    (case role
      :director (or (:director-term-years arts) 2)
      :auditor (or (:auditor-term-years arts) 4)
      nil)))

(defn officer-roles [o] (set (:officer/roles o)))

(defn term-role
  "任期の計算に使う役職。代表取締役は取締役としての任期で数える。"
  [o]
  (let [r (officer-roles o)]
    (cond (r :auditor) :auditor
          (or (r :director) (r :representative-director)) :director)))

(defn officer-term
  "役員の任期の終わり。

  任期は「選任後 N 年以内に終了する事業年度のうち最終のものに関する定時株主総会の終結の時」まで
  （332条1項、336条1項）。総会の日は決まるまで分からないので、定款上の開催時期の最終日を
  :latest-expiry として示し、:estimated true を付ける。"
  [c o]
  (let [role (term-role o)
        elected (:officer/elected-on o)]
    (cond
      (not (kk? c)) {:status :not-applicable}
      (nil? role) {:status :not-applicable}
      (not (d/valid? elected)) {:status :undetermined :reason :missing-election-date}
      :else
      (let [n (term-years c role)
            ends (d/fiscal-year-ends (:company/fy-end-month c) elected (d/add-years elected n))
            fy (peek ends)]
        (if (nil? fy)
          {:status :undetermined :reason :no-fiscal-year-end}
          {:status :ok
           :role role
           :term-years n
           :last-fiscal-year-end fy
           :latest-expiry (d/add-months fy (or (:company/agm-within-months c) 3))
           :estimated true
           :source (if (= role :auditor) :kaisha/a336 :kaisha/a332)})))))

(defn registration-deadline
  "登記事項の変更から 2 週間以内（915条1項）。初日は数えない（民法140条）ので変更日 + 14 日。"
  [change-date]
  (when (d/valid? change-date)
    {:deadline (d/add-days change-date 14) :source :kaisha/a915}))

(defn representative?
  "代表権の候補かどうか。内部の役職表は代表権の証明にならないので、
  登記事項証明書などで確認済み（:officer/authority-verified-on）でなければ :unverified。"
  [c officer-id]
  (let [o (some #(when (= officer-id (:officer/id %)) %) (:company/officers c))
        roles (officer-roles o)
        rep? (if (kk? c)
               (or (roles :representative-director)
                   (and (not (:company/board? c)) (roles :director)
                        (not-any? #(contains? (officer-roles %) :representative-director)
                                  (:company/officers c))))
               (or (roles :representative-member) (roles :executive-member)))
        src (when (kk? c) :kaisha/a349)]
    (cond
      (nil? o) {:status :blocked :reason :unknown-officer}
      (not rep?) {:status :blocked :reason :no-representative-authority :source src}
      (not (d/valid? (:officer/authority-verified-on o)))
      {:status :unverified :reason :representative-unconfirmed :source src}
      :else {:status :ok :source src})))

(defn directors [c]
  (->> (:company/officers c)
       (filter #(some (officer-roles %) [:director :representative-director]))
       (map :officer/id)
       vec))

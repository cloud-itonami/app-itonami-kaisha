(ns kaisha.deadlines
  "法定の期限と予定の一覧。日付は候補であり、:estimated true のものは定款・総会の日程で変わる。"
  (:require [kaisha.books :as books]
            [kaisha.company :as co]
            [kaisha.date :as d]
            [kaisha.meeting :as mt]))

(defn- fiscal-year-end-on-or-after [c today]
  (first (d/fiscal-year-ends (:company/fy-end-month c) today (d/add-years today 1))))

(defn- last-fiscal-year-end [c today]
  (peek (d/fiscal-year-ends (:company/fy-end-month c) (d/add-years today -1) today)))

(defn officer-items [c]
  (for [o (:company/officers c)
        :let [t (co/officer-term c o)]
        :when (= :ok (:status t))
        item [{:kind :officer-term-expiry :date (:latest-expiry t) :estimated true
               :params {:name (:officer/name o) :fy (:last-fiscal-year-end t)}
               :source (:source t)}
              {:kind :officer-registration :date (d/add-days (:latest-expiry t) 14) :estimated true
               :params {:name (:officer/name o)} :source :kaisha/a915}]]
    item))

(defn fiscal-items
  "直近の事業年度末（today 以前で最後のもの）と次の事業年度末から、総会・申告の期限を出す。"
  [c today]
  (let [months (or (:company/agm-within-months c) 3)]
    (for [fy (distinct (remove nil? [(last-fiscal-year-end c today) (fiscal-year-end-on-or-after c today)]))
          item (cond-> [{:kind :fiscal-year-end :date fy :params {:fy fy}}
                        {:kind :tax-return :date (d/add-months fy 2) :params {:fy fy}
                         :source :hojinzei/a74 :note :extension-not-considered}]
                 (co/kk? c)
                 (conj {:kind :agm-latest :date (d/add-months fy months) :estimated true
                        :params {:fy fy} :source :kaisha/a296}))]
      item)))

(defn meeting-items [c meetings]
  (for [m meetings
        :let [n (mt/notice-check c m)]
        :when (= :pending (:status n))
        item [{:kind :notice-latest :date (:latest-send-date n)
               :params {:title (:meeting/title m)} :source (:source n)}
              {:kind :meeting :date (:meeting/date m) :params {:title (:meeting/title m)}}]]
    item))

(defn financials-items [c fs today]
  (when-let [nxt (and fs (seq (:fs/rows fs)) (books/next-step c fs))]
    [{:kind :financials-next :date today :params {:step (:step nxt)} :source (:source nxt)}]))

(defn registration-items [changes]
  (for [{:keys [title on]} changes
        :let [r (co/registration-deadline on)]
        :when r]
    {:kind :registration :date (:deadline r) :params {:title title} :source :kaisha/a915}))

(defn upcoming
  "today から horizon 日先までの期限（期限切れのものは :overdue true で先頭に残す）。"
  [{:keys [company meetings financials registrations]} today horizon]
  (let [end (d/add-days today horizon)
        items (concat (officer-items company)
                      (fiscal-items company today)
                      (meeting-items company meetings)
                      (financials-items company financials today)
                      (registration-items registrations))]
    (->> items
         (filter #(d/on-or-before? (:date %) end))
         (map #(cond-> % (d/before? (:date %) today) (assoc :overdue true)))
         (remove #(and (:overdue %) (#{:fiscal-year-end :agm-latest :tax-return} (:kind %))
                       (d/before? (:date %) (d/add-days today -60))))
         (sort-by (juxt :date (comp name :kind)))
         vec)))

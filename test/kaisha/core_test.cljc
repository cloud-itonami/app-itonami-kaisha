(ns kaisha.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaisha.app :as app]
            [kaisha.books :as books]
            [kaisha.chat :as chat]
            [kaisha.company :as co]
            [kaisha.date :as d]
            [kaisha.i18n]
            [kaisha.deadlines :as dl]
            [kaisha.law :as law]
            [kaisha.meeting :as mt]
            [kaisha.ringi :as ringi]))

(def c (:company (app/sample)))

(deftest dates
  (is (= "2026-02-28" (d/add-months "2026-01-31" 1)))
  (is (= "2028-02-29" (d/add-months "2028-01-31" 1)))
  (is (= "2026-11-04" (d/add-days "2026-11-18" -14)))
  (is (nil? (d/parse "2026-02-30")))
  (is (= ["2026-03-31" "2027-03-31"] (d/fiscal-year-ends 3 "2025-06-26" "2027-06-26"))))

(deftest officer-terms
  (testing "取締役 2 年: 2025-06-26 選任 → 2027-03 期の定時総会まで（332条1項）"
    (let [t (co/officer-term c (first (:company/officers c)))]
      (is (= :ok (:status t)))
      (is (= "2027-03-31" (:last-fiscal-year-end t)))
      (is (= "2027-06-30" (:latest-expiry t)))
      (is (:estimated t))
      (is (= :kaisha/a332 (:source t)))))
  (testing "監査役 4 年（336条1項）"
    (is (= "2027-03-31" (:last-fiscal-year-end (co/officer-term c (nth (:company/officers c) 3))))))
  (testing "非公開会社は定款で 10 年まで、公開会社は 2 年まで（332条2項）"
    (is (empty? (co/validate (assoc-in c [:company/articles :director-term-years] 10))))
    (is (= [:director-term-out-of-range]
           (map :code (co/validate (-> c (assoc :company/public? true)
                                       (assoc-in [:company/articles :director-term-years] 3)))))))
  (testing "監査役の任期は短縮できない"
    (is (some #{:auditor-term-out-of-range}
              (map :code (co/validate (assoc-in c [:company/articles :auditor-term-years] 2))))))
  (testing "選任日がなければ決めない"
    (is (= :undetermined (:status (co/officer-term c {:officer/roles [:director]}))))))

(deftest registration
  (is (= "2027-07-14" (:deadline (co/registration-deadline "2027-06-30"))))
  (is (nil? (co/registration-deadline "not-a-date"))))

(deftest representative
  (testing "代表取締役でも、登記事項で確認するまでは未確認"
    (is (= :unverified (:status (co/representative? c "yamada"))))
    (is (= :ok (:status (co/representative?
                         (assoc-in c [:company/officers 0 :officer/authority-verified-on] "2026-11-18")
                         "yamada")))))
  (testing "取締役会設置会社の平取締役には代表権がない"
    (is (= :no-representative-authority (:reason (co/representative? c "sato"))))))

(deftest notices
  (testing "非公開・取締役会設置: 株主総会は 1 週間前まで（中 7 日）"
    (is (= 7 (mt/shareholder-notice-days c {})))
    (is (= "2027-06-17" (mt/latest-send-date "2027-06-25" 7)))
    (is (= :ok (:status (mt/notice-check c {:meeting/body :shareholders :meeting/date "2027-06-25"
                                            :meeting/notice-sent-on "2027-06-17"}))))
    (is (= :notice-too-late (:reason (mt/notice-check c {:meeting/body :shareholders :meeting/date "2027-06-25"
                                                         :meeting/notice-sent-on "2027-06-18"})))))
  (testing "書面投票を定めたら 2 週間（299条1項）"
    (is (= 14 (mt/shareholder-notice-days c {:written-voting? true}))))
  (testing "取締役会非設置の非公開会社は定款で短縮できる。取締役会設置会社では短縮は無効"
    (is (= 3 (mt/shareholder-notice-days (-> c (assoc :company/board? false)
                                             (assoc-in [:company/articles :shareholder-notice-days] 3)) {})))
    (is (some #{:notice-shortening-not-allowed}
              (map :code (co/validate (assoc-in c [:company/articles :shareholder-notice-days] 3))))))
  (testing "取締役会は全員の同意で招集手続を省略できる（368条2項）"
    (is (:waived (mt/notice-check c {:meeting/body :board :meeting/date "2026-12-10" :meeting/all-consent-waiver? true})))))

(deftest shareholder-resolutions
  (testing "普通決議: 過半数出席、出席の過半数"
    (is (= :passed (:status (mt/resolve-shareholders c {:matter :approve-financials :rights-total 100 :rights-present 51 :rights-for 26}))))
    (is (= :quorum-not-met (:reason (mt/resolve-shareholders c {:matter :approve-financials :rights-total 100 :rights-present 50 :rights-for 50}))))
    (is (= :insufficient-votes (:reason (mt/resolve-shareholders c {:matter :approve-financials :rights-total 100 :rights-present 60 :rights-for 30})))))
  (testing "特別決議: 出席の 3 分の 2 以上"
    (is (= :passed (:status (mt/resolve-shareholders c {:matter :amend-articles :rights-total 90 :rights-present 60 :rights-for 40}))))
    (is (= :failed (:status (mt/resolve-shareholders c {:matter :amend-articles :rights-total 90 :rights-present 60 :rights-for 39})))))
  (testing "役員の選任: 定款で定足数を下げても 3 分の 1 未満にはできない（341条）"
    (is (= :passed (:status (mt/resolve-shareholders (assoc-in c [:company/articles :quorum :officer] [1 3])
                                                     {:matter :elect-officer :rights-total 90 :rights-present 30 :rights-for 16}))))
    (is (= :quorum-below-one-third (:reason (mt/resolve-shareholders (assoc-in c [:company/articles :quorum :officer] [1 4])
                                                                     {:matter :elect-officer :rights-total 90 :rights-present 30 :rights-for 16})))))
  (testing "特殊決議と矛盾した票数は判定しない"
    (is (= :undetermined (:status (mt/resolve-shareholders c {:matter :share-transfer-restriction :rights-total 10 :rights-present 10 :rights-for 10}))))
    (is (= :blocked (:status (mt/resolve-shareholders c {:matter :dividend :rights-total 10 :rights-present 5 :rights-for 6}))))))

(deftest thresholds
  (testing "普通決議: 100 個なら定足数 51、出席 60 なら可決 31（309条1項）"
    (is (= {:min-present 51 :min-for 31} (select-keys (mt/shareholder-thresholds c :approve-financials 100 60) [:min-present :min-for]))))
  (testing "特別決議: 出席 60 なら 40 以上"
    (is (= 40 (:min-for (mt/shareholder-thresholds c :amend-articles 100 60)))))
  (testing "定款で定足数 1/3: 90 個なら 30"
    (is (= 30 (:min-present (mt/shareholder-thresholds (assoc-in c [:company/articles :quorum :officer] [1 3]) :elect-officer 90 30)))))
  (testing "要件の根拠は 309条（役員の選任は 341条）。議題の根拠の 438条ではない"
    (is (= :kaisha/a309 (:source (mt/shareholder-thresholds c :approve-financials 100 60))))
    (is (= :kaisha/a309 (:source (mt/resolve-shareholders c {:matter :approve-financials :rights-total 100 :rights-present 60 :rights-for 40}))))
    (is (= :kaisha/a341 (:source (mt/shareholder-thresholds c :elect-officer 100 60)))))
  (testing "特殊決議は示さない"
    (is (= :undetermined (:status (mt/shareholder-thresholds c :share-transfer-restriction 100 60)))))
  (testing "必要な数は判定と一致する（境界の 1 つ下は否決）"
    (doseq [[total present] [[100 60] [7 5] [90 61] [3 2]]]
      (let [{:keys [min-present min-for]} (mt/shareholder-thresholds c :approve-financials total present)]
        (is (= (>= present min-present)
               (:quorum-met (mt/resolve-shareholders c {:matter :approve-financials :rights-total total :rights-present present :rights-for 0}))))
        (is (= :passed (:status (mt/resolve-shareholders c {:matter :approve-financials :rights-total total :rights-present (max present min-present) :rights-for (:min-for (mt/shareholder-thresholds c :approve-financials total (max present min-present)))}))))
        (is (not= :passed (:status (mt/resolve-shareholders c {:matter :approve-financials :rights-total total :rights-present present :rights-for (dec min-for)})))))))
  (testing "取締役会: 3 人なら出席 2、出席 2 なら賛成 2。利害関係者 1 人なら対象 2 人"
    (is (= {:eligible 3 :min-present 2 :min-for 2} (select-keys (mt/board-thresholds c #{} 2) [:eligible :min-present :min-for])))
    (is (= 2 (:eligible (mt/board-thresholds c #{"yamada"} nil))))))

(deftest board-resolutions
  (testing "特別利害関係のある取締役は数えない（369条2項）"
    (let [r (mt/resolve-board c {:present #{"yamada" "sato" "suzuki"} :for #{"yamada" "sato"} :interested #{"yamada"}})]
      (is (= :failed (:status r)))
      (is (= ["yamada"] (:excluded-interested r)))))
  (is (= :passed (:status (mt/resolve-board c {:present #{"yamada" "sato"} :for #{"yamada" "sato"}}))))
  (is (= :quorum-not-met (:reason (mt/resolve-board c {:present #{"yamada"} :for #{"yamada"}}))))
  (is (= :unknown-director (:reason (mt/resolve-board c {:present #{"takahashi" "sato"} :for #{"sato"}}))))
  (is (= "2036-12-10" (:until (mt/minutes-retention {:meeting/body :board :meeting/date "2026-12-10"})))))

(def tb "科目,区分,金額\n現金,資産,\"1,500,000\"\n売掛金,資産,500000\n買掛金,負債,300000\n資本金,純資産,1000000\n売上高,収益,2000000\n仕入高,費用,1300000\n")

(deftest trial-balance
  (let [{:keys [rows errors]} (books/parse-trial-balance tb)
        s (books/summarize rows)]
    (is (empty? errors))
    (is (= 2000000 (:assets s)))
    (is (= 700000 (:net-income s)))
    (is (:balanced? s)))
  (is (= [{:line 2 :code :unknown-category}] (:errors (books/parse-trial-balance "科目,区分,金額\n現金,現預金,1\n"))))
  (is (= :unexpected-header (-> (books/parse-trial-balance "a,b,c\n") :errors first :code))))

(deftest financial-statements
  (let [rows (:rows (books/parse-trial-balance tb))
        fs {:fs/rows rows :fs/history []}]
    (is (= [:prepared :audited :board-approved :agm-approved :published] (map :step (books/steps c))))
    (is (= [:prepared :agm-approved :published]
           (map :step (books/steps (assoc c :company/board? false :company/auditor? false)))))
    (is (= :out-of-order (:error (books/advance c fs {:step :audited}))))
    (is (= :unbalanced-trial-balance
           (:error (books/advance c {:fs/rows (butlast rows)} {:step :prepared}))))
    (let [fs (:ok (books/advance c fs {:step :prepared :on "2027-05-10"}))
          fs (:ok (books/advance c fs {:step :audited :on "2027-05-20"}))]
      (is (= :board-resolution-required (:error (books/advance c fs {:step :board-approved}))))
      (is (= :board-resolution-required
             (:error (books/advance c fs {:step :board-approved :resolution {:matter :board-general :result {:status :passed}}}))))
      (is (= :agm-resolution-required
             (:error (books/advance c (:ok (books/advance c fs {:step :board-approved :resolution {:matter :approve-financials :result {:status :passed}}}))
                                    {:step :agm-approved :resolution {:matter :dividend :result {:status :passed}}})))))))

(deftest ringi-flow
  (let [r {:ringi/id "r1" :ringi/title "サーバー購入" :ringi/amount 500000 :ringi/category :ordinary
           :ringi/drafter "ito" :ringi/submitted-on "2026-11-18"}]
    (is (= 2 (:approvers (ringi/route c r))))
    (is (= :self-approval (:error (ringi/decide c (assoc r :ringi/drafter "sato") {:by "sato" :decision :approve}))))
    (is (= :no-approval-permission (:error (ringi/decide c r {:by "takahashi" :decision :approve}))))
    (let [r (:ok (ringi/decide c r {:by "sato" :decision :approve}))]
      (is (= :already-decided (:error (ringi/decide c r {:by "sato" :decision :approve}))))
      (is (= :pending (ringi/status c r)))
      (let [r (:ok (ringi/decide c r {:by "suzuki" :decision :approve}))]
        (is (= :approved (ringi/status c r)))
        (is (= :unverified (:status (ringi/signer-check c r "yamada")))))))
  (testing "多額の借財は取締役会の決議がなければ承認済みにしない（362条4項）"
    (let [r {:ringi/id "r2" :ringi/amount 50000000 :ringi/category :large-borrowing :ringi/drafter "ito"
             :ringi/submitted-on "2026-11-18"
             :ringi/decisions [{:by "sato" :decision :approve} {:by "suzuki" :decision :approve} {:by "yamada" :decision :approve}]}]
      (is (= :awaiting-board (ringi/status c r)))
      (is (= :resolution-not-passed (:error (ringi/link-board-resolution c r {:matter :large-borrowing :result {:status :failed}}))))
      (is (= :resolution-matter-mismatch (:error (ringi/link-board-resolution c r {:matter :board-general :result {:status :passed}}))))
      (is (= :approved (ringi/status c (:ok (ringi/link-board-resolution c r {:matter :large-borrowing :result {:status :passed}}))))))))

(deftest chat-space
  (let [s (:space (app/sample))]
    (is (= "dm:3:ito:sato" (chat/dm-id "sato" "ito")) "長さ付きで、並びに依らない")
    (is (not= (chat/dm-id "x|y" "z") (chat/dm-id "x" "y|z")))
    (is (= :not-a-member (:error (chat/post s "board" {:message/id "m1" :message/author "ito" :message/body "hi" :message/at "t1"}))))
    (let [s (:ok (chat/post s "general" {:message/id "m1" :message/author "ito" :message/body "@sato 確認お願いします" :message/at "2026-11-18T09:00"}))
          s2 (:ok (chat/post s "general" {:message/id "m2" :message/author "sato" :message/body "了解" :message/at "2026-11-18T09:01" :message/parent "m1"}))]
      (is (= #{"sato"} (:message/mentions (first (chat/top-level s "general")))))
      (is (= :nested-thread (:error (chat/post s2 "general" {:message/id "m3" :message/author "ito" :message/body "x" :message/at "t" :message/parent "m2"}))))
      (is (= 1 (count (chat/replies s2 "general" "m1"))))
      (is (= 2 (chat/unread-count s2 "yamada" "general")))
      (is (= 0 (chat/unread-count (chat/mark-read s2 "yamada" "general" "2026-11-18T09:01") "yamada" "general"))))
    (is (= (chat/open-dm s "ito" "sato") (chat/open-dm (chat/open-dm s "ito" "sato") "sato" "ito")))))

(deftest deadlines
  (let [items (dl/upcoming (app/sample) "2026-11-18" 400)
        kinds (set (map :kind items))]
    (is (kinds :officer-term-expiry))
    (is (kinds :tax-return))
    (is (some #(and (= :tax-return (:kind %)) (= "2027-05-31" (:date %))) items))
    (is (some #(and (= :notice-latest (:kind %)) (= "2026-12-02" (:date %))) items) "取締役会の 1 週間前")
    (is (= (sort (map :date items)) (map :date items)))))

(deftest app-events
  (let [s (app/sample)
        as (fn [s who] (:state (app/dispatch s {:type :session/as :member who})))]
    (testing "通知前の会議では決議を記録しない"
      (is (= :notice-not-satisfied
             (:error (app/dispatch (as s "yamada") {:type :meeting/vote :id "board-2026-12" :agenda "a1"
                                                    :vote {:present #{"yamada" "sato"} :for #{"yamada" "sato"}}})))))
    (let [s (-> (as s "yamada")
                (app/dispatch {:type :meeting/notice-sent :id "board-2026-12" :on "2026-12-01"}) :state
                (app/dispatch {:type :meeting/vote :id "board-2026-12" :agenda "a1" :on "2026-12-10"
                               :vote {:present #{"yamada" "sato"} :for #{"yamada" "sato"}}}) :state)]
      (is (= :passed (get-in (app/resolution-ref s "board-2026-12" "a1") [:result :status])))
      (is (= [:meeting/notice-sent :meeting/vote] (map :type (:audit s))))
      (testing "会社の設定の誤りは受け付けない"
        (is (= :invalid-company (:error (app/dispatch s {:type :company/set :patch {:company/fy-end-month 13}})))))
      (testing "議題の種類が会議体に合わなければ追加しない"
        (is (= :invalid-matter (:error (app/dispatch s {:type :meeting/add :meeting {:meeting/id "x" :meeting/body :board :meeting/date "2026-12-20"
                                                                                  :meeting/agenda [{:agenda/id "a1" :agenda/matter :amend-articles}]}})))))
      (testing "取締役会の決議を稟議に結びつける（種類が一致するものだけ）"
        (let [s (-> (as s "ito")
                    (app/dispatch {:type :meeting/add :meeting {:meeting/id "board-loan" :meeting/body :board :meeting/title "臨時取締役会"
                                                               :meeting/date "2026-11-20" :meeting/all-consent-waiver? true
                                                               :meeting/agenda [{:agenda/id "a1" :agenda/title "借入" :agenda/matter :large-borrowing}]}}) :state
                    (as "yamada")
                    (app/dispatch {:type :meeting/vote :id "board-loan" :agenda "a1" :vote {:present #{"yamada" "sato" "suzuki"} :for #{"yamada" "sato"}}}) :state
                    (as "ito")
                    (app/dispatch {:type :ringi/create :ringi {:ringi/id "r1" :ringi/title "借入" :ringi/amount 30000000 :ringi/category :large-borrowing}}) :state
                    (app/dispatch {:type :ringi/submit :id "r1" :on "2026-11-18"}) :state)]
          (is (= :self-approval (:error (app/dispatch s {:type :ringi/decide :id "r1" :decision :approve}))))
          (let [s (reduce (fn [s who] (:state (app/dispatch (as s who) {:type :ringi/decide :id "r1" :decision :approve :on "2026-11-19"})))
                          s ["yamada" "sato" "suzuki"])]
            (is (= :awaiting-board (ringi/status (:company s) (app/find-by :ringi/id "r1" (:ringi s)))))
            (is (= :resolution-matter-mismatch (:error (app/dispatch s {:type :ringi/link-board :id "r1" :meeting "board-2026-12" :agenda "a1"}))))
            (let [s (:state (app/dispatch s {:type :ringi/link-board :id "r1" :meeting "board-loan" :agenda "a1"}))]
              (is (= :approved (ringi/status (:company s) (app/find-by :ringi/id "r1" (:ringi s))))))))))))

(deftest settings-ops
  (let [s (app/sample)]
    (testing "役員の追加: 変更登記の期限を予定に出す（915条）"
      (let [r (app/dispatch s {:type :officer/add :on "2026-11-18"
                               :officer {:officer/id "kato" :officer/name "加藤 四郎" :officer/roles [:director] :officer/elected-on "2026-11-20"}})]
        (is (nil? (:error r)))
        (is (= "加藤 四郎" (:officer/name (last (get-in r [:state :company :company/officers])))))
        (is (some #(= "2026-12-04" (:date %)) (filter #(= :registration (:kind %)) (dl/upcoming (:state r) "2026-11-18" 60))))))
    (testing "役員の追加の負の対照"
      (is (= [:duplicate-officer] (map :code (:problems (app/dispatch s {:type :officer/add :officer {:officer/id "yamada" :officer/name "x" :officer/roles [:director] :officer/elected-on "2026-11-20"}})))))
      (is (some #{:invalid-role} (map :code (:problems (app/dispatch s {:type :officer/add :officer {:officer/id "x" :officer/name "x" :officer/roles [:representative-member] :officer/elected-on "2026-11-20"}})))))
      (is (some #{:missing-election-date} (map :code (:problems (app/dispatch s {:type :officer/add :officer {:officer/id "x" :officer/name "x" :officer/roles [:director]}})))))
      (is (some #{:no-auditor-seat} (map :code (:problems (app/dispatch (assoc-in s [:company :company/auditor?] false) {:type :officer/add :officer {:officer/id "x" :officer/name "x" :officer/roles [:auditor] :officer/elected-on "2026-11-20"}}))))))
    (testing "メンバー: 追加と権限の変更"
      (let [s2 (:state (app/dispatch s {:type :member/add :member {:member/id "kimura" :member/name "木村 五郎" :member/permissions [:post]}}))]
        (is (= [:post] (:member/permissions (app/find-by :member/id "kimura" (get-in s2 [:company :company/members])))))
        (is (= :duplicate-member (:error (app/dispatch s2 {:type :member/add :member {:member/id "kimura" :member/name "x"}}))))
        (is (= [:approve :post] (:member/permissions (app/find-by :member/id "kimura" (get-in (:state (app/dispatch s2 {:type :member/set-permissions :id "kimura" :permissions [:post :approve]})) [:company :company/members])))))
        (is (= :invalid-permission (:error (app/dispatch s2 {:type :member/set-permissions :id "kimura" :permissions [:sign]}))))))
    (testing "定款の設定の負の対照"
      (let [set-arts (fn [m] (app/dispatch s {:type :company/set :patch {:company/articles (merge (get-in s [:company :company/articles]) m)}}))]
        (is (some #{:director-term-out-of-range} (map :code (:problems (set-arts {:director-term-years 11})))))
        (is (some #{:notice-shortening-not-allowed} (map :code (:problems (set-arts {:shareholder-notice-days 3})))))
        (is (some #{:quorum-below-one-third} (map :code (:problems (set-arts {:quorum {:officer [1 4]}})))))
        (is (some #{:board-notice-out-of-range} (map :code (:problems (set-arts {:board-notice-days 0})))))
        (is (nil? (:error (set-arts {:director-term-years 10 :board-notice-days 5 :quorum {:officer [1 3] :ordinary :none}}))))))))

(deftest i18n-complete
  (is (= {:ja-only [] :en-only []} (kaisha.i18n/missing-keys))))

(defn- evidence-file [id]
  (let [[_ law art] (re-matches #"(kaisha|hojinzei)/a(\d+)" (subs (str id) 1))]
    (str "docs/evidence/egov-20261118/" law "-" art ".json")))

(deftest law-sources
  (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %)) (vals law/sources)))
  #?(:clj
     (testing "記録した sha256 が、保存した e-Gov の応答と一致する"
       (doseq [[id {:keys [sha256]}] law/sources]
         (let [bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. (evidence-file id))))
               digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
           (is (= sha256 (apply str (map #(format "%02x" %) digest))) (str id))))))
  (is (= :unreviewed (:review/status law/pack)))
  (is (not (law/stale? "2026-11-18")))
  (is (law/stale? "2027-02-19"))
  (is (thrown? #?(:clj Exception :cljs js/Error) (law/cite :kaisha/a999)))
  (is (= "会社法 第368条" (law/short-title :kaisha/a368)))
  (is (= "法人税法 第74条" (law/short-title :hojinzei/a74))))

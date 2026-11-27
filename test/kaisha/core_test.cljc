(ns kaisha.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaisha.app :as app]
            [kaisha.approval :as approval]
            [kaisha.books :as books]
            [kaisha.chat :as chat]
            [kaisha.company :as co]
            [kaisha.date :as d]
            [kaisha.i18n]
            [kaisha.deadlines :as dl]
            [kaisha.law :as law]
            [kaisha.identity :as idn]
            [kaisha.meeting :as mt]
            [kaisha.procedures :as proc]
            [kaisha.ringi :as ringi]
            [kaisha.screen :as screen]))

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

(deftest state-v2
  (let [s (app/sample)]
    (testing "sample は schema v2 で actor を持つ"
      (is (= "itonami.kaisha.state.v2" (:schema s)))
      (is (= {:actor/id "ito" :actor/kind :human} (:actor s)))
      (is (nil? (:me s)))
      (is (app/valid-state? s))
      (is (not (contains? s :ops)))
      (is (not-any? #(contains? % :needs-board-resolution) (:ringi s))))
    (testing "actor の検査"
      (is (not (app/valid-state? (dissoc s :actor))))
      (is (not (app/valid-state? (assoc s :actor {:actor/id "ito"}))))
      (is (not (app/valid-state? (assoc s :actor {:actor/id "ito" :actor/kind :robot}))))
      (is (not (app/valid-state? (assoc s :actor {:actor/id "" :actor/kind :human}))))
      (is (not (app/valid-state? (assoc s :schema app/v1-schema)))))
    (testing "設定の「操作する人」は human"
      (let [next (:state (app/dispatch s {:type :session/as :member "yamada"}))]
        (is (= {:actor/id "yamada" :actor/kind :human} (:actor next)))
        (is (app/valid-state? next))))
    (testing "監査には actor の id と kind が入る"
      (let [ev {:type :member/add :at "2026-11-25T10:00"
                :member {:member/id "kimura" :member/name "木村 五郎" :member/permissions [:post]}}
            next (:state (app/dispatch s ev))
            entry (last (:audit next))]
        (is (= "ito" (:actor entry)))
        (is (= :human (:kind entry)))
        (is (= :member/add (:type entry)))
        (is (app/valid-state? next))))
    (testing "agent の操作も kind で区別して監査に残る"
      (let [agent (assoc s :actor {:actor/id "ito" :actor/kind :agent})
            next (:state (app/dispatch agent {:type :member/add :at "2026-11-25T10:01"
                                              :member {:member/id "sasaki" :member/name "佐々木 六郎"
                                                       :member/permissions [:post]}}))]
        (is (= :agent (:kind (last (:audit next)))))
        (is (app/valid-state? next))))))

(deftest state-v1-migration
  (let [v1 (-> (app/sample)
               (assoc :schema app/v1-schema :me "yamada")
               (dissoc :actor)
               (assoc :audit [{:at "2026-11-18" :actor "yamada" :type :company/set :target nil}]))
        s (app/parse-state (pr-str v1))]
    (testing "v1 の文字列を読み込み時に v2 へ移す"
      (is (= app/schema (:schema s)))
      (is (= {:actor/id "yamada" :actor/kind :human} (:actor s)))
      (is (nil? (:me s)))
      (is (= [{:at "2026-11-18" :actor "yamada" :kind :human :type :company/set :target nil}]
             (:audit s)))
      (is (app/valid-state? s))))
  (testing "v2 の文字列はそのまま読む"
    (is (= (app/sample) (app/parse-state (pr-str (app/sample))))))
  (testing "移行できない値は nil（画面は sample に戻す）"
    (is (nil? (app/parse-state "{:schema \"itonami.kaisha.state.v1\" :me 42}")))
    (is (nil? (app/parse-state "{:schema \"itonami.kaisha.state.v1\"}")))
    (is (nil? (app/parse-state "not edn")))
    (is (nil? (app/parse-state nil)))))

(deftest screen-status
  (let [s (app/sample)
        ringi-ready {:ringi/id "r1" :ringi/title "備品の購入" :ringi/amount 100000
                     :ringi/category :board-general :ringi/drafter "ito"
                     :ringi/submitted-on "2026-11-18" :ringi/decisions []}
        ringi-board {:ringi/id "r2" :ringi/title "借入" :ringi/amount 50000000
                     :ringi/category :large-borrowing :ringi/drafter "ito"
                     :ringi/submitted-on "2026-11-18"
                     :ringi/decisions [{:by "sato" :decision :approve}
                                       {:by "suzuki" :decision :approve}
                                       {:by "yamada" :decision :approve}]}
        fs-ready {:fs/rows (:rows (books/parse-trial-balance tb)) :fs/history []}
        verified (assoc-in s [:company :company/officers 0 :officer/authority-verified-on] "2026-11-18")
        proc-rec {:procedure/id "p1" :procedure/kind :head-office-move :moved-on "2026-11-20"}]
    (testing "ready: 7 画面"
      (is (= {:status :ready} (screen/status s :kaisha.schedule)))
      (is (= {:status :ready} (screen/status s :kaisha.meeting)))
      (is (= {:status :ready} (screen/status (assoc s :ringi [ringi-ready]) :kaisha.ringi)))
      (is (= {:status :ready} (screen/status (assoc s :financials fs-ready) :kaisha.books)))
      (is (= {:status :ready} (screen/status s :kaisha.chat)))
      (is (= {:status :ready} (screen/status (assoc verified :procedures [proc-rec]) :kaisha.procedures)))
      (is (= {:status :ready} (screen/status s :kaisha.settings))))
    (testing "empty"
      (is (= {:status :empty} (screen/status s :kaisha.ringi)))
      (is (= {:status :empty :reason :no-trial-balance} (screen/status s :kaisha.books)))
      (is (= {:status :empty} (screen/status s :kaisha.procedures)))
      (is (= {:status :empty} (screen/status (assoc s :space {:channels {}}) :kaisha.chat)))
      (is (= {:status :empty}
             (screen/status (-> s
                                (assoc :meetings [] :registrations [])
                                (assoc-in [:company :company/officers] [])
                                (assoc-in [:company :company/fy-end-month] nil))
                            :kaisha.schedule))))
    (testing "error"
      (is (= {:status :error :reason :invalid-state} (screen/status {} :kaisha.ringi)))
      (is (= {:status :error :reason :invalid-state}
             (screen/status (assoc s :actor nil) :kaisha.ringi)))
      (is (= {:status :error :reason :unknown-screen} (screen/status s :kaisha.unknown))))
    (testing "undetermined"
      (is (= {:status :undetermined :reason :missing-meeting-date}
             (screen/status (assoc-in s [:meetings 0 :meeting/date] "2026-02-30") :kaisha.meeting)))
      (is (= {:status :undetermined :reason :missing-election-date}
             (screen/status (assoc-in s [:company :company/officers 0]
                                      {:officer/id "yamada" :officer/roles [:representative-director]})
                            :kaisha.schedule)))
      (is (= {:status :undetermined :reason :unsupported-entity-form}
             (screen/status (assoc-in s [:company :company/form] :gk) :kaisha.settings)))
      (is (= {:status :undetermined :reason :unsupported-entity-form}
             (screen/status (-> s
                                (assoc :procedures [proc-rec])
                                (assoc-in [:company :company/form] :gk))
                            :kaisha.procedures))))
    (testing "blocked"
      (is (= {:status :blocked :reason :notice-too-late}
             (screen/status (assoc-in s [:meetings 0 :meeting/notice-sent-on] "2026-12-09")
                            :kaisha.meeting)))
      (is (= {:status :blocked :reason :board-resolution-required}
             (screen/status (assoc s :ringi [ringi-board]) :kaisha.ringi)))
      (is (= {:status :blocked :reason :unbalanced-trial-balance}
             (screen/status (assoc s :financials
                                   {:fs/rows [{:account "現金" :category :asset :amount 100}]
                                    :fs/history []})
                            :kaisha.books)))
      (is (= {:status :blocked :reason :representative-unconfirmed}
             (screen/status (assoc s :procedures [proc-rec]) :kaisha.procedures))))))

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

(deftest procedures
  (let [s (app/sample)
        c (:company s)
        verified (assoc-in c [:company/officers 0 :officer/authority-verified-on] "2026-11-18")
        move {:to {:country :jp :subdivision :jp.fixture/city-b} :moved-on "2026-11-20" :address-confirmed? true}]
    (testing "代表権が登記事項で未確認なら止める（egov の :representative-unconfirmed）"
      (is (= [:blocked :representative-unconfirmed] ((juxt :status :reason) (proc/head-office-move-plan c move "2026-11-18")))))
    (testing "確認済みなら、本店移転の変更登記を候補に出す（14 日以内・915条）。レビュー前なので提出の提案はしない"
      (let [r (proc/head-office-move-plan verified move "2026-11-18")
            cand (first (:candidates r))]
        (is (= :candidates (:status r)))
        (is (= :jp/honten-iten-toki (:procedure/id cand)))
        (is (= "2026-12-04" (get-in cand [:deadline :due])))
        (is (= {:registry-office-for :jp.fixture/city-b} (:submit-to cand)))
        (is (= [:jp/honten-iten-across-registry-offices] (:unknowns cand)))
        (is (= :withheld-unreviewed-pack (:submission-proposal r)))
        (is (= (:deadline (co/registration-deadline "2026-11-20")) (get-in cand [:deadline :due])) "kaisha の 915 条の期限と一致する")))
    (testing "負の対照"
      (is (= :unknown-subdivision (:reason (proc/head-office-move-plan verified (assoc-in move [:to :subdivision] :jp.fixture/nowhere) "2026-11-18"))))
      (is (= :cross-border-not-supported (:reason (proc/head-office-move-plan verified (assoc move :to {:country :us :subdivision :x}) "2026-11-18"))))
      (is (= :ambiguous-address (:reason (proc/head-office-move-plan verified (assoc move :address-confirmed? false) "2026-11-18"))))
      (is (= :unsupported-entity-form (:reason (proc/head-office-move-plan (assoc verified :company/form :godo
                                                                                   :company/officers [{:officer/id "g" :officer/roles [:representative-member] :officer/authority-verified-on "2026-11-18"}])
                                                                            move "2026-11-18"))))
      (is (= :pack-not-current (:reason (proc/head-office-move-plan verified move "2027-03-01"))) "見直し期限を過ぎた制度パックでは止める"))
    (testing "記録の操作"
      (is (= :same-address (:error (app/dispatch s {:type :procedure/head-office-move :id "p1" :to {:country :jp :subdivision :jp.fixture/city-a} :moved-on "2026-11-20"}))))
      (is (= :unknown-subdivision (:error (app/dispatch s {:type :company/address :address {:country :jp :subdivision :jp.fixture/nowhere}}))))
      (is (= 1 (count (:procedures (:state (app/dispatch s {:type :procedure/head-office-move :id "p1" :to (:to move) :moved-on "2026-11-20" :address-confirmed? true})))))))
    (testing "市町村は制度パックにあるものだけ"
      (is (= [:jp.fixture/city-a :jp.fixture/city-b] (map first (proc/subdivisions :jp)))))))

#?(:clj
   (deftest vendored-egov-pins
     (let [pins (slurp "vendor/egov/PINS.json")]
       (is (= 3 (count (re-seq #"[0-9a-f]{64}" pins))))
       (doseq [[_ f sha] (re-seq #"\"([^\"]+)\": \"([0-9a-f]{64})\"" pins)]
         (let [bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. (str "vendor/egov/" f))))
               digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
           (is (= sha (apply str (map #(format "%02x" %) digest))) f))))))

(deftest identity-integration
  (testing "法人番号の検査用数字（省令 第2条）: 国税庁の法人番号 7000012050002 で確かめる"
    (is (idn/corporate-number-valid? "7000012050002"))
    (is (idn/corporate-number-valid? "7123456789012") "架空の会社の番号も検査用数字が合う")
    (is (not (idn/corporate-number-valid? "8000012050002")) "検査用数字が違う")
    (is (not (idn/corporate-number-valid? "000012050002")) "12 桁（個人番号の桁数）は受け付けない")
    (is (not (idn/corporate-number-valid? "70000120500a2")))
    (is (not (idn/corporate-number-valid? nil))))
  (testing "会社の設定で法人番号を確かめる"
    (is (some #{:invalid-corporate-number} (map :code (co/validate (assoc c :company/corporate-number "7123456789013")))))
    (is (some #{:invalid-corporate-number} (map :code (co/validate (assoc c :company/corporate-number "123456789012")))))
    (is (not-any? #{:invalid-corporate-number} (map :code (co/validate (dissoc c :company/corporate-number))))))
  (testing "個人番号はどの仕組みでも扱わない"
    (is (not-any? #(some idn/never-handled (:data %)) idn/services))
    (is (not-any? #(some idn/never-handled (:data-categories %)) (idn/operations))))
  (testing "外部操作の宣言は形が正しく、broker が確認できるまで有効にならない"
    (is (seq (idn/operations)))
    (is (every? empty? (map idn/operation-problems (idn/operations))))
    (is (= [:origin-not-https :consent-not-each-operation]
           (idn/operation-problems {:origin "http://x.example" :method "GET" :path "/a" :query-names #{} :consent :batch})))
    (is (= [:handles-individual-number] (idn/operation-problems {:origin "https://x.example" :method "GET" :path "/a" :query-names #{} :consent :each-operation :data-categories #{:individual-number}})))
    (is (false? idn/broker-available?))
    (is (= [] (idn/enabled-operations)))
    (is (every? false? (map :enabled (idn/operations))))))

(deftest i18n-complete
  (is (= {:ja-only [] :en-only []} (kaisha.i18n/missing-keys))))

(defn- evidence-file [id]
  (let [[_ law art] (re-matches #"(kaisha|hojinzei|hojinbango-shoureki)/a(\d+)" (subs (str id) 1))]
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

(defn- as-member [s who]
  (:state (app/dispatch s {:type :session/as :member who})))

(deftest approval-preview-ringi
  (let [s (-> (app/sample)
              (as-member "yamada")
              (app/dispatch {:type :ringi/create :ringi {:ringi/id "r-prev" :ringi/title "PC購入" :ringi/amount 50000 :ringi/category :ordinary}}) :state
              (app/dispatch {:type :ringi/submit :id "r-prev" :on "2026-11-20"}) :state)]
    (testing "許可: 承認人数、取締役会決議不要、自己承認不可、取り消せないこと"
      (let [s-sato (as-member s "sato")
            p (approval/preview s-sato :ringi.decide {:id "r-prev" :decision :approve})]
        (is (:allowed? p))
        (is (nil? (:reason p)))
        (is (:irreversible? p))
        (is (:human-only? p))
        (is (some #(re-find #"承認人数" %) (:effects p)))
        (is (some #(re-find #"取締役会決議" %) (:effects p)))
        (is (some #(re-find #"自己承認" %) (:effects p)))
        (is (some #(re-find #"取り消せません" %) (:effects p)))))
    (testing "拒否: 起案者自身による自己承認"
      (let [p (approval/preview (as-member s "yamada") :ringi.decide {:id "r-prev" :decision :approve})]
        (is (false? (:allowed? p)))
        (is (= :self-approval (:reason p)))))
    (testing "拒否: 未提出・存在しない・権限なし"
      (let [s-unsub (assoc s :ringi [{:ringi/id "r-unsub" :ringi/drafter "sato" :ringi/amount 1000 :ringi/category :ordinary :ringi/decisions []}])]
        (is (= :not-submitted (:reason (approval/preview s-unsub :ringi.decide {:id "r-unsub"}))))
        (is (= :unknown-ringi (:reason (approval/preview s :ringi.decide {:id "r-nonexistent"}))))
        (is (= :no-approval-permission (:reason (approval/preview (as-member s "takahashi") :ringi.decide {:id "r-prev"}))))))))

(deftest approval-preview-officer-verify
  (let [s (app/sample)]
    (testing "許可: 代表取締役と証跡あり"
      (let [p (approval/preview s :officer.verify {:id "yamada" :evidence "登記事項証明書 2026-11-20 取得分"})]
        (is (:allowed? p))
        (is (nil? (:reason p)))
        (is (:irreversible? p))
        (is (:human-only? p))
        (is (some #(re-find #"山田 一郎" %) (:effects p)))
        (is (some #(re-find #"登記事項証明書" %) (:effects p)))
        (is (some #(re-find #"取り消せません" %) (:effects p)))))
    (testing "拒否: 証跡未入力時は案内と拒否"
      (let [p (approval/preview s :officer.verify {:id "yamada" :evidence ""})]
        (is (false? (:allowed? p)))
        (is (= :missing-evidence (:reason p)))
        (is (some #(re-find #"証跡の入力が必要です" %) (:effects p)))))
    (testing "平取締役は有効な確認日と証跡があっても preview と確定で拒否される"
      (let [ev {:type :officer/verify :id "sato" :on "2026-11-20"
                :evidence "法務局取得 履歴事項全部証明書"}
            p (approval/preview s :officer.verify ev)
            r (app/dispatch s ev)]
        (is (false? (:allowed? p)))
        (is (= :blocked (:status p)))
        (is (= :no-representative-authority (:reason p) (:error r)))
        (is (= s (:state r)))
        (is (= (:audit s) (get-in r [:state :audit])))))))

(deftest approval-preview-member-permissions
  (let [s (app/sample)]
    (testing "許可: 変更前 → 変更後の差分を示す"
      (let [p (approval/preview s :member.permissions.set {:id "takahashi" :permissions [:approve :post]})]
        (is (:allowed? p))
        (is (nil? (:reason p)))
        (is (:irreversible? p))
        (is (:human-only? p))
        (is (= [:post] (get-in p [:diff :before])))
        (is (= [:approve :post] (get-in p [:diff :after])))
        (is (= [:approve] (get-in p [:diff :added])))
        (is (empty? (get-in p [:diff :removed])))
        (is (some #(re-find #"高橋 三郎" %) (:effects p)))
        (is (some #(re-find #"変更前" %) (:effects p)))
        (is (some #(re-find #"変更後" %) (:effects p)))
        (is (some #(re-find #"追加される権限: 承認できる" %) (:effects p)))
        (is (some #(re-find #"取り消せません" %) (:effects p)))))
    (testing "拒否: 不正な権限・存在しないメンバー"
      (is (= :invalid-permission (:reason (approval/preview s :member.permissions.set {:id "takahashi" :permissions [:admin]}))))
      (is (= :unknown-member (:reason (approval/preview s :member.permissions.set {:id "unknown" :permissions [:post]})))))))

(deftest approval-preview-meeting-vote
  (let [s (-> (app/sample)
              (assoc-in [:meetings 0 :meeting/notice-sent-on] "2026-12-01")
              (assoc-in [:meetings 1 :meeting/notice-sent-on] "2027-06-15"))]
    (testing "許可: 可決・否決（どちらも結果記録として許可）"
      (let [p-pass (approval/preview s :meeting.vote {:id "board-2026-12" :agenda "a1"
                                                     :vote {:present #{"yamada" "sato"} :for #{"yamada" "sato"}}})
            p-fail (approval/preview s :meeting.vote {:id "board-2026-12" :agenda "a1"
                                                     :vote {:present #{"yamada" "sato"} :for #{"yamada"}}})]
        (is (:allowed? p-pass))
        (is (= :passed (:vote-result p-pass)))
        (is (some #(re-find #"可決として記録" %) (:effects p-pass)))
        (is (:allowed? p-fail))
        (is (= :failed (:vote-result p-fail)))
        (is (= :insufficient-votes (:reason p-fail)))
        (is (some #(re-find #"否決として記録" %) (:effects p-fail)))))
    (testing "許可: 出席ゼロの投票は定足数不足として記録"
      (let [ev {:type :meeting/vote :id "board-2026-12" :agenda "a1"
                :vote {:present #{} :for #{} :interested #{}}}
            p (approval/preview s :meeting.vote ev)
            result (get-in (app/dispatch s ev) [:state :meetings 0 :meeting/agenda 0 :agenda/result])]
        (is (:allowed? p))
        (is (= :failed (:status p) (:status result)))
        (is (= :quorum-not-met (:reason p) (:reason result)))))
    (testing "未入力: 初期フォームと出席未指定の投票は未判定"
      (let [args {:id "board-2026-12" :agenda "a1" :vote {:for #{} :interested #{}}}]
        (is (nil? (:result (approval/preview-board (:company s) #{} #{} #{}))))
        (is (false? (:allowed? (approval/preview s :meeting.vote args))))
        (is (false? (:allowed? (approval/preview s :meeting.vote (assoc-in args [:vote :present] nil)))))))
    (testing "blocked: 招集通知遅延・票数矛盾"
      (let [s-late (assoc-in s [:meetings 0 :meeting/notice-sent-on] "2026-12-09")
            p-late (approval/preview s-late :meeting.vote {:id "board-2026-12" :agenda "a1"
                                                           :vote {:present #{"yamada" "sato"} :for #{"yamada" "sato"}}})
            p-incons (approval/preview s :meeting.vote {:id "agm-2027" :agenda "a1"
                                                        :vote {:rights-total 100 :rights-present 50 :rights-for 60}})]
        (is (false? (:allowed? p-late)))
        (is (= :notice-too-late (:reason p-late)))
        (is (false? (:allowed? p-incons)))
        (is (= :blocked (:status p-incons)))
        (is (= :inconsistent-vote-counts (:reason p-incons)))))
    (testing "undetermined: 特殊決議"
      (let [s-special (assoc-in s [:meetings 1 :meeting/agenda 0 :agenda/matter] :share-transfer-restriction)
            p-und (approval/preview s-special :meeting.vote {:id "agm-2027" :agenda "a1"
                                                            :vote {:rights-total 100 :rights-present 100 :rights-for 100}})]
        (is (false? (:allowed? p-und)))
        (is (= :undetermined (:status p-und)))
        (is (= :special-special-resolution (:reason p-und)))))))

(deftest approval-preview-books-advance
  (let [s (-> (app/sample)
              (app/dispatch {:type :books/import :csv tb}) :state)]
    (testing "許可: 貸借一致、次の段階と決議不要"
      (let [p (approval/preview s :books.advance {:step :prepared})]
        (is (:allowed? p))
        (is (nil? (:reason p)))
        (is (:irreversible? p))
        (is (:human-only? p))
        (is (some #(re-find #"次の段階: 作成" %) (:effects p)))
        (is (some #(re-find #"必要な決議: なし" %) (:effects p)))
        (is (some #(re-find #"貸借一致" %) (:effects p)))))
    (testing "blocked: 貸借不一致"
      (let [s-unbal (assoc-in s [:financials :fs/rows] [{:account "現金" :category :asset :amount 1000}])
            p (approval/preview s-unbal :books.advance {:step :prepared})]
        (is (false? (:allowed? p)))
        (is (= :blocked (:status p)))
        (is (= :unbalanced-trial-balance (:reason p)))
        (is (some #(re-find #"貸借が一致しません" %) (:effects p)))))
    (testing "blocked: 取締役会決議が必要な段階で決議未指定"
      (let [s-prep (:state (app/dispatch s {:type :books/advance :step :prepared}))
            s-aud (:state (app/dispatch s-prep {:type :books/advance :step :audited}))
            p (approval/preview s-aud :books.advance {:step :board-approved})]
        (is (false? (:allowed? p)))
        (is (= :blocked (:status p)))
        (is (= :board-resolution-required (:reason p)))))))

(deftest agent-human-only-rejection
  (let [s (app/sample)
        agent-state (assoc s :actor {:actor/id "bot-1" :actor/kind :agent})]
    (testing "agent による human-only 6 op は dispatch で拒否される"
      (is (= :human-only (:error (app/dispatch agent-state {:type :ringi/decide :id "r1" :decision :approve}))))
      (is (= :human-only (:error (app/dispatch agent-state {:type :officer/verify :id "yamada" :evidence "証明書"}))))
      (is (= :human-only (:error (app/dispatch agent-state {:type :member/set-permissions :id "takahashi" :permissions [:post]}))))
      (is (= :human-only (:error (app/dispatch agent-state {:type :meeting/vote :id "board-2026-12" :agenda "a1" :vote {:present #{"yamada"} :for #{"yamada"}}}))))
      (is (= :human-only (:error (app/dispatch agent-state {:type :books/advance :step :prepared}))))
      (is (= :human-only (:error (app/dispatch agent-state {:type :data/reset-demo})))))
    (testing "拒否された場合 state は一切変更されない"
      (is (= agent-state (:state (app/dispatch agent-state {:type :officer/verify :id "yamada" :evidence "証明書"}))))
      (is (= agent-state (:state (app/dispatch agent-state {:type :data/reset-demo})))))
    (testing "agent でも起案・提出・チャット投稿は許可される"
      (let [r (app/dispatch agent-state {:type :ringi/create :ringi {:ringi/id "r-bot" :ringi/title "bot起案" :ringi/amount 1000 :ringi/category :ordinary}})]
        (is (nil? (:error r)))
        (is (= "bot-1" (:ringi/drafter (app/find-by :ringi/id "r-bot" (get-in r [:state :ringi])))))
        (is (= :agent (:kind (last (get-in r [:state :audit])))))))))

(deftest officer-verify-evidence-and-patch-guard
  (let [s (app/sample)]
    (testing "officer/verify で有効な確認日と証跡があれば state と audit に保存される"
      (let [r (app/dispatch s {:type :officer/verify :id "yamada" :on "2026-11-20" :evidence "法務局取得 履歴事項全部証明書"})]
        (is (nil? (:error r)))
        (let [o (app/find-by :officer/id "yamada" (get-in r [:state :company :company/officers]))]
          (is (= "法務局取得 履歴事項全部証明書" (:officer/authority-evidence o)))
          (is (= "2026-11-20" (:officer/authority-verified-on o))))
        (is (= :officer/verify (:type (last (get-in r [:state :audit])))))
        (is (= "yamada" (:target (last (get-in r [:state :audit])))))))
    (testing "officer/verify は確認日 on が nil/欠落していると :invalid-date で拒否され、state と audit は不変"
      (let [r (app/dispatch s {:type :officer/verify :id "yamada" :evidence "法務局取得 履歴事項全部証明書"})]
        (is (= :invalid-date (:error r)))
        (is (= s (:state r)) "state そのものが一切変わらないこと")
        (is (= (:audit s) (get-in r [:state :audit])) "audit に追記されないこと")
        (let [o (app/find-by :officer/id "yamada" (get-in r [:state :company :company/officers]))]
          (is (nil? (:officer/authority-verified-on o)))
          (is (nil? (:officer/authority-evidence o))))))
    (testing "officer/verify は不正な日付形式だと :invalid-date で拒否され、state と audit は不変"
      (let [r1 (app/dispatch s {:type :officer/verify :id "yamada" :on "invalid-date" :evidence "証明書"})
            r2 (app/dispatch s {:type :officer/verify :id "yamada" :on "2026-02-30" :evidence "証明書"})]
        (is (= :invalid-date (:error r1)))
        (is (= :invalid-date (:error r2)))
        (is (= s (:state r1)))
        (is (= s (:state r2)))
        (is (= (:audit s) (get-in r1 [:state :audit])))
        (is (= (:audit s) (get-in r2 [:state :audit])))))
    (testing "officer/verify は証跡未入力だと dispatch で拒否され、state と audit は不変"
      (let [r1 (app/dispatch s {:type :officer/verify :id "yamada" :on "2026-11-20" :evidence ""})
            r2 (app/dispatch s {:type :officer/verify :id "yamada" :on "2026-11-20"})]
        (is (= :missing-evidence (:error r1)))
        (is (= :missing-evidence (:error r2)))
        (is (= s (:state r1)))
        (is (= s (:state r2)))
        (is (= (:audit s) (get-in r1 [:state :audit])))
        (is (= (:audit s) (get-in r2 [:state :audit])))))
    (testing "company/set による役員・メンバーの直接改ざんは拒否される（P2 ガード）"
      (is (= :disallowed-patch (:error (app/dispatch s {:type :company/set :patch {:company/officers []}}))))
      (is (= :disallowed-patch (:error (app/dispatch s {:type :company/set :patch {:company/members []}})))))))

(deftest approval-dialog-cancellation-preserves-state
  (testing "preview の実行とダイアログのキャンセル（未 dispatch）で state と audit は完全に不変"
    (let [s (app/sample)
          initial-officers (get-in s [:company :company/officers])
          initial-audit (:audit s)]
      ;; 1. preview を実行して承認カードの内容を計算（dry-run）
      (let [p-init (approval/preview s :officer.verify {:id "yamada" :evidence ""})
            p-typed (approval/preview s :officer.verify {:id "yamada" :evidence "登記事項証明書確認"})]
        (is (false? (:allowed? p-init)))
        (is (= :missing-evidence (:reason p-init)))
        (is (true? (:allowed? p-typed)))
        (is (nil? (:reason p-typed))))
      ;; 2. キャンセル操作: dispatch を行わずにダイアログを閉じた状態
      ;; state, officers, audit すべてが操作前の状態と厳密に一致することを検証
      (is (= s (app/sample)))
      (is (= initial-officers (get-in s [:company :company/officers])))
      (is (= initial-audit (:audit s)))
      (let [o (app/find-by :officer/id "yamada" (get-in s [:company :company/officers]))]
        (is (nil? (:officer/authority-verified-on o)))
        (is (nil? (:officer/authority-evidence o))))

      ;; 3. 他の 5 つの op についても preview が副作用を持たず、キャンセル時に state を変更しないことを確認
      (let [ops [[:ringi.decide {:id "r1" :decision :approve}]
                 [:member.permissions.set {:id "hanako" :permissions [:approve]}]
                 [:meeting.vote {:id "board-2026-12" :agenda "a1"
                                 :vote {:present #{"yamada" "suzuki"} :for #{"yamada" "suzuki"} :interested #{}}}]
                 [:books.advance {:step :audited}]
                 [:data.reset-demo {}]]]
        (doseq [[op args] ops]
          (let [prev (approval/preview s op args)]
            (is (map? prev))
            (is (boolean? (:irreversible? prev)))
            (is (boolean? (:human-only? prev)))))
        (is (= s (app/sample)))))))

(deftest approval-preview-reset-counts
  (testing "空の state に対する reset-preview と approval/preview"
    (let [empty-counts (app/reset-preview {})
          p (approval/preview {} :data/reset-demo {})]
      (is (= 0 (:company empty-counts)))
      (is (= 0 (:officers empty-counts)))
      (is (= 0 (:members empty-counts)))
      (is (= 0 (:meetings empty-counts)))
      (is (= 0 (:ringi empty-counts)))
      (is (= 0 (:financials empty-counts)))
      (is (= 0 (:chat empty-counts)))
      (is (= 0 (:registrations empty-counts)))
      (is (= 0 (:procedures empty-counts)))
      (is (= 0 (:audit empty-counts)))
      (is (= empty-counts (:counts p)))
      (is (:allowed? p))
      (is (:irreversible? p))
      (is (:human-only? p))))

  (testing "架空データ（sample）に対する reset-preview と approval/preview"
    (let [s (app/sample)
          c (app/reset-preview s)
          p-ja (approval/preview s :data/reset-demo {})
          p-en (approval/preview s :data/reset-demo {} {:lang :en})]
      (is (= 1 (:company c)))
      (is (= 4 (:officers c)))
      (is (= 5 (:members c)))
      (is (= 2 (:meetings c)))
      (is (= 0 (:ringi c)))
      (is (= 0 (:financials c)))
      (is (= 0 (:chat c)))
      (is (= 0 (:registrations c)))
      (is (= 0 (:procedures c)))
      (is (= 0 (:audit c)))
      (is (= c (:counts p-ja)))
      (is (:allowed? p-ja))
      (is (nil? (:reason p-ja)))
      (is (:irreversible? p-ja))
      (is (:human-only? p-ja))
      ;; 日英 i18n の effects 検証（全 10 項目）
      (is (some #(re-find #"架空商事株式会社" %) (:effects p-ja)))
      (is (some #(re-find #"会社 1件、役員 4件、メンバー 5件、会議 2件、稟議 0件、会計 0件、会話 0件、登記 0件、手続き 0件、旧監査 0件" %) (:effects p-ja)))
      (is (some #(re-find #"取り消せません" %) (:effects p-ja)))
      (is (some #(re-find #"Kakū Shōji" %) (:effects p-en)))
      (is (some #(re-find #"1 company, 4 officers, 5 members, 2 meetings, 0 approvals, 0 accounts, 0 messages, 0 registrations, 0 procedures, 0 old audit logs" %) (:effects p-en)))
      (is (some #(re-find #"cannot be undone" %) (:effects p-en)))))

  (testing "追加後の state（全項目が1件以上）に対する reset-preview 件数と日英 effects の検証"
    (let [s0 (app/sample)
          s1 (:state (app/dispatch s0 {:type :member/add :at "2026-11-26T10:00"
                                       :member {:member/id "m-new" :member/name "新メンバー" :member/permissions [:post]}}))
          s2 (:state (app/dispatch s1 {:type :officer/add :at "2026-11-26T10:05"
                                       :officer {:officer/id "o-new" :officer/name "新役員" :officer/roles [:director] :officer/elected-on "2026-11-20"}}))
          s3 (:state (app/dispatch s2 {:type :meeting/add :at "2026-11-26T10:10"
                                       :meeting {:meeting/id "m-new" :meeting/body :board :meeting/title "臨時会" :meeting/date "2026-12-20" :meeting/agenda []}}))
          s4 (:state (app/dispatch s3 {:type :ringi/create :at "2026-11-26T10:15"
                                       :ringi {:ringi/id "r-new" :ringi/title "新規稟議" :ringi/amount 50000 :ringi/category :ordinary}}))
          s5 (:state (app/dispatch s4 {:type :books/import :at "2026-11-26T10:20" :csv tb}))
          s6 (:state (app/dispatch s5 {:type :books/advance :at "2026-11-26T10:25" :step :prepared}))
          s7 (:state (app/dispatch s6 {:type :chat/post :at "2026-11-26T10:30" :channel "general" :body "連絡事項です"}))
          s8 (:state (app/dispatch s7 {:type :procedure/head-office-move :at "2026-11-26T10:35"
                                       :id "proc-1" :to {:country :jp :subdivision :jp.fixture/city-b} :moved-on "2026-12-01"}))
          counts (app/reset-preview s8)
          p-ja (approval/preview s8 :data/reset-demo {})
          p-en (approval/preview s8 :data/reset-demo {} {:lang :en})]
      ;; 各項目が 1 件以上存在することの確認
      (is (pos? (:company counts)))
      (is (pos? (:officers counts)))
      (is (pos? (:members counts)))
      (is (pos? (:meetings counts)))
      (is (pos? (:ringi counts)))
      (is (pos? (:financials counts)))
      (is (pos? (:chat counts)))
      (is (pos? (:registrations counts)))
      (is (pos? (:procedures counts)))
      (is (pos? (:audit counts)))
      (is (= 1 (:company counts)))
      (is (= 5 (:officers counts)) "4 -> 5")
      (is (= 6 (:members counts)) "5 -> 6")
      (is (= 3 (:meetings counts)) "2 -> 3")
      (is (= 1 (:ringi counts)) "0 -> 1")
      (is (= 1 (:financials counts)) "0 -> 1")
      (is (= 1 (:chat counts)) "0 -> 1")
      (is (= 1 (:registrations counts)) "officer/add による就任登記が自動追加")
      (is (= 1 (:procedures counts)) "0 -> 1")
      (is (= 8 (:audit counts)) "各操作（8回の dispatch）による監査記録が蓄積")
      (is (= counts (:counts p-ja)))
      ;; 日英両方の承認カード effects に全項目の消去件数が表示されること
      (is (some #(re-find #"会社 1件、役員 5件、メンバー 6件、会議 3件、稟議 1件、会計 1件、会話 1件、登記 1件、手続き 1件、旧監査 8件" %) (:effects p-ja)))
      (is (some #(re-find #"1 company, 5 officers, 6 members, 3 meetings, 1 approvals, 1 accounts, 1 messages, 1 registrations, 1 procedures, 8 old audit logs" %) (:effects p-en))))))

(deftest reset-demo-dispatch-and-audit
  (testing "リセット実行で state は sample に戻り、audit は 1 行のみ残り、リセット前 actor と一致する"
    (let [s0 (app/sample)
          ;; 操作者を yamada（human）に切り替え
          s1 (:state (app/dispatch s0 {:type :session/as :member "yamada"}))
          ;; 複数操作を実行して state と audit を増やす
          s2 (:state (app/dispatch s1 {:type :member/add :at "2026-11-26T11:00"
                                       :member {:member/id "m-extra" :member/name "追加者" :member/permissions [:post]}}))
          s3 (:state (app/dispatch s2 {:type :officer/verify :id "yamada" :on "2026-11-20" :at "2026-11-26T11:05"
                                       :evidence "登記事項証明書確認"}))
          s4 (:state (app/dispatch s3 {:type :chat/post :channel "general" :body "リセット前テスト" :at "2026-11-26T11:10"}))]
      ;; リセット前検証
      (is (< 1 (count (:audit s4))))
      (is (= 6 (count (get-in s4 [:company :company/members]))))
      (is (some? (:officer/authority-verified-on (app/find-by :officer/id "yamada" (get-in s4 [:company :company/officers])))))
      ;; リセット実行
      (let [res (app/dispatch s4 {:type :data/reset-demo :at "2026-11-26T12:00"})
            next-state (:state res)]
        (is (nil? (:error res)))
        (is (app/valid-state? next-state) "スキーマ v2 の整合性を維持")
        (is (= app/schema (:schema next-state)))
        ;; データが sample に戻っていること
        (is (= (:company (app/sample)) (:company next-state)))
        (is (= (:meetings (app/sample)) (:meetings next-state)))
        (is (= (:ringi (app/sample)) (:ringi next-state)))
        (is (= (:financials (app/sample)) (:financials next-state)))
        (is (= (:space (app/sample)) (:space next-state)))
        (is (= (:procedures (app/sample)) (:procedures next-state)))
        ;; 監査は消える前のものは残らず、1 行のみ残ること
        (is (= 1 (count (:audit next-state))))
        (let [entry (first (:audit next-state))]
          (is (= :data/reset-demo (:type entry)))
          (is (= "yamada" (:actor entry)) "リセット前の actor/id と一致")
          (is (= :human (:kind entry)) "リセット前の actor/kind と一致")
          (is (= "2026-11-26T12:00" (:at entry)))))))

  (testing "agent による :data/reset-demo は拒否され、state・audit は一切変わらない"
    (let [s (app/sample)
          agent-state (assoc s :actor {:actor/id "bot-admin" :actor/kind :agent})
          res (app/dispatch agent-state {:type :data/reset-demo :at "2026-11-26T12:00"})]
      (is (= :human-only (:error res)))
      (is (= agent-state (:state res)))
      (is (= (:audit s) (get-in res [:state :audit]))))))


(ns kaisha.app
  "アプリの状態と操作。すべて純粋な関数で、画面（kaisha.ui）と test が同じ関数を使う。

  状態は端末の中だけにある。外部への送信・提出・署名はしない（descriptor の operations は空）。
  操作する人は :actor {:actor/id ... :actor/kind :human|:agent} で持つ。受け付けた操作は
  :audit に残す（誰が・いつ・何を・人か agent か）。"
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [kaisha.approval :as approval]
            [kaisha.books :as books]
            [kaisha.chat :as chat]
            [kaisha.company :as co]
            [kaisha.date :as d]
            [kaisha.meeting :as mt]
            [kaisha.procedures :as proc]
            [kaisha.ringi :as ringi]))

(def schema "itonami.kaisha.state.v2")
(def v1-schema "itonami.kaisha.state.v1")
(def actor-kinds #{:human :agent})

(def member-permissions
  "社内の権限。代表権（会社法 349条）とは別で、代表権の証明にならない。"
  #{:approve :post})

(defn sample
  "初回に表示する架空の会社。実在の会社・人物ではない。"
  []
  (let [members [{:member/id "yamada" :member/name "山田 一郎" :member/permissions [:approve :post]}
                 {:member/id "sato" :member/name "佐藤 花子" :member/permissions [:approve :post]}
                 {:member/id "suzuki" :member/name "鈴木 次郎" :member/permissions [:approve :post]}
                 {:member/id "takahashi" :member/name "高橋 三郎" :member/permissions [:post]}
                 {:member/id "ito" :member/name "伊藤 由紀" :member/permissions [:post]}]]
    {:schema schema
     :fixture true
     :actor {:actor/id "ito" :actor/kind :human}
     :company {:company/id "fixture-kk"
               :company/name "架空商事株式会社"
               :company/corporate-number "7123456789012"
               :company/form :kk
               :company/public? false
               :company/board? true
               :company/auditor? true
               :company/fy-end-month 3
               :company/address {:country :jp :subdivision :jp.fixture/city-a}
               :company/agm-within-months 3
               :company/articles {:director-term-years 2 :auditor-term-years 4}
               :company/approval-policy ringi/default-policy
               :company/members members
               :company/officers
               [{:officer/id "yamada" :officer/name "山田 一郎" :officer/roles [:representative-director] :officer/elected-on "2025-06-26"}
                {:officer/id "sato" :officer/name "佐藤 花子" :officer/roles [:director] :officer/elected-on "2025-06-26"}
                {:officer/id "suzuki" :officer/name "鈴木 次郎" :officer/roles [:director] :officer/elected-on "2025-06-26"}
                {:officer/id "takahashi" :officer/name "高橋 三郎" :officer/roles [:auditor] :officer/elected-on "2023-06-28"}]}
     :space (-> {:channels {} :read {}}
                (chat/add-channel (chat/channel "general" {:name "全体"}))
                (chat/add-channel (chat/channel "board" {:name "取締役会" :visibility :private
                                                         :members ["yamada" "sato" "suzuki" "takahashi"]}))
                (chat/add-channel (chat/channel "keiri" {:name "経理"})))
     :meetings [{:meeting/id "board-2026-12"
                 :meeting/body :board
                 :meeting/title "12月 定例取締役会"
                 :meeting/date "2026-12-10"
                 :meeting/agenda [{:agenda/id "a1" :agenda/title "本社の賃貸借契約の更新" :agenda/matter :board-general}]}
                {:meeting/id "agm-2027"
                 :meeting/body :shareholders
                 :meeting/title "第3期 定時株主総会"
                 :meeting/date "2027-06-25"
                 :meeting/agenda [{:agenda/id "a1" :agenda/title "計算書類の承認" :agenda/matter :approve-financials}
                                  {:agenda/id "a2" :agenda/title "取締役 3 名の選任" :agenda/matter :elect-officer}]}]
     :ringi []
     :financials {:fs/rows [] :fs/history []}
     :registrations []
     :procedures []
     :audit []}))

(defn find-by [k id xs] (some #(when (= id (get % k)) %) xs))

(defn- update-by [state coll k id f]
  (update state coll (fn [xs] (mapv #(if (= id (get % k)) (f %) %) xs))))

(defn resolution-ref
  "会議の議題から、稟議・計算書類に渡す決議の参照を作る。"
  [state meeting-id agenda-id]
  (let [m (find-by :meeting/id meeting-id (:meetings state))
        a (find-by :agenda/id agenda-id (:meeting/agenda m))]
    (when (and m a (:agenda/result a))
      {:id (str meeting-id "/" agenda-id)
       :body (:meeting/body m)
       :matter (:agenda/matter a)
       :result (:agenda/result a)})))

(defn reset-preview
  "現在の state から初期化によって失われる件数を計算する純粋関数。"
  [state]
  (approval/reset-preview state))

(defn- ok [state] {:state state})
(defn- err [state code & {:as more}] (merge {:state state :error code} more))

(defn- step [state {:keys [type on] :as ev}]
  (let [c (:company state)
        me (:actor/id (:actor state))]
    (case type
      :session/as
      (if (find-by :member/id (:member ev) (:company/members c))
        (ok (assoc state :actor {:actor/id (:member ev) :actor/kind :human}))
        (err state :unknown-member))

      :company/set
      (cond
        (or (contains? (:patch ev) :company/officers) (contains? (:patch ev) :company/members))
        (err state :disallowed-patch)
        :else
        (let [c2 (merge c (:patch ev))
              problems (filter #(= :error (:severity %)) (co/validate c2))]
          (if (seq problems)
            (err state :invalid-company :problems (vec problems))
            (ok (assoc state :company c2)))))

      :officer/verify
      (let [o (find-by :officer/id (:id ev) (:company/officers c))
            evidence (or (:evidence ev) (:officer/authority-evidence ev))]
        (cond
          (nil? o) (err state :unknown-officer)
          (str/blank? evidence) (err state :missing-evidence)
          (not (d/valid? on)) (err state :invalid-date)
          (not (approval/officer-verification-eligible? o)) (err state :no-representative-authority)
          :else (ok (update-in state [:company :company/officers]
                               (fn [os] (mapv #(if (= (:id ev) (:officer/id %))
                                                 (assoc % :officer/authority-verified-on on
                                                          :officer/authority-evidence evidence) %) os))))))

      :officer/add
      (let [o (select-keys (:officer ev) [:officer/id :officer/name :officer/roles :officer/elected-on])
            problems (co/officer-problems c o)]
        (if (seq problems)
          (err state :invalid-officer :problems (vec problems))
          (ok (-> state
                  (update-in [:company :company/officers] conj (update o :officer/roles vec))
                  ;; 役員の就任は登記事項の変更なので、2 週間以内の変更登記を予定に出す（915条）
                  (update :registrations (fnil conj [])
                          {:title (str (:officer/name o) " の就任") :on (:officer/elected-on o)})))))

      :member/add
      (let [m (:member ev)
            perms (set (:member/permissions m))]
        (cond
          (or (str/blank? (:member/id m)) (str/blank? (:member/name m))) (err state :invalid-member)
          (find-by :member/id (:member/id m) (:company/members c)) (err state :duplicate-member)
          (not (every? member-permissions perms)) (err state :invalid-permission)
          :else (ok (update-in state [:company :company/members] conj
                               {:member/id (:member/id m) :member/name (:member/name m)
                                :member/permissions (vec (sort perms))}))))

      :member/set-permissions
      (let [perms (set (:permissions ev))]
        (cond
          (not (find-by :member/id (:id ev) (:company/members c))) (err state :unknown-member)
          (not (every? member-permissions perms)) (err state :invalid-permission)
          :else (ok (update-in state [:company :company/members]
                               (fn [ms] (mapv #(if (= (:id ev) (:member/id %))
                                                 (assoc % :member/permissions (vec (sort perms))) %) ms))))))

      :company/address
      (let [{:keys [country subdivision]} (:address ev)]
        (if (and country subdivision (proc/subdivision-name country subdivision))
          (ok (assoc-in state [:company :company/address] {:country country :subdivision subdivision}))
          (err state :unknown-subdivision)))

      :procedure/head-office-move
      (let [{:keys [to moved-on address-confirmed?]} ev]
        (cond
          (not (d/valid? moved-on)) (err state :missing-moved-on)
          (nil? (:country to)) (err state :missing-destination)
          (= to (:company/address c)) (err state :same-address)
          :else (ok (update state :procedures (fnil conj [])
                            {:procedure/id (:id ev) :procedure/kind :head-office-move :to to
                             :moved-on moved-on :address-confirmed? (boolean address-confirmed?)
                             :recorded-on on :recorded-by me}))))

      :meeting/add
      (let [m (:meeting ev)]
        (cond
          (not (#{:board :shareholders} (:meeting/body m))) (err state :invalid-meeting)
          (not (d/valid? (:meeting/date m))) (err state :missing-meeting-date)
          (find-by :meeting/id (:meeting/id m) (:meetings state)) (err state :duplicate-meeting)
          (and (= :board (:meeting/body m)) (not (:company/board? c))) (err state :no-board)
          (not (every? #(mt/matter-valid? (:meeting/body m) (:agenda/matter %)) (:meeting/agenda m)))
          (err state :invalid-matter)
          :else (ok (update state :meetings conj (update m :meeting/agenda #(vec (or % [])))))))

      :meeting/notice-sent
      (if (and (find-by :meeting/id (:id ev) (:meetings state)) (d/valid? on))
        (ok (update-by state :meetings :meeting/id (:id ev) #(assoc % :meeting/notice-sent-on on)))
        (err state :unknown-meeting))

      :meeting/vote
      (let [m (find-by :meeting/id (:id ev) (:meetings state))
            a (find-by :agenda/id (:agenda ev) (:meeting/agenda m))
            notice (when m (mt/notice-check c m))
            result (when a
                     (if (= :board (:meeting/body m))
                       (mt/resolve-board c (:vote ev))
                       (mt/resolve-shareholders c (assoc (:vote ev) :matter (:agenda/matter a)))))]
        (cond
          (nil? a) (err state :unknown-agenda)
          (not= :ok (:status notice)) (err state :notice-not-satisfied :notice notice)
          (#{:blocked :undetermined} (:status result)) (err state :vote-not-countable :result result)
          :else (ok (update-by state :meetings :meeting/id (:id ev)
                               (fn [m] (update m :meeting/agenda
                                               (fn [as] (mapv #(if (= (:agenda ev) (:agenda/id %))
                                                                 (assoc % :agenda/vote (:vote ev) :agenda/result result) %)
                                                              as))))))))

      :ringi/create
      (let [r (:ringi ev)]
        (cond
          (find-by :ringi/id (:ringi/id r) (:ringi state)) (err state :duplicate-ringi)
          (not (and (integer? (:ringi/amount r)) (not (neg? (:ringi/amount r))))) (err state :invalid-amount)
          :else (ok (update state :ringi conj (assoc r :ringi/drafter me :ringi/decisions [])))))

      :ringi/submit
      (let [r (find-by :ringi/id (:id ev) (:ringi state))]
        (cond (nil? r) (err state :unknown-ringi)
              (not= me (:ringi/drafter r)) (err state :not-drafter)
              (:ringi/submitted-on r) (err state :already-submitted)
              :else (ok (update-by state :ringi :ringi/id (:id ev) #(assoc % :ringi/submitted-on on)))))

      :ringi/decide
      (let [r (find-by :ringi/id (:id ev) (:ringi state))
            res (when r (ringi/decide c r {:by me :decision (:decision ev) :on on}))]
        (cond (nil? r) (err state :unknown-ringi)
              (:error res) (err state (:error res))
              :else (ok (update-by state :ringi :ringi/id (:id ev) (constantly (:ok res))))))

      :ringi/link-board
      (let [r (find-by :ringi/id (:id ev) (:ringi state))
            ref (resolution-ref state (:meeting ev) (:agenda ev))
            res (when (and r ref (= :board (:body ref))) (ringi/link-board-resolution c r ref))]
        (cond (nil? r) (err state :unknown-ringi)
              (or (nil? ref) (not= :board (:body ref))) (err state :board-resolution-required)
              (:error res) (err state (:error res))
              :else (ok (update-by state :ringi :ringi/id (:id ev) (constantly (:ok res))))))

      :books/import
      (let [{:keys [rows errors]} (books/parse-trial-balance (:csv ev))]
        (cond (seq (:fs/history (:financials state))) (err state :financials-in-progress)
              (seq errors) (err state :invalid-trial-balance :errors errors)
              (empty? rows) (err state :no-trial-balance)
              :else (ok (assoc state :financials {:fs/rows rows :fs/history [] :fs/imported-on on}))))

      :books/advance
      (let [ref (when (:meeting ev) (resolution-ref state (:meeting ev) (:agenda ev)))
            res (books/advance c (:financials state)
                               {:step (:step ev) :on on :actor me :resolution ref})]
        (if (:error res)
          (err state (:error res))
          (ok (assoc state :financials (:ok res)))))

      :registration/add
      (if (and (seq (:title ev)) (d/valid? (:change-on ev)))
        (ok (update state :registrations (fnil conj []) (cond-> {:title (:title ev) :on (:change-on ev)}
                                                           (:kind ev) (assoc :kind (:kind ev)))))
        (err state :invalid-registration))

      :chat/post
      (let [space (:space state)
            n (count (mapcat :channel/messages (vals (:channels space))))
            res (chat/post space (:channel ev)
                           (cond-> {:message/id (str "m" (inc n) "-" (:at ev))
                                    :message/author me
                                    :message/body (:body ev)
                                    :message/at (:at ev)}
                             (:parent ev) (assoc :message/parent (:parent ev))
                             (:ref ev) (assoc :message/ref (:ref ev))))]
        (if (:error res) (err state (:error res)) (ok (assoc state :space (:ok res)))))

      :chat/read
      (ok (update state :space chat/mark-read me (:channel ev) (:at ev)))

      :chat/open-dm
      (if (and (find-by :member/id (:with ev) (:company/members c)))
        (ok (update state :space chat/open-dm me (:with ev)))
        (err state :unknown-member))

      :data/reset-demo
      (ok (sample))

      (err state :unknown-event))))

(def ^:private unaudited #{:chat/read :session/as})

(defn dispatch
  "操作を 1 つ適用する。返り値 {:state s} または {:state 元の s :error kw ...}。"
  [state ev]
  (let [kind (get-in state [:actor :actor/kind])]
    (if (and (= :agent kind) (approval/human-only? (:type ev)))
      (err state :human-only)
      (let [res (step state ev)]
        (if (or (:error res) (unaudited (:type ev)))
          res
          (update-in res [:state :audit] (fnil conj [])
                     {:at (or (:at ev) (:on ev)) :actor (:actor/id (:actor state))
                      :kind (:actor/kind (:actor state)) :type (:type ev)
                      :target (or (:target ev) (:id ev) (:channel ev) (get-in ev [:ringi :ringi/id])
                                  (get-in ev [:meeting :meeting/id]) (get-in ev [:officer :officer/id])
                                  (get-in ev [:member :member/id]))}))))))

(defn- valid-actor? [a]
  (and (map? a) (string? (:actor/id a)) (not (str/blank? (:actor/id a)))
       (contains? actor-kinds (:actor/kind a))))

(defn- valid-audit? [audit]
  (and (vector? audit)
       (every? #(and (map? %) (string? (:actor %)) (contains? actor-kinds (:kind %))) audit)))

(defn valid-state? [s]
  (and (map? s) (= schema (:schema s)) (valid-actor? (:actor s))
       (map? (:company s)) (map? (:space s))
       (or (nil? (:procedures s)) (vector? (:procedures s)))
       (vector? (:meetings s)) (vector? (:ringi s))
       (valid-audit? (:audit s))))

(defn migrate-state
  "v1 の状態を v2 に移す。:me を :actor（:human）にし、監査の各項目に :kind :human を足す。
  移せない値は nil（呼び出し側が sample に戻す）。"
  [s]
  (when (and (map? s) (= v1-schema (:schema s)) (string? (:me s)) (not (str/blank? (:me s))))
    (let [next (-> s
                   (dissoc :me)
                   (assoc :schema schema
                          :actor {:actor/id (:me s) :actor/kind :human})
                   (update :audit
                           #(mapv (fn [h]
                                    (if (and (map? h) (:actor h))
                                      (assoc h :kind (or (:kind h) :human))
                                      h))
                                  (or % []))))]
      (when (valid-state? next) next))))

(defn parse-state
  "保存した文字列を schema v2 の状態にする。v1 は移行する。壊れた値・移行できない v1 は nil。"
  [raw]
  (when (and (string? raw) (not (str/blank? raw)))
    (let [s (try (edn/read-string raw)
                 (catch #?(:clj Exception :cljs :default) _ nil))]
      (if (valid-state? s) s (migrate-state s)))))

(ns kaisha.approval
  "不可逆・外部・権限変更の操作の preview（dry-run）と承認判定。
  すべて純粋な関数で、state を変更しない。"
  (:require [clojure.string :as str]
            [kaisha.books :as books]
            [kaisha.company :as co]
            [kaisha.meeting :as mt]
            [kaisha.ringi :as ringi]))

(def member-permissions #{:approve :post})

(def human-only-ops
  "人だけが判断・承認できる操作。agent は起案・提出まで。"
  #{:ringi.decide :ringi/decide
    :officer.verify :officer/verify
    :member.permissions.set :member/set-permissions
    :meeting.vote :meeting/vote
    :books.advance :books/advance})

(defn normalize-op
  "op の表記ゆれ（keyword の namespace 有無や文字列）を揃える。"
  [op]
  (case op
    (:ringi.decide :ringi/decide "ringi.decide") :ringi.decide
    (:officer.verify :officer/verify "officer.verify") :officer.verify
    (:member.permissions.set :member/set-permissions "member.permissions.set") :member.permissions.set
    (:meeting.vote :meeting/vote "meeting.vote") :meeting.vote
    (:books.advance :books/advance "books.advance") :books.advance
    op))

(defn human-only?
  "人による操作が必須の op か。"
  [op]
  (boolean (contains? human-only-ops op)))

(defn irreversible?
  "一度実行すると取り消せない操作か。"
  [op]
  (boolean (contains? human-only-ops op)))

(defn- find-by [k id xs] (some #(when (= id (get % k)) %) xs))

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

;; ---------------------------------------------------------------- 会議の試算（UI から移動）

(defn preview-shareholders
  "株主総会決議の試算。必要数・可否・blocked/undetermined の理由。"
  [c matter total present for]
  (let [th (mt/shareholder-thresholds c matter total present)
        r (when (and total present for)
            (mt/resolve-shareholders c {:matter matter :rights-total total :rights-present present :rights-for for}))]
    {:thresholds th
     :result r
     :status (or (:status r) (:status th))
     :reason (or (:reason r) (:reason th))
     :passed? (= :passed (:status r))
     :failed? (= :failed (:status r))
     :blocked? (= :blocked (or (:status r) (:status th)))
     :undetermined? (= :undetermined (or (:status r) (:status th)))}))

(defn preview-board
  "取締役会決議の試算。必要数・可否・blocked/undetermined の理由。"
  [c present for interested & [{:keys [submitted?]}]]
  (let [eligible-present (count (remove (set interested) (set present)))
        th (mt/board-thresholds c interested eligible-present)
        r (when (or submitted? (seq present))
            (mt/resolve-board c {:present present :for for :interested interested}))]
    {:thresholds th
     :result r
     :status (:status r)
     :reason (:reason r)
     :passed? (= :passed (:status r))
     :failed? (= :failed (:status r))
     :blocked? (= :blocked (:status r))
     :undetermined? (= :undetermined (:status r))}))

;; ---------------------------------------------------------------- 各 op の preview

(defn- preview-ringi-decide [state args opts]
  (let [lang (or (:lang opts) (:lang args) :ja)
        c (:company state)
        rid (or (:id args) (:ringi/id args))
        decision (or (:decision args) :approve)
        r (find-by :ringi/id rid (:ringi state))
        me (get-in state [:actor :actor/id])
        actor-m (find-by :member/id me (:company/members c))]
    (cond
      (nil? r)
      {:allowed? false :reason :unknown-ringi :effects [] :irreversible? true :human-only? true}

      (nil? (:ringi/submitted-on r))
      {:allowed? false :reason :not-submitted
       :effects [(if (= :ja lang) "稟議がまだ提出されていません。" "Ringi has not been submitted yet.")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (#{:approved :rejected} (ringi/status c r))
      {:allowed? false :reason :already-closed
       :effects [(if (= :ja lang) "この稟議はすでに確定しています。" "Ringi is already closed.")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (= me (:ringi/drafter r))
      {:allowed? false :reason :self-approval
       :effects [(if (= :ja lang) "自己承認: 不可（起案者自身は承認・却下できません）"
                                  "Self-approval: Not allowed (drafter cannot decide)")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (nil? actor-m)
      {:allowed? false :reason :unknown-member :effects [] :irreversible? true :human-only? true}

      (not (contains? (set (:member/permissions actor-m)) :approve))
      {:allowed? false :reason :no-approval-permission
       :effects [(if (= :ja lang) "この操作者には承認権限がありません。" "No approval permission.")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (some #(= me (:by %)) (:ringi/decisions r))
      {:allowed? false :reason :already-decided
       :effects [(if (= :ja lang) "この操作者はすでに判断を記録しています。" "Already decided.")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (not (#{:approve :reject} decision))
      {:allowed? false :reason :invalid-decision :effects [] :irreversible? true :human-only? true}

      :else
      (let [{:keys [approvers board-resolution?]} (ringi/route c r)
            approved-count (count (filter #(= :approve (:decision %)) (:ringi/decisions r)))
            next-approved (if (= decision :approve) (inc approved-count) approved-count)
            board-text (if (= :ja lang)
                         (if board-resolution?
                           "取締役会決議の要否: 必要（会社法 第362条第4項）"
                           "取締役会決議の要否: 不要")
                         (if board-resolution?
                           "Board resolution: Required (Companies Act Art. 362(4))"
                           "Board resolution: Not required"))
            approvers-text (if (= :ja lang)
                             (if (= decision :approve)
                               (str "承認人数: " approved-count "/" approvers " 人 → " next-approved "/" approvers " 人")
                               "稟議を却下します")
                             (if (= decision :approve)
                               (str "Approvers: " approved-count "/" approvers " -> " next-approved "/" approvers)
                               "Reject this ringi"))
            self-approval-text (if (= :ja lang)
                                 "自己承認の可否: 不可（起案者と異なるメンバーによる承認）"
                                 "Self-approval: Not allowed (confirmed by another approver)")
            irreversible-text (if (= :ja lang)
                                "この操作は取り消せません。"
                                "This operation cannot be undone.")]
        {:allowed? true
         :reason nil
         :effects [approvers-text board-text self-approval-text irreversible-text]
         :irreversible? true
         :human-only? true}))))

(defn officer-verification-eligible?
  "登記事項による代表権確認の対象役職かどうか。"
  [officer]
  (boolean (some #{:representative-director :representative-member} (:officer/roles officer))))

(defn- preview-officer-verify [state args opts]
  (let [lang (or (:lang opts) (:lang args) :ja)
        c (:company state)
        oid (or (:id args) (:officer/id args))
        evidence (or (:evidence args) (:officer/authority-evidence args))
        o (find-by :officer/id oid (:company/officers c))]
    (cond
      (nil? o)
      {:allowed? false :reason :unknown-officer :effects [] :irreversible? true :human-only? true}

      (not (officer-verification-eligible? o))
      {:allowed? false
       :status :blocked
       :reason :no-representative-authority
       :effects [(if (= :ja lang)
                   (str "確認対象: " (:officer/name o) "（代表権を持たない役員です）")
                   (str "Target: " (:officer/name o) " (Officer has no representative authority)"))
                 (if (= :ja lang)
                   "代表権のない役員は代表権確認の対象外です。"
                   "Officers without representative authority cannot be verified.")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (str/blank? evidence)
      {:allowed? false
       :reason :missing-evidence
       :effects [(if (= :ja lang)
                   (str "確認対象: " (:officer/name o) "（代表権の確認）")
                   (str "Target: " (:officer/name o) " (Representative authority)"))
                 (if (= :ja lang)
                   "証跡の入力が必要です（登記事項証明書の参照番号や確認メモ等を入力してください）。"
                   "Evidence is required (enter registration certificate reference or memo).")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      :else
      {:allowed? true
       :reason nil
       :effects [(if (= :ja lang)
                   (str "確認対象: " (:officer/name o) "（代表権の確認）")
                   (str "Target: " (:officer/name o) " (Representative authority)"))
                 (if (= :ja lang)
                   (str "確認証跡: " (str/trim evidence))
                   (str "Evidence: " (str/trim evidence)))
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true})))

(defn- preview-member-permissions [state args opts]
  (let [lang (or (:lang opts) (:lang args) :ja)
        c (:company state)
        mid (or (:id args) (:member/id args))
        new-perms (set (:permissions args))
        m (find-by :member/id mid (:company/members c))]
    (cond
      (nil? m)
      {:allowed? false :reason :unknown-member :effects [] :irreversible? true :human-only? true}

      (not (every? member-permissions new-perms))
      {:allowed? false :reason :invalid-permission :effects [] :irreversible? true :human-only? true}

      :else
      (let [old-perms (set (:member/permissions m))
            perm-label (fn [p] (if (= :ja lang)
                                 (case p :approve "承認できる" :post "投稿できる" (name p))
                                 (case p :approve "Can approve" :post "Can post" (name p))))
            format-perms (fn [ps]
                           (if (seq ps)
                             (str/join " · " (map perm-label (sort ps)))
                             (if (= :ja lang) "なし" "None")))
            added (sort (remove old-perms new-perms))
            removed (sort (remove new-perms old-perms))
            target-text (if (= :ja lang)
                          (str "対象メンバー: " (:member/name m))
                          (str "Target member: " (:member/name m)))
            before-text (if (= :ja lang)
                          (str "変更前: " (format-perms old-perms))
                          (str "Before: " (format-perms old-perms)))
            after-text (if (= :ja lang)
                         (str "変更後: " (format-perms new-perms))
                         (str "After: " (format-perms new-perms)))
            detail-text (cond
                          (and (seq added) (seq removed))
                          (if (= :ja lang)
                            (str "追加: " (format-perms added) " / 削除: " (format-perms removed))
                            (str "Added: " (format-perms added) " / Removed: " (format-perms removed)))
                          (seq added)
                          (if (= :ja lang)
                            (str "追加される権限: " (format-perms added))
                            (str "Added permissions: " (format-perms added)))
                          (seq removed)
                          (if (= :ja lang)
                            (str "削除される権限: " (format-perms removed))
                            (str "Removed permissions: " (format-perms removed)))
                          :else
                          (if (= :ja lang) "権限に変更はありません" "No change in permissions"))
            irreversible-text (if (= :ja lang)
                                "この操作は取り消せません（再度変更することは可能です）。"
                                "This operation cannot be undone (can be reconfigured later).")]
        {:allowed? true
         :reason nil
         :diff {:before (vec (sort old-perms))
                :after (vec (sort new-perms))
                :added (vec added)
                :removed (vec removed)}
         :effects [target-text before-text after-text detail-text irreversible-text]
         :irreversible? true
         :human-only? true}))))

(defn- preview-meeting-vote [state args opts]
  (let [lang (or (:lang opts) (:lang args) :ja)
        c (:company state)
        mid (or (:id args) (:meeting args))
        aid (or (:agenda args) (:agenda-id args))
        m (find-by :meeting/id mid (:meetings state))
        a (find-by :agenda/id aid (:meeting/agenda m))
        vote (:vote args)]
    (cond
      (nil? m)
      {:allowed? false :reason :unknown-meeting :effects [] :irreversible? true :human-only? true}

      (nil? a)
      {:allowed? false :reason :unknown-agenda :effects [] :irreversible? true :human-only? true}

      :else
      (let [notice (mt/notice-check c m)
            board? (= :board (:meeting/body m))
            prev (if board?
                   (preview-board c (:present vote) (:for vote) (:interested vote)
                                  {:submitted? (some? (:present vote))})
                   (preview-shareholders c (:agenda/matter a) (:rights-total vote) (:rights-present vote) (:rights-for vote)))
            r (:result prev)
            notice-ok? (= :ok (:status notice))
            status (cond
                     (not notice-ok?) (:status notice)
                     (:status r) (:status r)
                     :else (:status prev))
            reason (cond
                     (not notice-ok?) (:reason notice)
                     (:reason r) (:reason r)
                     :else (:reason prev))
            allowed? (and notice-ok? (contains? #{:passed :failed} (:status r)))
            effects
            (cond
              (not notice-ok?)
              [(if (= :ja lang)
                 (str "招集通知の期限を満たしていません（理由: " (name (or reason :notice-not-satisfied)) "）")
                 (str "Notice deadline not satisfied (reason: " (name (or reason :notice-not-satisfied)) ")"))
               (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]

              (= :blocked status)
              [(if (= :ja lang)
                 (str "票数の計算ができません（理由: " (name (or reason :vote-not-countable)) "）")
                 (str "Vote count cannot be calculated (reason: " (name (or reason :vote-not-countable)) ")"))
               (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]

              (= :undetermined status)
              [(if (= :ja lang)
                 (str "この決議の要件は計算しません（理由: " (name (or reason :undetermined)) "）")
                 (str "Requirements for this resolution are not calculated (reason: " (name (or reason :undetermined)) ")"))
               (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]

              (= :passed (:status r))
              [(if (= :ja lang) "決議結果: 可決として記録します" "Resolution result: Will be recorded as passed")
               (if (= :ja lang) "定足数と可決要件を満たしています" "Quorum and pass requirements are met")
               (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]

              (= :failed (:status r))
              [(if (= :ja lang) "決議結果: 否決として記録します" "Resolution result: Will be recorded as failed")
               (if (= :ja lang)
                 (str "理由: " (if (= :quorum-not-met reason) "定足数を満たしていません" "賛成が足りません"))
                 (str "Reason: " (if (= :quorum-not-met reason) "Quorum not met" "Insufficient votes")))
               (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]

              :else
              [(if (= :ja lang) "票数を入力してください" "Please enter the vote counts")
               (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")])]
        (cond-> {:allowed? allowed?
                 :status status
                 :reason reason
                 :effects effects
                 :irreversible? true
                 :human-only? true}
          (:status r) (assoc :vote-result (:status r)))))))

(defn- preview-books-advance [state args opts]
  (let [lang (or (:lang opts) (:lang args) :ja)
        c (:company state)
        fs (:financials state)
        nxt (books/next-step c fs)
        step (or (:step args) (:step nxt))
        summary (books/summarize (:fs/rows fs))
        ref (when (:meeting args) (resolution-ref state (:meeting args) (:agenda args)))
        step-label (fn [s]
                     (if (= :ja lang)
                       (case s
                         :prepared "作成（会社法 第435条）"
                         :audited "監査役の監査（会社法 第436条）"
                         :board-approved "取締役会の承認（会社法 第436条）"
                         :agm-approved "定時株主総会の承認（会社法 第438条）"
                         :published "決算公告（会社法 第440条）"
                         (name s))
                       (case s
                         :prepared "Prepared (Companies Act Art. 435)"
                         :audited "Audited (Companies Act Art. 436)"
                         :board-approved "Board approval (Companies Act Art. 436)"
                         :agm-approved "AGM approval (Companies Act Art. 438)"
                         :published "Public notice (Companies Act Art. 440)"
                         (name s))))]
    (cond
      (nil? nxt)
      {:allowed? false :reason :already-complete
       :effects [(if (= :ja lang) "すべての段階が記録済みです" "All steps have been recorded")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (not= step (:step nxt))
      {:allowed? false :reason :out-of-order :expected (:step nxt)
       :effects [(if (= :ja lang)
                   (str "段階の順序が違います。次は「" (step-label (:step nxt)) "」です。")
                   (str "Step out of order. Next step is \"" (step-label (:step nxt)) "\"."))
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (empty? (:fs/rows fs))
      {:allowed? false :reason :no-trial-balance
       :effects [(if (= :ja lang) "試算表が取り込まれていません" "No trial balance imported")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (and (= step :prepared) (not (:balanced? summary)))
      (let [diff (- (:assets summary) (+ (:liabilities summary) (:equity summary) (:net-income summary)))]
        {:allowed? false
         :status :blocked
         :reason :unbalanced-trial-balance
         :effects [(if (= :ja lang)
                     (str "次の段階: " (step-label step))
                     (str "Next step: " (step-label step)))
                   (if (= :ja lang)
                     "必要な決議: なし"
                     "Required resolution: None")
                   (if (= :ja lang)
                     (str "貸借の状態: ✕ 貸借が一致しません（差額: " diff " 円）")
                     (str "Balance status: ✕ Unbalanced (difference: JPY " diff ")"))
                   (if (= :ja lang)
                     "試算表の貸借が一致するまで作成を記録できません。"
                     "Cannot record preparation until trial balance balances.")
                   (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
         :irreversible? true :human-only? true})

      (and (= step :board-approved)
           (not (and (= :passed (get-in ref [:result :status]))
                     (= :approve-financials (:matter ref)))))
      {:allowed? false
       :status :blocked
       :reason :board-resolution-required
       :effects [(if (= :ja lang)
                   (str "次の段階: " (step-label step))
                   (str "Next step: " (step-label step)))
                 (if (= :ja lang)
                   "必要な決議: 計算書類を承認した取締役会の決議（未選択または未可決）"
                   "Required resolution: Passed board resolution approving financials (missing or not passed)")
                 (if (= :ja lang)
                   (str "貸借の状態: " (if (:balanced? summary) "✓ 貸借一致" "✕ 貸借不一致"))
                   (str "Balance status: " (if (:balanced? summary) "✓ Balanced" "✕ Unbalanced")))
                 (if (= :ja lang)
                   "可決済みの取締役会決議を選択してください。"
                   "Please select a passed board resolution.")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      (and (= step :agm-approved)
           (not (and (= :passed (get-in ref [:result :status]))
                     (= :approve-financials (:matter ref)))))
      {:allowed? false
       :status :blocked
       :reason :agm-resolution-required
       :effects [(if (= :ja lang)
                   (str "次の段階: " (step-label step))
                   (str "Next step: " (step-label step)))
                 (if (= :ja lang)
                   "必要な決議: 計算書類を承認した株主総会の決議（未選択または未可決）"
                   "Required resolution: Passed AGM resolution approving financials (missing or not passed)")
                 (if (= :ja lang)
                   (str "貸借の状態: " (if (:balanced? summary) "✓ 貸借一致" "✕ 貸借不一致"))
                   (str "Balance status: " (if (:balanced? summary) "✓ Balanced" "✕ Unbalanced")))
                 (if (= :ja lang)
                   "可決済みの株主総会決議を選択してください。"
                   "Please select a passed AGM resolution.")
                 (if (= :ja lang) "この操作は取り消せません。" "This operation cannot be undone.")]
       :irreversible? true :human-only? true}

      :else
      (let [res-text (cond
                       (#{:board-approved :agm-approved} step)
                       (if (= :ja lang)
                         (str "必要な決議: " (:id ref) "（可決済み）")
                         (str "Required resolution: " (:id ref) " (passed)"))
                       :else
                       (if (= :ja lang)
                         "必要な決議: なし"
                         "Required resolution: None"))
            step-text (if (= :ja lang)
                        (str "次の段階: " (step-label step))
                        (str "Next step: " (step-label step)))
            balance-text (if (= :ja lang)
                           (str "貸借の状態: " (if (:balanced? summary) "✓ 貸借一致" "✕ 貸借不一致"))
                           (str "Balance status: " (if (:balanced? summary) "✓ Balanced" "✕ Unbalanced")))
            irreversible-text (if (= :ja lang)
                                "この操作は取り消せません。"
                                "This operation cannot be undone.")]
        {:allowed? true
         :reason nil
         :next-step step
         :effects [step-text res-text balance-text irreversible-text]
         :irreversible? true
         :human-only? true}))))

;; ---------------------------------------------------------------- 公開 preview 関数

(defn preview
  "操作の dry-run。state は変更せず、許可・拒否・効果・不可逆性・人のみの属性を返す。"
  ([state op args] (preview state op args {}))
  ([state op args opts]
   (case (normalize-op op)
     :ringi.decide (preview-ringi-decide state args opts)
     :officer.verify (preview-officer-verify state args opts)
     :member.permissions.set (preview-member-permissions state args opts)
     :meeting.vote (preview-meeting-vote state args opts)
     :books.advance (preview-books-advance state args opts)
     {:allowed? false :reason :unknown-op :effects [] :irreversible? false :human-only? false})))

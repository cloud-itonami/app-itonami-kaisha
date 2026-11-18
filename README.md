# Kaisha 会社

会社法に基づいて法人を運営するための Itonami dapp。社内の会話（Teams のような）と、期限・会議・稟議・計算書類の管理（MoneyForward のような）を、1 つの画面で扱う。
画面・状態・判定はすべて端末の中で動き、外部への送信・提出・署名はしない。

追跡: [AWAI-3423](https://linear.app/kotobalabs/issue/AWAI-3423)（親 [AWAI-155](https://linear.app/kotobalabs/issue/AWAI-155)）。

## できること

| 画面 | 内容 | 主な条文 |
|---|---|---|
| 予定 | 役員の任期満了の見込み、変更登記の期限、事業年度末、定時総会の最終日、法人税の申告期限、招集通知の最終日 | 会社法 296・332・336・915条、法人税法 74条 |
| 会議 | 株主総会・取締役会の招集通知の期限、決議要件の計算（普通・特別・役員選任・取締役会）、議事録の保存期間 | 299・309・318・341・368・369・371条 |
| 稟議 | 社内規程による承認人数、起案者の自己承認の禁止、取締役会の決議が要る種類（重要な財産・多額の借財など）、締結できる代表者 | 349・362条4項 |
| 会計 | 残高試算表（CSV）の取り込みと貸借の確認、計算書類の 作成 → 監査 → 取締役会の承認 → 定時総会の承認 → 公告 | 435・436・438・440条 |
| 会話 | チャンネル、非公開チャンネル、1 段のスレッド、DM、メンション、既読 | — |
| 行政手続き | 本店移転を記録すると、egov の制度パックから候補手続き（変更登記）・期限・提出先・出典・未収録の場合分けを出す。代表権が未確認・制度にない市町村・国をまたぐ移転・合同会社・見直し期限切れでは結論を出さずに止める | 915条（egov の日本パック） |
| 設定 | 会社の基本・定款の定め（入力中に会社法の上限を確かめる）、役員の追加（変更登記の期限を予定に出す）、メンバーと権限 | 296・299・332・336・341・368・915条 |

扱う会社は、株式会社（監査等委員会設置会社・指名委員会等設置会社を除く）と合同会社。
特殊決議（309条3・4項）、種類株主総会、累積投票など扱わないものは、推測せず「判定しない」を返す。

## 法令の扱い

- 条文は e-Gov 法令 API v2 で 2026-11-18 に取得し、法令の改正 ID と応答の sha256 を [`src/kaisha/law.cljc`](src/kaisha/law.cljc) に記録した。応答そのものは [`docs/evidence/egov-20261118/`](docs/evidence/egov-20261118/) にあり、test が sha256 の一致を確かめる。
- **専門家レビュー前**（`:review/status :unreviewed`）。画面にもそう表示する。判定は「候補」と「確認事項」までで、法的な判断・代理・提出の代わりにはならない。
- `:pack/review-by`（2027-02-18）を過ぎたら見直す。
- 代表取締役でも、登記事項証明書などで確認するまでは代表権を「未確認」と表示する。内部の役職表は代表権の証明にならない。

## 画面の設計

画面の構成は Figma の提案ページ [Kaisha · 法人運営 UX proposal](https://www.figma.com/design/umqCqP5KSjLWh9fhCI1HaN?node-id=319-2)（AWAI-3445）が正。コードは挙動の正。
- `web/icons/` は、そのファイルの `Android v2 / Icon`（calendar・chat・back）と `Kaisha / Icon`（meeting・approval・ledger）を書き出した SVG。
- `vendor/awai-ds/status.json` は、同じファイルの変数コレクション「Awai DS · market & status」の色。awai-ui のトークンにまだない。
- 設定（行 3、AWAI-3449）: 各タブの右上の設定ボタンから開く。操作する人（ログインではない）、言語、会社の基本、定款の定め、役員、メンバーと権限、法令パックの情報、架空データに戻す（確認つき）。

## 構成

| 場所 | 中身 |
|---|---|
| `src/kaisha/*.cljc` | domain と規則（純粋な関数）。JVM・Babashka・ClojureScript で同じものが動く |
| `ui/kaisha/ui.cljs` | 画面。`kaisha.app/dispatch` で状態を更新し、`localStorage` にだけ保存する |
| `web/` | HTML と CSS。色・余白は awai-ui のトークンだけを使う |
| `vendor/awai-ui/` | awai-os `packages/awai-ui` の固定 revision の CSS・トークン・Noto Sans JP（OFL）。`PINS.json` の sha256 が変わるとビルドが止まる |
| `scripts/build.mjs` | `dist/` を組み立て、`descriptor.json`（`kotoba.app.client-runtime.v1`、operations は空）と `release.json`（sha256）を書く |
| `src-tauri/` | Tauri 3.0.0-alpha.6 の薄い host（yotei と同じ固定）。ネットワーク・ファイル・shell の権限は持たない |

行政手続きの判定と制度パックは [app-itonami-egov](https://github.com/network-awai/app-itonami-egov) が正。`vendor/egov/` に revision と sha256 を固定して取り込み（`PINS.json`、test が照合）、ここでは書き換えない。直すときは egov を直してから固定を更新する。

会計の帳簿は [kaikei](https://github.com/network-awai/app-itonami-kaikei) が持つ。Kaisha は作り直さず、試算表の残高だけを受け取る。
会話の意味（スレッドは 1 段、DM の id は 2 人から決まる）は [kotoba-lang/app-itonami-kaisha](https://github.com/kotoba-lang/app-itonami-kaisha) の `kaisha.model` に合わせた。

## 実行

```bash
bb test                  # test（clojure -M:test でも同じ）
bb ui                    # 画面の JS（JVM で shadow-cljs）
npm run build            # dist/ を組み立てる（bb ui の後）
cargo build --features custom-protocol --manifest-path src-tauri/Cargo.toml   # desktop
npm run build:android    # Android APK（JDK・SDK・NDK が必要。未検証）
```

## 現状（2026-11-18）

- domain・画面・test・desktop のビルドまで。初回は架空の会社（架空商事株式会社）で開く。
- 未着手: Figma での状態設計、Android APK と実機、署名と Itonami Store、AwaiFS への保存と端末間の同期、itonami agent との協働、外部への提出、専門家レビュー。

# 本人確認・電子署名・電子申請の連携（AWAI-3483）

owner 決定（2026-11-18）: 日本から段階的に進める。**個人番号（12 桁）は読まない・求めない・保存しない。** 本人確認と署名には、カードの電子証明書だけを使う。

コードは `src/kaisha/identity.cljc`。外部操作は宣言だけで、`enabled false`。共通の同意 broker（`kotoba-client-runtime.js`）の実在と審査が確認できるまで、`broker-available?` は false のままにし、descriptor の operations にも入れない（AWAI-155 の blocker）。

## 日本

| 仕組み | 使い道 | 前提（2026-11-18 時点の調べ） | 状態 |
|---|---|---|---|
| マイナンバーカード（JPKI、署名用・利用者証明用） | 代表者の本人確認と電子署名 | 民間が電子証明書を検証するには、署名検証者等として主務大臣の認定を受けるか、認定済みのプラットフォーム事業者を使う。デジタル認証アプリ（2024-06 開始）という選択肢もある。新規の認定には数か月かかるとされる | blocked |
| 商業登記電子証明書 | 代表者の電子署名（登記のオンライン申請） | 登記されている代表者等に発行される。登記のオンライン申請では、これか JPKI などの電子証明書で署名する | planned |
| 法人番号（13 桁、公開情報） | 会社の基本 3 情報の照会 | 国税庁 法人番号システム Web-API（`https://api.houjin-bangou.nta.go.jp/4/num`）。アプリケーション ID の発行が必要。検査用数字はこの端末で確かめる（法人番号の指定等に関する省令 第2条） | blocked（ID 未発行） |
| gBizID | 法人の共通認証 | 連携の条件は未確認 | research |
| e-Gov 電子申請 API | 社会保険などの電子申請 | API 利用者（ソフトウェアを開発・提供する者）としての登録と、検証環境での確認試験が必要 | blocked |
| 登記・供託オンライン申請 | 商業登記の申請 | 申請用総合ソフトの利用が案内されている。API 連携の方法は未確認 | research |

## ほかの国（調査中・未検証）

EU（eIDAS、EU デジタル ID ウォレット）、エストニア（ID カード）、シンガポール（Singpass・Corppass）を候補として一覧に出す。条件は確かめておらず、画面では「調査中」と表示する。

## 次の段階

1. 法人番号: 国税庁にアプリケーション ID を申請する（owner の判断）。ID は端末の外に出さず、broker の操作ごとの同意で照会する。
2. JPKI: プラットフォーム事業者の経由か、デジタル認証アプリかを owner が選ぶ。Android（AOSP）の NFC で読む場合は、ネイティブ側の adapter が必要。
3. e-Gov 電子申請: API 利用者の登録と確認試験（owner の判断と外部の手続き）。

## 出典

- デジタル庁「公的個人認証サービスの民間事業者における活用」 https://www.digital.go.jp/policies/mynumber/private-business/jpki-introduction
- デジタル庁 e-Gov 開発者サイト 電子申請 API 利用規約 https://developer.e-gov.go.jp/sites/default/files/filebrowser/e-gov/doc/terms-of-API.pdf
- 国税庁 法人番号システム Web-API https://www.houjin-bangou.nta.go.jp/webapi/ 、利用手続（共通編） https://www.houjin-bangou.nta.go.jp/pc/webapi/images/k-web-api-tetuduki.pdf
- 法務省 商業登記電子証明書 https://www.moj.go.jp/MINJI/minji06_00086.html
- 法人番号の指定等に関する省令 第2条（e-Gov 法令 API、`docs/evidence/egov-20261118/hojinbango-shoureki-2.json`）

# 画像認識の中継サーバー

デスクトップアプリから手書き画像を受け取り、Claude（Anthropic の API）で MakeCode の JavaScript に
書き起こして返すサーバー。**Anthropic の API キーはこのサーバーだけが持ち、アプリには配布しない。**

```
デスクトップアプリ ──(招待コード＋画像)──> このサーバー（Cloud Run） ──> Anthropic API（Claude Sonnet 5）
```

## API

`POST /v1/recognize`

- ヘッダー: `Authorization: Bearer <招待コード>`、`Content-Type: application/json`
- 本文: `{ "image": "<base64>", "mediaType": "image/jpeg" | "image/png" }`
- 成功 `200`: `{ "code": "...", "notes": ["..."], "remaining": 29 }`（`remaining` は本日の残り回数）
- 失敗: `{ "error": "日本語のメッセージ" }`

| 状態 | 意味 |
|---|---|
| 400 | 本文の形式が不正、JPEG / PNG 以外 |
| 401 | 招待コードが無い・違う |
| 413 | 本文が 6MB を超えた |
| 422 | 処理を断られた、出力が長すぎた（回数に数える） |
| 429 | 本日の上限に達した |
| 5xx | サーバー側・Anthropic 側の失敗（回数に数えない） |

`GET /healthz` は死活確認用。

## 設定（環境変数）

| 変数 | 意味 |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic の API キー（Secret Manager から渡す） |
| `INVITE_CODES` | 招待コードの一覧。1 行に `名前=コード`（Secret Manager から渡す） |
| `DAILY_LIMIT` | 1 人 1 日の上限回数（既定 30） |
| `PORT` | 待ち受けポート（Cloud Run が自動で設定） |

回数はメモリ上で数えるので、**インスタンスは最大 1 台に固定する**（`--max-instances 1`）。
再起動すると数え直しになるが、少人数での運用なら問題ない。

ログには 名前・時刻・トークン数・所要時間だけを残す。画像や認識結果は保存しない。

## ローカルで動かす

```powershell
cd server
mvn package
$env:ANTHROPIC_API_KEY = "sk-ant-..."
$env:INVITE_CODES = "自分=test-code"
java -jar target/ocr-server.jar
```

デスクトップアプリ側は環境変数 `MAKECODE_OCR_URL=http://localhost:8080` を付けて起動し、
画像認識画面の「招待コード…」に `test-code` を入れる。

## Google Cloud（Cloud Run）へのデプロイ

ローカルに Docker は不要（ビルドは Google Cloud 側で `Dockerfile` を使って行われる）。
以下は **Cloud Shell（ブラウザ上の端末、gcloud 入り）** 向けの bash のコマンド。
このフォルダ（`server/`）を Cloud Shell にアップロードするか、リポジトリを clone して実行する。

### 1. 準備（最初の 1 回）

```bash
PROJECT=あなたのプロジェクトID
REGION=asia-northeast1
gcloud config set project $PROJECT

gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com
```

### 2. 秘密情報を Secret Manager に入れる

招待コードの一覧 `invites.txt` を作る（**リポジトリには入れないこと**）。コードは推測されにくい乱数にする
（例: `openssl rand -hex 6`）。

```
山田先生=3f9a1c2b7d4e
佐藤先生=a81c9e0f2b35
```

```bash
printf '%s' 'sk-ant-...' | gcloud secrets create anthropic-api-key --data-file=-
gcloud secrets create ocr-invite-codes --data-file=invites.txt

PROJECT_NUMBER=$(gcloud projects describe $PROJECT --format='value(projectNumber)')
for S in anthropic-api-key ocr-invite-codes; do
  gcloud secrets add-iam-policy-binding $S \
    --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
    --role=roles/secretmanager.secretAccessor
done
```

### 3. デプロイ

```bash
cd server
gcloud run deploy makecode-ocr --source . --region $REGION \
  --allow-unauthenticated --max-instances 1 --memory 1Gi --timeout 300 \
  --set-secrets ANTHROPIC_API_KEY=anthropic-api-key:latest,INVITE_CODES=ocr-invite-codes:latest \
  --set-env-vars DAILY_LIMIT=30
```

初回は Artifact Registry のリポジトリを作るか聞かれるので `Y`。
`--allow-unauthenticated` は Cloud Run の入口を開けるという意味で、利用者の確認は招待コードで行う。

### 4. アプリに URL を設定する

```bash
gcloud run services describe makecode-ocr --region $REGION --format='value(status.url)'
curl "$(gcloud run services describe makecode-ocr --region $REGION --format='value(status.url)')/healthz"
```

表示された URL を `src/main/java/app/HandwritingOcr.java` の `DEFAULT_SERVER_URL` に入れて、
アプリをビルドし直してから配布する。

## 運用

### 招待コードを追加・停止する

`invites.txt` を編集して（停止したい人の行を消す）、新しい版を登録し、サーバーに読み直させる。

```bash
gcloud secrets versions add ocr-invite-codes --data-file=invites.txt
gcloud run services update makecode-ocr --region $REGION \
  --update-secrets INVITE_CODES=ocr-invite-codes:latest
```

### 記法ガイド（プロンプト）を更新する

`src/main/resources/ocr-prompt.md` を直して、手順 3 のデプロイをやり直す。全員に一斉に反映される。
**例（書き起こし → 正解 JS）を直したときは、MakeCode のレンダラーで変換して意図どおりのブロックになるか確認すること**
（例が間違っているとモデルがそれを真似する）。

### 費用の歯止め（必ず設定する）

- Anthropic Console の **Limits** で月の利用上限を設定する
- Google Cloud の **お支払い → 予算とアラート** で予算アラートを設定する

### ログを見る

```bash
gcloud run services logs read makecode-ocr --region $REGION --limit 50
```

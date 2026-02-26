# DocExtract — AWS Textract Document Extraction App

A standalone Spring Boot enterprise component for AI-powered document text extraction using AWS Textract, with S3 storage, Lambda processing, DynamoDB job tracking, and Excel result export.

---

## Architecture

```
Browser UI  →  Spring Boot API  →  S3 (uploads)
                                      ↓ S3 Event
                                   Lambda Function
                                      ↓
                                   AWS Textract
                                      ↓
                                   S3 (Excel results)
                                      ↓
                                   DynamoDB (job status)
```

---

## Prerequisites

- Java 21+
- Maven 3.9+
- Terraform 1.5+
- AWS CLI configured (`aws configure`)
- AWS account with Textract access

---

## Quick Start

### Step 1 — Build Lambda JAR

```powershell
cd textract-app\lambda
mvn clean package -DskipTests
```

### Step 2 — Provision Infrastructure

```powershell
cd textract-app\terraform
terraform init
terraform apply
# Note the output values (bucket names, table name)
```

### Step 3 — Set Environment Variables

Copy the values printed by `terraform output spring_boot_env_vars` and run them in PowerShell:

```powershell
$env:UPLOAD_BUCKET  = "<from terraform output>"
$env:RESULTS_BUCKET = "<from terraform output>"
$env:DYNAMODB_TABLE = "textract_jobs"
$env:AWS_REGION     = "us-east-1"
```

### Step 4 — Build & Run Spring Boot

```powershell
cd textract-app\backend
mvn clean package -DskipTests
java -jar target\textract-backend-1.0.0.jar
```

### Step 5 — Open the UI

Open `frontend\index.html` in any browser. Set the API URL to `http://localhost:8080` in the config bar.

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/documents/upload` | Upload a document (returns `ackId`) |
| `GET`  | `/api/documents/status/{ackId}` | Poll processing status |
| `GET`  | `/api/documents/result/{ackId}` | Get extracted data as JSON |
| `POST` | `/api/documents/reprocess/{ackId}` | Re-run analysis on existing file |
| `GET`  | `/api/documents/download/{ackId}` | Get pre-signed Excel download URL |

Swagger UI: **http://localhost:8080/swagger-ui.html**

---

## User Flows

### Upload & Track
1. Open `frontend/index.html`
2. Drag & drop a PDF or image
3. Click **Analyse Document**
4. Note the **Acknowledgment ID** shown
5. In the Retrieve section, paste the ID and click **Check Status**
6. Status auto-refreshes every 5 seconds
7. When `COMPLETED`, click **View Extracted Data**

### Duplicate Detection
- Uploading the same filename again shows an alert banner
- Click **View Result** to see the cached analysis
- Click **Reprocess & Replace** to re-run Textract and overwrite

---

## Project Structure

```
textract-app/
├── terraform/        # Infrastructure as Code (S3, Lambda, DynamoDB, IAM)
├── backend/          # Spring Boot REST API
│   └── src/main/java/com/enterprise/textract/
│       ├── config/   # AWS clients, CORS
│       ├── controller/
│       ├── service/  # S3, DynamoDB, Excel
│       └── model/
├── lambda/           # AWS Lambda Textract processor
│   └── src/main/java/com/enterprise/textract/lambda/
│       ├── TextractLambdaHandler.java
│       └── util/ExcelConverter.java
└── frontend/         # Single-page HTML/CSS/JS UI
```

---

## Cleanup

```powershell
cd textract-app\terraform
terraform destroy
```

---

## Notes

- **Textract limits**: PDFs up to 3,000 pages; images up to 10 MB (single-page only for sync API)
- **Lambda timeout**: Set to 900s (15 min) to handle long async Textract jobs
- **Credentials**: Uses `DefaultCredentialsProvider` (AWS CLI profile) — no hardcoded keys
- **Result format**: 3-sheet Excel (Raw Text, Key-Values, Tables)

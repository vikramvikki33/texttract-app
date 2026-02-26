# Build and Deploy Script for DocExtract (PowerShell)
# Run from the textract-app directory

param(
    [switch]$SkipTerraform,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

Write-Host "╔══════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║   DocExtract — Build & Deploy    ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════╝" -ForegroundColor Cyan

# ── Step 1: Build Lambda ──────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "`n[1/4] Building Lambda JAR..." -ForegroundColor Yellow
    Set-Location "$Root\lambda"
    mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Lambda build failed!"; exit 1 }
    Write-Host "    Lambda JAR built successfully" -ForegroundColor Green

    # ── Step 2: Build Spring Boot ──────────────────────────────────────
    Write-Host "`n[2/4] Building Spring Boot JAR..." -ForegroundColor Yellow
    Set-Location "$Root\backend"
    mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Error "Spring Boot build failed!"; exit 1 }
    Write-Host "    Spring Boot JAR built successfully" -ForegroundColor Green
}

# ── Step 3: Terraform ─────────────────────────────────────────────
if (-not $SkipTerraform) {
    Write-Host "`n[3/4] Deploying infrastructure with Terraform..." -ForegroundColor Yellow
    Set-Location "$Root\terraform"
    terraform init -input=false
    terraform apply -auto-approve -input=false
    if ($LASTEXITCODE -ne 0) { Write-Error "Terraform apply failed!"; exit 1 }
    Write-Host "    Infrastructure deployed!" -ForegroundColor Green

    Write-Host "`n── Terraform Outputs ──" -ForegroundColor Cyan
    terraform output
}

# ── Step 4: Instructions ──────────────────────────────────────────
Write-Host "`n[4/4] Setup Complete!" -ForegroundColor Green
Write-Host "`nNext steps:" -ForegroundColor Yellow
Write-Host "  1. Copy the env var commands from 'spring_boot_env_vars' output above"
Write-Host "  2. Run them in your PowerShell session"
Write-Host "  3. Start Spring Boot:"
Write-Host "       cd backend"
Write-Host "       java -jar target\textract-backend-1.0.0.jar"
Write-Host "  4. Open frontend\index.html in your browser"
Write-Host "  5. Visit http://localhost:8080/swagger-ui.html for API docs"
Write-Host ""

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# ─────────────────────────────────────────────
# Random suffix to ensure globally unique S3 names
# ─────────────────────────────────────────────
resource "random_id" "suffix" {
  byte_length = 4
}

locals {
  suffix        = random_id.suffix.hex
  upload_bucket = "${var.project_name}-uploads-${local.suffix}"
  results_bucket = "${var.project_name}-results-${local.suffix}"
  common_tags = {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "Terraform"
  }
}

# ─────────────────────────────────────────────
# S3 — Upload Bucket
# ─────────────────────────────────────────────
resource "aws_s3_bucket" "uploads" {
  bucket        = local.upload_bucket
  force_destroy = true
  tags          = merge(local.common_tags, { Name = "Textract Uploads" })
}

resource "aws_s3_bucket_versioning" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "uploads" {
  bucket                  = aws_s3_bucket.uploads.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ─────────────────────────────────────────────
# S3 — Results Bucket
# ─────────────────────────────────────────────
resource "aws_s3_bucket" "results" {
  bucket        = local.results_bucket
  force_destroy = true
  tags          = merge(local.common_tags, { Name = "Textract Results" })
}

resource "aws_s3_bucket_versioning" "results" {
  bucket = aws_s3_bucket.results.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "results" {
  bucket = aws_s3_bucket.results.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "results" {
  bucket                  = aws_s3_bucket.results.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ─────────────────────────────────────────────
# DynamoDB — Job Tracking Table
# ─────────────────────────────────────────────
resource "aws_dynamodb_table" "textract_jobs" {
  name         = "textract_jobs"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "ack_id"

  attribute {
    name = "ack_id"
    type = "S"
  }

  attribute {
    name = "file_name"
    type = "S"
  }

  # GSI to look up existing jobs by filename (duplicate detection)
  global_secondary_index {
    name            = "FileNameIndex"
    hash_key        = "file_name"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "expires_at"
    enabled        = false
  }

  tags = merge(local.common_tags, { Name = "Textract Jobs" })
}

# ─────────────────────────────────────────────
# IAM — Lambda Execution Role
# ─────────────────────────────────────────────
resource "aws_iam_role" "lambda_exec" {
  name = "${var.project_name}-lambda-exec-${local.suffix}"
  tags = local.common_tags

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "lambda_policy" {
  name = "${var.project_name}-lambda-policy"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # CloudWatch Logs
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "arn:aws:logs:*:*:*"
      },
      # S3 — full access to both buckets
      {
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:HeadObject", "s3:ListBucket"]
        Resource = [
          aws_s3_bucket.uploads.arn,
          "${aws_s3_bucket.uploads.arn}/*",
          aws_s3_bucket.results.arn,
          "${aws_s3_bucket.results.arn}/*"
        ]
      },
      # Textract
      {
        Effect   = "Allow"
        Action   = ["textract:*"]
        Resource = "*"
      },
      # DynamoDB
      {
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:Query", "dynamodb:Scan"]
        Resource = [
          aws_dynamodb_table.textract_jobs.arn,
          "${aws_dynamodb_table.textract_jobs.arn}/index/*"
        ]
      }
    ]
  })
}

# ─────────────────────────────────────────────
# CloudWatch Log Group for Lambda
# ─────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "lambda_logs" {
  name              = "/aws/lambda/${var.project_name}-processor-${local.suffix}"
  retention_in_days = 14
  tags              = local.common_tags
}

# ─────────────────────────────────────────────
# Lambda Function
# ─────────────────────────────────────────────
resource "aws_lambda_function" "textract_processor" {
  function_name = "${var.project_name}-processor-${local.suffix}"
  role          = aws_iam_role.lambda_exec.arn
  runtime       = "java21"
  handler       = "com.enterprise.textract.lambda.TextractLambdaHandler::handleRequest"
  timeout       = var.lambda_timeout_seconds
  memory_size   = var.lambda_memory_mb

  # JAR must be built first: cd lambda && mvn clean package -DskipTests
  filename         = "${path.module}/../lambda/target/textract-lambda-1.0.0.jar"
  source_code_hash = filebase64sha256("${path.module}/../lambda/target/textract-lambda-1.0.0.jar")

  environment {
    variables = {
      RESULTS_BUCKET  = aws_s3_bucket.results.bucket
      DYNAMODB_TABLE  = aws_dynamodb_table.textract_jobs.name
      AWS_REGION_NAME = var.aws_region
    }
  }

  depends_on = [
    aws_iam_role_policy.lambda_policy,
    aws_cloudwatch_log_group.lambda_logs
  ]

  tags = local.common_tags
}

# ─────────────────────────────────────────────
# S3 → Lambda Trigger (for pdf, jpg, jpeg, png)
# ─────────────────────────────────────────────
resource "aws_lambda_permission" "s3_invoke_lambda" {
  statement_id  = "AllowS3Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.textract_processor.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.uploads.arn
}

resource "aws_s3_bucket_notification" "upload_trigger" {
  bucket = aws_s3_bucket.uploads.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.textract_processor.arn
    events              = ["s3:ObjectCreated:*"]
    filter_suffix       = ".pdf"
  }

  lambda_function {
    lambda_function_arn = aws_lambda_function.textract_processor.arn
    events              = ["s3:ObjectCreated:*"]
    filter_suffix       = ".jpg"
  }

  lambda_function {
    lambda_function_arn = aws_lambda_function.textract_processor.arn
    events              = ["s3:ObjectCreated:*"]
    filter_suffix       = ".jpeg"
  }

  lambda_function {
    lambda_function_arn = aws_lambda_function.textract_processor.arn
    events              = ["s3:ObjectCreated:*"]
    filter_suffix       = ".png"
  }

  depends_on = [aws_lambda_permission.s3_invoke_lambda]
}

# ─────────────────────────────────────────────
# IAM Role for Spring Boot App (EC2 / local dev)
# ─────────────────────────────────────────────
resource "aws_iam_user" "app_user" {
  name = "${var.project_name}-app-user-${local.suffix}"
  tags = local.common_tags
}

resource "aws_iam_user_policy" "app_user_policy" {
  name = "${var.project_name}-app-user-policy"
  user = aws_iam_user.app_user.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject", "s3:HeadObject", "s3:ListBucket", "s3:DeleteObject"]
        Resource = [
          aws_s3_bucket.uploads.arn,
          "${aws_s3_bucket.uploads.arn}/*",
          aws_s3_bucket.results.arn,
          "${aws_s3_bucket.results.arn}/*"
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:Query", "dynamodb:Scan"]
        Resource = [
          aws_dynamodb_table.textract_jobs.arn,
          "${aws_dynamodb_table.textract_jobs.arn}/index/*"
        ]
      }
    ]
  })
}

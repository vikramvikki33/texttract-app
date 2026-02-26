output "upload_bucket_name" {
  description = "S3 bucket name for document uploads"
  value       = aws_s3_bucket.uploads.bucket
}

output "results_bucket_name" {
  description = "S3 bucket name for Excel results"
  value       = aws_s3_bucket.results.bucket
}

output "dynamodb_table_name" {
  description = "DynamoDB table name for job tracking"
  value       = aws_dynamodb_table.textract_jobs.name
}

output "lambda_function_arn" {
  description = "ARN of the Textract processor Lambda function"
  value       = aws_lambda_function.textract_processor.arn
}

output "lambda_function_name" {
  description = "Name of the Textract processor Lambda function"
  value       = aws_lambda_function.textract_processor.function_name
}

output "aws_region" {
  description = "AWS region where resources are deployed"
  value       = var.aws_region
}

output "spring_boot_env_vars" {
  description = "Environment variables to set before running Spring Boot"
  value = <<-EOT
    Set these environment variables before starting the Spring Boot application:

    Windows PowerShell:
      $env:UPLOAD_BUCKET  = "${aws_s3_bucket.uploads.bucket}"
      $env:RESULTS_BUCKET = "${aws_s3_bucket.results.bucket}"
      $env:DYNAMODB_TABLE = "${aws_dynamodb_table.textract_jobs.name}"
      $env:AWS_REGION     = "${var.aws_region}"

    Linux/MacOS:
      export UPLOAD_BUCKET="${aws_s3_bucket.uploads.bucket}"
      export RESULTS_BUCKET="${aws_s3_bucket.results.bucket}"
      export DYNAMODB_TABLE="${aws_dynamodb_table.textract_jobs.name}"
      export AWS_REGION="${var.aws_region}"
  EOT
}

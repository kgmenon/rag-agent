resource "aws_s3_bucket" "uploads" {
  bucket = "${var.project_name}-${var.environment}-uploads-${random_string.bucket_suffix.result}"

  tags = {
    Name        = "${var.project_name}-${var.environment}-uploads"
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_s3_bucket" "ui" {
  bucket = "${var.project_name}-${var.environment}-ui-${random_string.bucket_suffix.result}"

  tags = {
    Name        = "${var.project_name}-${var.environment}-ui"
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_s3_bucket" "litellm_config" {
  bucket = "${var.project_name}-${var.environment}-litellm-config-${random_string.bucket_suffix.result}"

  tags = {
    Name        = "${var.project_name}-${var.environment}-litellm-config"
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_s3_bucket" "lambda_deployments" {
  bucket = "${var.project_name}-${var.environment}-lambda-deployments-${random_string.bucket_suffix.result}"

  tags = {
    Name        = "${var.project_name}-${var.environment}-lambda-deployments"
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "random_string" "bucket_suffix" {
  length  = 8
  special = false
  upper   = false
}

resource "aws_s3_bucket_versioning" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_versioning" "litellm_config" {
  bucket = aws_s3_bucket.litellm_config.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_cors_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "POST", "PUT", "DELETE", "HEAD"]
    allowed_origins = ["*"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_cors_configuration" "ui" {
  bucket = aws_s3_bucket.ui.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = ["*"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_website_configuration" "ui" {
  bucket = aws_s3_bucket.ui.id

  index_document {
    suffix = "index.html"
  }

  error_document {
    key = "error.html"
  }
}

resource "aws_s3_bucket_public_access_block" "ui" {
  bucket = aws_s3_bucket.ui.id

  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_policy" "ui" {
  bucket = aws_s3_bucket.ui.id
  depends_on = [aws_s3_bucket_public_access_block.ui]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "PublicReadGetObject"
        Effect    = "Allow"
        Principal = "*"
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.ui.arn}/*"
      }
    ]
  })
}

# Upload UI files to S3 bucket
resource "aws_s3_object" "ui_index" {
  bucket       = aws_s3_bucket.ui.id
  key          = "index.html"
  source       = "../ui/index.html"
  content_type = "text/html"
  
  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
  
  depends_on = [aws_s3_bucket_policy.ui]
}

resource "aws_s3_object" "ui_app_js" {
  bucket       = aws_s3_bucket.ui.id
  key          = "app.js"
  source       = "../ui/app.js"
  content_type = "application/javascript"
  
  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
  
  depends_on = [aws_s3_bucket_policy.ui]
}

resource "aws_s3_object" "ui_styles" {
  bucket       = aws_s3_bucket.ui.id
  key          = "styles.css"
  source       = "../ui/styles.css"
  content_type = "text/css"
  
  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
  
  depends_on = [aws_s3_bucket_policy.ui]
}

resource "aws_s3_object" "ui_error" {
  bucket       = aws_s3_bucket.ui.id
  key          = "error.html"
  source       = "../ui/error.html"
  content_type = "text/html"
  
  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
  
  depends_on = [aws_s3_bucket_policy.ui]
}

# Generate dynamic UI configuration
resource "aws_s3_object" "ui_config" {
  bucket       = aws_s3_bucket.ui.id
  key          = "config.json"
  content_type = "application/json"
  
  content = jsonencode({
    apiBaseUrl = "http://${aws_lb.main.dns_name}"
  })
  
  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
  
  depends_on = [aws_s3_bucket_policy.ui, aws_lb.main]
}

resource "aws_s3_object" "litellm_config" {
  bucket  = aws_s3_bucket.litellm_config.id
  key     = "config.yaml"
  content = var.litellm_config

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}
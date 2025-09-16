output "alb_dns_name" {
  description = "Application Load Balancer DNS name"
  value       = aws_lb.main.dns_name
}

output "ui_bucket_name" {
  description = "Name of the UI S3 bucket"
  value       = aws_s3_bucket.ui.id
}

output "ui_bucket_website_endpoint" {
  description = "Website endpoint of the UI S3 bucket"
  value       = aws_s3_bucket_website_configuration.ui.website_endpoint
}

output "uploads_bucket_name" {
  description = "Name of the uploads S3 bucket"
  value       = aws_s3_bucket.uploads.id
}

# OpenSearch removed - using Google ADK in-memory solution
# output "opensearch_endpoint" {
#   description = "OpenSearch Serverless collection endpoint"  
#   value       = aws_opensearchserverless_collection.documents.collection_endpoint
# }


output "vpc_id" {
  description = "ID of the VPC"
  value       = aws_vpc.main.id
}

output "public_subnet_ids" {
  description = "IDs of the public subnets"
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "IDs of the private subnets"
  value       = aws_subnet.private[*].id
}

output "ec2_instance_info" {
  description = "EC2 instance information"
  value = {
    instance_id    = aws_instance.rag_agent.id
    private_ip     = aws_instance.rag_agent.private_ip
    instance_type  = aws_instance.rag_agent.instance_type
  }
}

output "rag_service_endpoints" {
  description = "RAG service endpoints via Load Balancer"
  value = {
    base_url   = "http://${aws_lb.main.dns_name}"
    upload_url = "http://${aws_lb.main.dns_name}/upload"
    query_url  = "http://${aws_lb.main.dns_name}/query"
    chat_url   = "http://${aws_lb.main.dns_name}/chat"
    health_url = "http://${aws_lb.main.dns_name}/health"
  }
}

output "infrastructure_info" {
  description = "Key infrastructure information"
  value = {
    vpc_id               = aws_vpc.main.id
    upload_bucket       = aws_s3_bucket.uploads.id
    load_balancer_dns   = aws_lb.main.dns_name
    chat_endpoint       = "http://${aws_lb.main.dns_name}/chat"
  }
}
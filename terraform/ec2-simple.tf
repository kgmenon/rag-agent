# Security Group for RAG Agent EC2
resource "aws_security_group" "rag_agent_ec2" {
  name        = "${var.project_name}-${var.environment}-ec2-sg"
  description = "Security group for RAG Agent EC2 instance"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Project     = var.project_name
    Environment = var.environment
    Name        = "${var.project_name}-${var.environment}-ec2-sg"
  }
}

# Application Load Balancer
resource "aws_lb" "main" {
  name               = "${var.project_name}-${var.environment}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets           = aws_subnet.public[*].id

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# ALB Security Group
resource "aws_security_group" "alb" {
  name        = "${var.project_name}-${var.environment}-alb-sg"
  description = "Security group for Application Load Balancer"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Project     = var.project_name
    Environment = var.environment
    Name        = "${var.project_name}-${var.environment}-alb-sg"
  }
}

# ALB Target Group
resource "aws_lb_target_group" "rag_agent_ec2" {
  name     = "${var.project_name}-${var.environment}-tg"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id

  health_check {
    enabled             = true
    healthy_threshold   = 2
    unhealthy_threshold = 2
    timeout             = 5
    interval            = 30
    path                = "/health"
    matcher             = "200"
  }

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# ALB Listener
resource "aws_lb_listener" "main" {
  load_balancer_arn = aws_lb.main.arn
  port              = "80"
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.rag_agent_ec2.arn
  }
}

# ALB Target Group Attachment
resource "aws_lb_target_group_attachment" "rag_agent_ec2" {
  target_group_arn = aws_lb_target_group.rag_agent_ec2.arn
  target_id        = aws_instance.rag_agent.id
  port             = 8080
}

# CloudWatch Log Groups for EC2 logs
resource "aws_cloudwatch_log_group" "ec2_application" {
  name              = "/ec2/rag-agent/application"
  retention_in_days = 14

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

resource "aws_cloudwatch_log_group" "ec2_error" {
  name              = "/ec2/rag-agent/error"
  retention_in_days = 14

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

resource "aws_instance" "rag_agent" {
  ami           = "ami-056729a8869a8ab24"  # Amazon Linux 2 AMI for ap-southeast-2
  instance_type = "t3.medium"
  subnet_id     = aws_subnet.private[0].id  # Use private subnet for security
  vpc_security_group_ids = [aws_security_group.rag_agent_ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.rag_agent.name
  
  user_data = base64encode(<<EOF
#!/bin/bash
set -e
set -x
exec > >(tee -a /var/log/rag-agent/startup.log)
exec 2>&1

echo "=== RAG Agent EC2 Startup Script Started at $(date) ==="
mkdir -p /var/log/rag-agent
chmod 755 /var/log/rag-agent

yum update -y
yum install -y java-17-amazon-corretto-devel curl unzip amazon-cloudwatch-agent wget nc

java -version
aws --version
aws sts get-caller-identity

# Download JAR from S3
UPLOAD_BUCKET="${aws_s3_bucket.uploads.id}"
JAR_KEY="app/rag-agent-1.0.0.jar"
JAR_PATH="/home/ec2-user/rag-agent.jar"

echo "Downloading JAR from s3://$UPLOAD_BUCKET/$JAR_KEY"
for attempt in {1..3}; do
  if aws s3 cp "s3://$UPLOAD_BUCKET/$JAR_KEY" "$JAR_PATH"; then
    echo "JAR download successful"
    break
  else
    echo "Download attempt $attempt failed"
    if [ $attempt -eq 3 ]; then
      echo "FATAL: Failed to download JAR"
      exit 1
    fi
    sleep 10
  fi
done

chown ec2-user:ec2-user "$JAR_PATH"
chmod 644 "$JAR_PATH"

# Direct Bedrock integration - no external connectivity test needed
echo "Using direct Bedrock integration - skipping LiteLLM connectivity test"

# Create systemd service
cat > /etc/systemd/system/rag-agent.service << 'SERVICE_EOF'
[Unit]
Description=RAG Agent Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ec2-user
Group=ec2-user
WorkingDirectory=/home/ec2-user
ExecStart=/bin/bash -c 'exec /usr/bin/java -Xmx1536m -Xms512m -XX:+UseG1GC -jar /home/ec2-user/rag-agent.jar >> /var/log/rag-agent/application.log 2>> /var/log/rag-agent/error.log'
Restart=always
RestartSec=30
Environment="UPLOAD_BUCKET=${aws_s3_bucket.uploads.id}"
Environment="AWS_REGION=${var.region}"

[Install]
WantedBy=multi-user.target
SERVICE_EOF

# Create log files
touch /var/log/rag-agent/application.log /var/log/rag-agent/error.log
chown ec2-user:ec2-user /var/log/rag-agent/*.log
chmod 644 /var/log/rag-agent/*.log

# Configure CloudWatch agent
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'CW_EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/rag-agent/application.log",
            "log_group_name": "/ec2/rag-agent/application",
            "log_stream_name": "{instance_id}",
            "timezone": "UTC",
            "multi_line_start_pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}"
          },
          {
            "file_path": "/var/log/rag-agent/error.log",
            "log_group_name": "/ec2/rag-agent/error", 
            "log_stream_name": "{instance_id}",
            "timezone": "UTC",
            "multi_line_start_pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}"
          }
        ]
      }
    }
  }
}
CW_EOF

# Start CloudWatch agent
echo "Starting CloudWatch agent..."
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json -s
sleep 5
echo "CloudWatch agent status:"
systemctl status amazon-cloudwatch-agent --no-pager

# Start service
systemctl daemon-reload
systemctl enable rag-agent
systemctl start rag-agent

# Wait and check status
sleep 15
systemctl status rag-agent --no-pager

# Test logging
echo "Testing application logging..."
echo "$(date): RAG Agent service started successfully" >> /var/log/rag-agent/application.log
echo "$(date): Test error log entry" >> /var/log/rag-agent/error.log

# Verify log files exist and have content
echo "Log file status:"
ls -la /var/log/rag-agent/
echo "Application log content:"
tail -5 /var/log/rag-agent/application.log
echo "Error log content:"
tail -5 /var/log/rag-agent/error.log

echo "=== RAG Agent Setup Complete ==="
echo "Service status: $(systemctl is-active rag-agent)"
EOF
  )
  
  tags = {
    Name        = "${var.project_name}-${var.environment}-rag-agent"
    Environment = var.environment
    Project     = var.project_name
  }
  
  # Force replacement when user data changes
  user_data_replace_on_change = true
  
  depends_on = [aws_s3_object.rag_jar]
}

# S3 Object for RAG Agent JAR
resource "aws_s3_object" "rag_jar" {
  bucket = aws_s3_bucket.uploads.id
  key    = "app/rag-agent-1.0.0.jar"
  source = "../target/rag-agent-1.0.0.jar"
  etag   = filemd5("../target/rag-agent-1.0.0.jar")

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}
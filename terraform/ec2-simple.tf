# EC2 instance to run the RAG Agent JAR
resource "aws_instance" "rag_agent" {
  ami           = "ami-056729a8869a8ab24"  # Amazon Linux 2 AMI for ap-southeast-2
  instance_type = "t3.medium"
  subnet_id     = aws_subnet.private[0].id  # Use private subnet for security
  
  vpc_security_group_ids = [aws_security_group.rag_agent_ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.rag_agent.name
  
  user_data = base64encode(<<EOF
#!/bin/bash
yum update -y
yum install -y java-17-amazon-corretto-devel curl unzip amazon-cloudwatch-agent

# Create log directory
mkdir -p /var/log/rag-agent
chown ec2-user:ec2-user /var/log/rag-agent

# Download the JAR from S3
echo "Downloading JAR from S3..." >> /var/log/rag-agent/startup.log
aws s3 cp s3://${aws_s3_bucket.lambda_deployments.id}/rag-agent-1.0.0.jar /home/ec2-user/rag-agent.jar 2>&1 >> /var/log/rag-agent/startup.log
chown ec2-user:ec2-user /home/ec2-user/rag-agent.jar

# Verify JAR file  
echo "Verifying JAR file..." >> /var/log/rag-agent/startup.log
ls -la /home/ec2-user/rag-agent.jar >> /var/log/rag-agent/startup.log
    
    # Test Java installation
    echo "Testing Java installation..." >> /var/log/rag-agent/startup.log
    java -version >> /var/log/rag-agent/startup.log 2>&1
    
    # Test LiteLLM service connectivity for Google ADK solution
    echo "Testing LiteLLM service connectivity for Google ADK..." >> /var/log/rag-agent/startup.log
    LITELLM_URL="http://litellm.${var.project_name}.local:4000"
    echo "Testing connectivity to: $LITELLM_URL" >> /var/log/rag-agent/startup.log
    
    # Test DNS resolution
    nslookup litellm.${var.project_name}.local >> /var/log/rag-agent/startup.log 2>&1 || echo "DNS resolution failed" >> /var/log/rag-agent/startup.log
    
    # Test HTTP connectivity with extended timeout and detailed logging
    LITELLM_READY=false
    for i in {1..15}; do
      echo "LiteLLM connectivity test attempt $i..." >> /var/log/rag-agent/startup.log
      if curl -v --connect-timeout 10 --max-time 30 "$LITELLM_URL/health" >> /var/log/rag-agent/startup.log 2>&1; then
        echo "LiteLLM service is accessible for Google ADK!" >> /var/log/rag-agent/startup.log
        LITELLM_READY=true
        break
      else
        echo "LiteLLM connectivity attempt $i failed, waiting 15 seconds..." >> /var/log/rag-agent/startup.log
        sleep 15
      fi
    done
    
    if [ "$LITELLM_READY" = false ]; then
      echo "WARNING: LiteLLM service not accessible - Google ADK solution may fail to start" >> /var/log/rag-agent/startup.log
    fi
    
    # Test JAR file integrity (just verify it's a valid JAR)
    echo "Verifying JAR file validity..." >> /var/log/rag-agent/startup.log
    jar -tf /home/ec2-user/rag-agent.jar | head -5 >> /var/log/rag-agent/startup.log 2>&1 || echo "JAR file validation failed" >> /var/log/rag-agent/startup.log

    # Create systemd service
    cat > /etc/systemd/system/rag-agent.service << 'SERVICE_EOF'
    [Unit]
    Description=RAG Agent Service with Google ADK and Bedrock
    After=network.target

    [Service]
    Type=simple
    User=ec2-user
    WorkingDirectory=/home/ec2-user
    ExecStart=/usr/bin/java -Xmx1g -jar /home/ec2-user/rag-agent.jar
    Restart=always
    RestartSec=30
    TimeoutStartSec=300
    TimeoutStopSec=30
    StartLimitBurst=5
    StartLimitInterval=300

    # Environment variables for the RAG application
    Environment=OPENSEARCH_ENDPOINT=${aws_opensearchserverless_collection.documents.collection_endpoint}
    Environment=OPENSEARCH_INDEX=documents
    Environment=UPLOAD_BUCKET=${aws_s3_bucket.uploads.id}
    Environment=AWS_REGION=${var.region}
    Environment=LITELLM_ENDPOINT=http://litellm.${var.project_name}.local:4000

    # Logging
    StandardOutput=journal
    StandardError=journal

    [Install]
    WantedBy=multi-user.target
SERVICE_EOF

    # Validate environment before starting service
    echo "Validating environment configuration..." >> /var/log/rag-agent/startup.log
    echo "OPENSEARCH_ENDPOINT=${aws_opensearchserverless_collection.documents.collection_endpoint}" >> /var/log/rag-agent/startup.log
    echo "UPLOAD_BUCKET=${aws_s3_bucket.uploads.id}" >> /var/log/rag-agent/startup.log
    echo "LITELLM_ENDPOINT=http://litellm.${var.project_name}.local:4000" >> /var/log/rag-agent/startup.log
    echo "AWS_REGION=${var.region}" >> /var/log/rag-agent/startup.log

    # Start the service
    echo "Starting RAG agent service..." >> /var/log/rag-agent/startup.log
    systemctl daemon-reload
    systemctl enable rag-agent
    systemctl start rag-agent

    # Wait for service to start
    sleep 10
    
    # Check service status immediately
    echo "Initial service status:" >> /var/log/rag-agent/startup.log
    systemctl status rag-agent >> /var/log/rag-agent/startup.log 2>&1
    
    # Check if service is active
    if systemctl is-active --quiet rag-agent; then
        echo "Service is active" >> /var/log/rag-agent/startup.log
    else
        echo "Service is NOT active" >> /var/log/rag-agent/startup.log
        echo "Service logs:" >> /var/log/rag-agent/startup.log
        journalctl -u rag-agent --no-pager >> /var/log/rag-agent/startup.log 2>&1
    fi

    # Wait a bit more for service to start
    sleep 20
    
    # Create a simple health check endpoint test
    echo "Testing application startup..." >> /var/log/rag-agent/startup.log
    for i in {1..30}; do
      if curl -s http://localhost:8080/health >/dev/null 2>&1; then
        echo "Application is responding on port 8080!" >> /var/log/rag-agent/startup.log
        break
      else
        echo "Attempt $i: Application not yet responding on port 8080" >> /var/log/rag-agent/startup.log
        sleep 10
      fi
    done
    
    # Final status check
    echo "Final service status check:" >> /var/log/rag-agent/startup.log
    systemctl status rag-agent >> /var/log/rag-agent/startup.log 2>&1
    
    # Show recent application logs
    echo "Recent application logs:" >> /var/log/rag-agent/startup.log
    tail -50 /var/log/rag-agent/rag-agent.log >> /var/log/rag-agent/startup.log 2>&1 || echo "No application logs found" >> /var/log/rag-agent/startup.log
    tail -50 /var/log/rag-agent/rag-agent-error.log >> /var/log/rag-agent/startup.log 2>&1 || echo "No error logs found" >> /var/log/rag-agent/startup.log

    # Setup CloudWatch agent to send logs
    cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'CLOUDWATCH_EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/rag-agent/startup.log",
            "log_group_name": "/ec2/rag-agent/startup",
            "log_stream_name": "{instance_id}"
          },
          {
            "file_path": "/var/log/rag-agent/rag-agent.log",
            "log_group_name": "/ec2/rag-agent/application", 
            "log_stream_name": "{instance_id}"
          },
          {
            "file_path": "/var/log/rag-agent/rag-agent-error.log",
            "log_group_name": "/ec2/rag-agent/errors",
            "log_stream_name": "{instance_id}"
          }
        ]
      }
    }
  }
}
CLOUDWATCH_EOF

    # Start CloudWatch agent
    /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json -s
    
    # Log service status and wait for service to be ready
    echo "Final service status:" >> /var/log/rag-agent/startup.log
    systemctl status rag-agent >> /var/log/rag-agent/startup.log 2>&1
    
    # Wait longer for the application to be ready (Java apps can be slow to start)
    echo "Waiting for RAG agent to be ready (up to 10 minutes)..." >> /var/log/rag-agent/startup.log
    for i in {1..60}; do
      if curl -f --connect-timeout 5 --max-time 10 http://localhost:8080/health > /dev/null 2>&1; then
        echo "RAG agent is ready after $((i * 10)) seconds!" >> /var/log/rag-agent/startup.log
        break
      fi
      echo "Attempt $i: RAG agent not ready yet, waiting 10 seconds..." >> /var/log/rag-agent/startup.log
      # Log service status for debugging
      if [ $((i % 6)) -eq 0 ]; then
        echo "Service status check (attempt $i):" >> /var/log/rag-agent/startup.log
        systemctl status rag-agent >> /var/log/rag-agent/startup.log 2>&1
        echo "Recent service logs:" >> /var/log/rag-agent/startup.log
        journalctl -u rag-agent --no-pager --lines 10 >> /var/log/rag-agent/startup.log 2>&1
      fi
      sleep 10
    done
    
    # Final health check with detailed error logging
    echo "Final health check:" >> /var/log/rag-agent/startup.log
    if curl -v --connect-timeout 10 --max-time 15 http://localhost:8080/health >> /var/log/rag-agent/startup.log 2>&1; then
      echo "SUCCESS: RAG agent health check passed!" >> /var/log/rag-agent/startup.log
    else
      echo "FAILED: RAG agent health check failed" >> /var/log/rag-agent/startup.log
      echo "Final service status:" >> /var/log/rag-agent/startup.log
      systemctl status rag-agent >> /var/log/rag-agent/startup.log 2>&1
      echo "Final service logs:" >> /var/log/rag-agent/startup.log
      journalctl -u rag-agent --no-pager --lines 20 >> /var/log/rag-agent/startup.log 2>&1
    fi
EOF
  )
  
  tags = {
    Name        = "${var.project_name}-${var.environment}-rag-agent"
    Environment = var.environment
    Project     = var.project_name
  }
  
  depends_on = [aws_s3_object.rag_jar]
  
  # Force replacement when JAR changes
  user_data_replace_on_change = true
}

resource "aws_s3_object" "rag_jar" {
  bucket = aws_s3_bucket.lambda_deployments.id
  key    = "rag-agent-1.0.0.jar"
  source = "../target/rag-agent-1.0.0.jar"
  
  depends_on = [null_resource.maven_build]
}

resource "null_resource" "maven_build" {
  triggers = {
    always_run = timestamp()
  }

  provisioner "local-exec" {
    command     = "mvn clean package -DskipTests"
    working_dir = "${path.module}/.."
  }
}

resource "aws_security_group" "rag_agent_ec2" {
  name_prefix = "${var.project_name}-${var.environment}-ec2-"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]  # Only from VPC for security
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-ec2-sg"
    Environment = var.environment
    Project     = var.project_name
  }
}

# Load Balancer Target Group for EC2
resource "aws_lb_target_group" "rag_agent_ec2" {
  name        = "${var.project_name}-${var.environment}-ec2-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "instance"

  health_check {
    enabled             = true
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 10
    interval            = 30
    path                = "/health"
    matcher             = "200"
  }

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

# Attach EC2 instance to target group
resource "aws_lb_target_group_attachment" "rag_agent_ec2" {
  target_group_arn = aws_lb_target_group.rag_agent_ec2.arn
  target_id        = aws_instance.rag_agent.id
  port             = 8080
}

# Load Balancer Security Group
resource "aws_security_group" "alb" {
  name_prefix = "${var.project_name}-${var.environment}-alb-"
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
    Name        = "${var.project_name}-${var.environment}-alb-sg"
    Environment = var.environment
    Project     = var.project_name
  }
}

# Application Load Balancer
resource "aws_lb" "main" {
  name               = "${var.project_name}-${var.environment}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets           = aws_subnet.public[*].id

  enable_deletion_protection = false

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

# Load Balancer Listener
resource "aws_lb_listener" "main" {
  load_balancer_arn = aws_lb.main.arn
  port              = "80"
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.rag_agent_ec2.arn
  }

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}
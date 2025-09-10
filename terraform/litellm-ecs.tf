# LiteLLM Proxy ECS Service
# Cost-optimized ECS deployment for LiteLLM proxy server

# ECS Cluster for LiteLLM
resource "aws_ecs_cluster" "litellm" {
  name = "${var.project_name}-${var.environment}-litellm-cluster"
  
  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# CloudWatch Log Group for LiteLLM
resource "aws_cloudwatch_log_group" "litellm" {
  name              = "/ecs/${var.project_name}-${var.environment}-litellm"
  retention_in_days = 7  # Short retention for cost optimization

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# LiteLLM Task Definition
resource "aws_ecs_task_definition" "litellm" {
  family                   = "${var.project_name}-${var.environment}-litellm"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"  # Increased CPU for stable operation
  memory                   = "1024"  # Increased memory for stable operation
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn           = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name  = "config-setup"
      image = "alpine:latest"
      essential = false
      
      command = [
        "sh", "-c",
        <<-EOF
        mkdir -p /shared && 
        cat > /shared/config.yaml << 'CONFIG_EOF'
model_list:
  - model_name: anthropic.claude-3-sonnet-20240229-v1:0
    litellm_params:
      model: bedrock/anthropic.claude-3-sonnet-20240229-v1:0
      aws_region_name: ${var.region}
CONFIG_EOF
        echo 'Config created successfully:' && 
        cat /shared/config.yaml && 
        ls -la /shared/
        EOF
      ]
      
      mountPoints = [
        {
          sourceVolume  = "shared-config"
          containerPath = "/shared"
          readOnly      = false
        }
      ]
      
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.litellm.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "setup"
        }
      }
    },
    {
      name  = "litellm-proxy"
      image = "ghcr.io/berriai/litellm:main-v1.17.0"
      essential = true
      dependsOn = [
        {
          containerName = "config-setup"
          condition     = "SUCCESS"
        }
      ]
      
      portMappings = [
        {
          containerPort = 4000
          protocol      = "tcp"
        }
      ]

      environment = [
        {
          name  = "LITELLM_MASTER_KEY"
          value = "sk-litellm-proxy-${random_string.bucket_suffix.result}"
        },
        {
          name  = "AWS_DEFAULT_REGION"
          value = var.region
        }
      ]

      command = [
        "--config",
        "/app/config.yaml",
        "--port",
        "4000"
      ]

      mountPoints = [
        {
          sourceVolume  = "shared-config"
          containerPath = "/app"
          readOnly      = true
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.litellm.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "litellm"
        }
      }

      healthCheck = {
        command = [
          "CMD-SHELL",
          "curl -f http://localhost:4000/health || exit 1"
        ]
        interval    = 30
        timeout     = 15
        retries     = 3
        startPeriod = 180
      }
    }
  ])

  volume {
    name = "shared-config"
    # Use ephemeral storage instead of EFS for config files
    # Config is downloaded at startup by init container
  }

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# EFS resources removed - using ECS init container approach instead

# Security Group for LiteLLM ECS Service
resource "aws_security_group" "litellm" {
  name        = "${var.project_name}-${var.environment}-litellm-sg"
  description = "Security group for LiteLLM ECS service"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 4000
    to_port         = 4000
    protocol        = "tcp"
    security_groups = [aws_security_group.rag_agent_ec2.id]  # Allow access from RAG agent
  }
  
  ingress {
    from_port   = 4000
    to_port     = 4000
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.main.cidr_block]  # Allow access from within VPC
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
    Name        = "${var.project_name}-${var.environment}-litellm-sg"
  }
}

# ECS Service for LiteLLM
resource "aws_ecs_service" "litellm" {
  name            = "${var.project_name}-${var.environment}-litellm"
  cluster         = aws_ecs_cluster.litellm.id
  task_definition = aws_ecs_task_definition.litellm.arn
  desired_count   = 1  # Single instance for cost optimization
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.litellm.id]
  }

  service_registries {
    registry_arn   = aws_service_discovery_service.litellm.arn
    container_name = "litellm-proxy"
  }


  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# Service Discovery for LiteLLM
resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "${var.project_name}.local"
  vpc  = aws_vpc.main.id

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

resource "aws_service_discovery_service" "litellm" {
  name = "litellm"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }
  }


  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# Removed EC2-based EFS setup - using ECS init container instead

# Update ECS task role to include Bedrock permissions
resource "aws_iam_role_policy" "ecs_task_bedrock" {
  name = "${var.project_name}-${var.environment}-ecs-bedrock-policy"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "bedrock:InvokeModel",
          "bedrock:InvokeModelWithResponseStream"
        ]
        Resource = [
          "arn:aws:bedrock:${var.region}::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0",
          "arn:aws:bedrock:${var.region}::foundation-model/anthropic.claude-sonnet-4-20250514-v1:0"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject"
        ]
        Resource = [
          "${aws_s3_bucket.litellm_config.arn}/*"
        ]
      }
    ]
  })
}
resource "aws_opensearchserverless_security_policy" "encryption" {
  name = "${var.project_name}-${var.environment}-encryption-policy"
  type = "encryption"

  policy = jsonencode({
    Rules = [
      {
        Resource = [
          "collection/${var.project_name}-${var.environment}-documents"
        ]
        ResourceType = "collection"
      }
    ]
    AWSOwnedKey = true
  })

}

resource "aws_opensearchserverless_security_policy" "network" {
  name = "${var.project_name}-${var.environment}-network-policy"
  type = "network"

  policy = jsonencode([
    {
      Rules = [
        {
          Resource = [
            "collection/${var.project_name}-${var.environment}-documents"
          ]
          ResourceType = "collection"
        }
      ]
      AllowFromPublic = true
    }
  ])

}

resource "aws_opensearchserverless_access_policy" "data" {
  name = "${var.project_name}-${var.environment}-data-policy"
  type = "data"

  policy = jsonencode([
    {
      Rules = [
        {
          Resource = [
            "collection/${var.project_name}-${var.environment}-documents"
          ]
          Permission = [
            "aoss:CreateCollectionItems",
            "aoss:DeleteCollectionItems",
            "aoss:UpdateCollectionItems",
            "aoss:DescribeCollectionItems"
          ]
          ResourceType = "collection"
        },
        {
          Resource = [
            "index/${var.project_name}-${var.environment}-documents/*"
          ]
          Permission = [
            "aoss:CreateIndex",
            "aoss:DeleteIndex",
            "aoss:UpdateIndex",
            "aoss:DescribeIndex",
            "aoss:ReadDocument",
            "aoss:WriteDocument"
          ]
          ResourceType = "index"
        }
      ]
      Principal = [
        data.aws_caller_identity.current.arn,
        aws_iam_role.lambda_ingest.arn,
        aws_iam_role.lambda_query.arn,
        aws_iam_role.ec2_rag_agent.arn
      ]
    }
  ])

}

resource "aws_opensearchserverless_collection" "documents" {
  name = "${var.project_name}-${var.environment}-documents"
  type = "VECTORSEARCH"


  depends_on = [
    aws_opensearchserverless_security_policy.encryption,
    aws_opensearchserverless_security_policy.network,
    aws_opensearchserverless_access_policy.data
  ]
}
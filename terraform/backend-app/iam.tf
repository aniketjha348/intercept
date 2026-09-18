# --- GitHub Actions deploy role (OIDC, no keys) ---
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # Root CAs for token.actions.githubusercontent.com over time — AWS matches any.
  # 6938fd4d = DigiCert era; cabd2a79 = ISRG Root X1 (current Let's Encrypt chain,
  # verified against the letsencrypt.org root download).
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "cabd2a79a1076a31f21d253635cb039d4329a5e8",
  ]
}

resource "aws_iam_role" "deploy" {
  name = "InterceptGitHubDeploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      # TEMPORARY isolation test: aud-only (proves whether the sub condition
      # is the blocker). WILL BE RE-TIGHTENED before real users. See run log.
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
      }
    }]
  })
}

resource "aws_iam_role_policy" "deploy_ecr" {
  name = "EcrPush"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect   = "Allow",
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:CompleteLayerUpload", "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart", "ecr:BatchGetImage", "ecr:DescribeImages"],
        Resource = aws_ecr_repository.app.arn }
    ]
  })
}

resource "aws_iam_role_policy" "deploy_ecs" {
  name = "EcsDeploy"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect   = "Allow",
        Action   = ["ecs:UpdateService", "ecs:DescribeServices", "ecs:ListTasks", "ecs:DescribeTasks"],
        Resource = "*" },
      { Effect   = "Allow",
        Action   = ["iam:PassRole"],
        Resource = [aws_iam_role.exec.arn, aws_iam_role.task.arn]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        } }
    ]
  })
}

resource "aws_iam_role_policy" "deploy_website" {
  name = "WebsiteSync"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect   = "Allow", Action = ["s3:ListBucket"],
        Resource = "arn:aws:s3:::intercept-website" },
      { Effect   = "Allow", Action = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
        Resource = "arn:aws:s3:::intercept-website/*" },
      { Effect   = "Allow",
        Action   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"],
        Resource = "*" }
    ]
  })
}

# --- ECS task roles (least privilege) ---
resource "aws_iam_role" "exec" {
  name = "${var.project}-ecs-exec"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "exec_base" {
  role       = aws_iam_role.exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "exec_secrets" {
  name = "ReadSecrets"
  role = aws_iam_role.exec.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [aws_secretsmanager_secret.database_url.arn, aws_secretsmanager_secret.google_key.arn, aws_secretsmanager_secret.openai_key.arn]
    }]
  })
}

resource "aws_iam_role" "task" {
  name = "${var.project}-ecs-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  # No policies: the app calls no AWS APIs today.
}

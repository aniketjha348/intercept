output "api_url" {
  description = "Backend base URL → GitHub secret API_URL (no trailing slash)"
  value       = "http://${aws_lb.app.dns_name}"
}

output "ecr_uri" {
  description = "Image repo (CI pushes here)"
  value       = aws_ecr_repository.app.repository_url
}

output "deploy_role_arn" {
  description = "→ GitHub secret AWS_ROLE_ARN"
  value       = aws_iam_role.deploy.arn
}

output "cluster" {
  value = aws_ecs_cluster.app.name
}

output "service" {
  value = aws_ecs_service.app.name
}

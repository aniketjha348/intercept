resource "aws_ecr_repository" "app" {
  name                 = "${var.project}-backend"
  image_tag_mutability = "MUTABLE" # :latest moves on every push by design
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy     = <<EOF
{"rules":[{"rulePriority":1,"description":"keep last 10 images",
"selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":10},
"action":{"type":"expire"}}]}
EOF
}

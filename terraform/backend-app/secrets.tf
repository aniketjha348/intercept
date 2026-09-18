# Secrets live in Secrets Manager, NEVER in task env plaintext or images.
# Rotate: update the secret value → force a new ECS deployment (CI redeploy does it).
resource "aws_secretsmanager_secret" "database_url" {
  name                    = "${var.project}/DATABASE_URL"
  recovery_window_in_days = 0 # pilot: no 30-day limbo on replace
}

resource "aws_secretsmanager_secret_version" "database_url" {
  count         = var.db_url != "" ? 1 : 0 # empty = memory mode, no version stored
  secret_id     = aws_secretsmanager_secret.database_url.id
  secret_string = var.db_url
}

resource "aws_secretsmanager_secret" "google_key" {
  name                    = "${var.project}/GOOGLE_API_KEY"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "google_key" {
  count         = var.google_key != "" ? 1 : 0
  secret_id     = aws_secretsmanager_secret.google_key.id
  secret_string = var.google_key
}

resource "aws_secretsmanager_secret" "openai_key" {
  name                    = "${var.project}/OPENAI_API_KEY"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "openai_key" {
  count         = var.openai_key != "" ? 1 : 0
  secret_id     = aws_secretsmanager_secret.openai_key.id
  secret_string = var.openai_key
}

resource "aws_secretsmanager_secret" "whatsapp_token" {
  name                    = "${var.project}/WHATSAPP_TOKEN"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "whatsapp_token" {
  count         = var.whatsapp_token != "" ? 1 : 0
  secret_id     = aws_secretsmanager_secret.whatsapp_token.id
  secret_string = var.whatsapp_token
}

resource "aws_secretsmanager_secret" "livekit_secret" {
  name                    = "${var.project}/LIVEKIT_SECRET"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "livekit_secret" {
  count         = var.livekit_secret != "" ? 1 : 0
  secret_id     = aws_secretsmanager_secret.livekit_secret.id
  secret_string = var.livekit_secret
}

locals {
  # Secrets exist as versions only when set — ECS can only inject those.
  app_secrets = concat(
    var.db_url != "" ? [{ name = "DATABASE_URL", valueFrom = aws_secretsmanager_secret.database_url.arn }] : [],
    var.google_key != "" ? [{ name = "GOOGLE_API_KEY", valueFrom = aws_secretsmanager_secret.google_key.arn }] : [],
    var.openai_key != "" ? [{ name = "OPENAI_API_KEY", valueFrom = aws_secretsmanager_secret.openai_key.arn }] : [],
    var.whatsapp_token != "" ? [{ name = "WHATSAPP_TOKEN", valueFrom = aws_secretsmanager_secret.whatsapp_token.arn }] : [],
    var.livekit_secret != "" ? [{ name = "LIVEKIT_SECRET", valueFrom = aws_secretsmanager_secret.livekit_secret.arn }] : [],
  )
}

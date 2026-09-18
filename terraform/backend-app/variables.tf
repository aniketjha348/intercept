variable "project" {
  type    = string
  default = "intercept"
}

variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "db_url" {
  type      = string
  sensitive = true
  default   = ""
  description = "Neon Postgres string (empty = memory mode)"
}

variable "google_key" {
  type      = string
  sensitive = true
  default   = ""
  description = "Gemini key (empty = offline rules mode)"
}

variable "openai_key" {
  type      = string
  sensitive = true
  default   = ""
}

variable "apk_url" {
  type    = string
  default = "https://github.com/aniketjha348/intercept/releases/latest/download/app-debug.apk"
}

variable "llm_provider" {
  type    = string
  default = "auto"
}

variable "llm_model" {
  type    = string
  default = "gemini-3.5-flash-lite"
}

variable "desired_count" {
  type    = number
  default = 1
}

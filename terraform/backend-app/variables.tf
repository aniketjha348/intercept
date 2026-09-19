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

variable "livekit_url" {
  type    = string
  default = ""
  description = "LiveKit Cloud WS URL (empty = voice demo off)"
}

variable "livekit_key" {
  type    = string
  default = ""
}

variable "livekit_secret" {
  type      = string
  sensitive = true
  default   = ""
}

# Required for the caller to ever hear the AI. The app asks the backend where to
# send unknown calls; with this empty the answer is "not configured", the owner
# cannot arm forwarding, and the call is never handed to LiveKit at all — which
# is what silently killed AI answering in production. Set it to the number
# attached to the LiveKit inbound trunk / dispatch rule.
variable "assistant_forward_number" {
  type        = string
  default     = ""
  description = "DID unknown calls are forwarded to (empty = AI answering is unreachable)"
}

variable "whatsapp_token" {
  type      = string
  sensitive = true
  default   = ""
  description = "Meta Graph token for the bot number (empty = bot off)"
}

variable "whatsapp_phone_id" {
  type      = string
  default   = ""
  description = "Meta phone-number ID for the bot number"
}

variable "whatsapp_verify" {
  type    = string
  default = "intercept-verify"
  description = "Webhook verify string (must match Meta dashboard)"
}

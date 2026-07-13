const REDACTED = '[REDACTED]'

/**
 * Removes credentials and common financial/authentication values from PageAgent's simplified DOM
 * before the content is sent to Gemma. This is defense in depth even though inference is local.
 */
export function maskSensitivePageContent(content: string): string {
  let masked = content

  masked = masked.replace(
    /(type=["']?password["']?[^>]*value=)(["'][^"']*["']|[^\s>]+)/gi,
    `$1"${REDACTED}"`
  )
  masked = masked.replace(/\b\d{12,19}\b/g, REDACTED)
  masked = masked.replace(/\b(?:cvv|cvc)\s*[:=]?\s*\d{3,4}\b/gi, `CVV=${REDACTED}`)
  masked = masked.replace(/\b(?:otp|one[- ]time password|verification code)\s*[:=]?\s*\d{4,8}\b/gi, `OTP=${REDACTED}`)
  masked = masked.replace(/\b(?:upi pin|mpin|transaction pin)\s*[:=]?\s*\d{4,8}\b/gi, `PIN=${REDACTED}`)
  masked = masked.replace(/(authorization\s*[:=]\s*bearer\s+)[a-z0-9._~+/=-]+/gi, `$1${REDACTED}`)
  masked = masked.replace(/(access[_-]?token\s*[:=]\s*)[a-z0-9._~+/=-]+/gi, `$1${REDACTED}`)

  return masked
}

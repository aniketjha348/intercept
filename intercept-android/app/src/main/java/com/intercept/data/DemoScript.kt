package com.intercept.data

/** Scripted bank-KYC scam for the hackathon demo. Plays the classic
 *  AUTHORITY → URGENCY → FEAR → OTP chain so judges see risk climb live. */
object DemoScript {
    val callerNumber = "+91 98XXX XXXXX (demo)"
    val lines = listOf(
        "Hello, I am calling from SBI bank head office regarding your account KYC.",
        "Sir your account will be blocked within 2 hours if KYC is not verified right now.",
        "SBI bank alert: unauthorized login attempt on your account, it is compromised and will be frozen within 30 minutes.",
        "To verify, please click this link immediately: https://sbi-security-verify.example.com/login",
        "Now share the OTP you just received to complete verification.",
    )
}

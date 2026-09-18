# Testing Guide

> **Version:** v0.4.4  
> **Purpose:** Comprehensive testing procedures for Intercept AI call screening app

---

## Table of Contents

1. [Testing Strategy Overview](#testing-strategy-overview)
2. [Automated Testing](#automated-testing)
3. [Manual Testing Checklists](#manual-testing-checklists)
4. [Device Testing Matrix](#device-testing-matrix)
5. [Test Scenarios](#test-scenarios)
6. [Debugging Tools](#debugging-tools)
7. [Reporting Issues](#reporting-issues)

---

## Testing Strategy Overview

### Testing Layers

```text
┌─────────────────────────────────────────────────────────────┐
│                    TESTING PYRAMID                            │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│    📱 MANUAL TESTING     (40%)                                 │
│    - Real device scenarios                                     │
│    - User experience flows                                     │
│    - Edge cases and interactions                              │
│                                                               │
│    ⚙️ AUTOMATED TESTS    (35%)                                 */
│    - Unit tests                                                  */
│    - Integration tests                                           */
│    - Regression suite                                           */
│                                                               */
│    🧪 PERFORMANCE    (15%)                                    */
│    - Latency tests                                               */
│    - Memory profiling                                            */
│    - Battery impact analysis                                    */
│                                                               */
│    🔍 STATIC ANALYSIS   (10%)                               */
│    - Lint checks                                                 */
│    - Security scans                                              */
│                                                               */
└─────────────────────────────────────────────────────────────┘
```

### Testing Phases

| Phase | Focus | Tools | Pass Criteria |
|-------|-------|-------|---------------|
| Development | Unit & integration | JUnit, Mockito | 100% coverage on core logic |
| QA | Functional | Manual test cases | All critical paths pass |
| Beta | Real-world scenarios | Internal testers | No critical bugs |
| Release | Production validation | Play Console | Monitor crash-free rate |

---

## Automated Testing

### Test Structure

```text
app/src/
  test/                          # Local unit tests
    java/com/intercept/
      CallScreeningTest.kt
      AutoScreenServiceTest.kt
      ContactHelperTest.kt
      ApiServiceTest.kt
  
  androidTest/                     # Instrumented tests
    java/com/intercept/
      MainActivityTest.kt
      CallFlowTest.kt
```

### Running Tests

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.intercept.CallScreeningTest"

# Run with coverage
./gradlew testDebugUnitTestCoverage

# Run instrumentation tests on connected device
./gradlew connectedAndroidTest

# Lint checks
./gradlew lint

# Security scan
./gradlew dependencyCheckAnalyze
```

### Sample Unit Test

```kotlin
// CallScreeningTest.kt
@Test
fun `auto-answer should trigger for unknown callers`() {
    // Given
    val container = FakeAppContainer(
        setupDone = true,
        autoCalls = true
    )
    val number = "+15551234567"
    
    // When
    val shouldAutoAnswer = container.shouldAutoAnswer(number)
    
    // Then
    assertTrue(shouldAutoAnswer)
}

@Test
fun `known contact should NOT trigger auto-answer`() {
    // Given
    val container = FakeAppContainer(
        setupDone = true,
        autoCalls = true
    )
    val number = "+19876543210" // Known contact
    
    // When
    val shouldAutoAnswer = container.shouldAutoAnswer(number)
    
    // Then
    assertFalse(shouldAutoAnswer)
}
```

---

## Manual Testing Checklists

### Phase 1: Initial Setup Verification

- [ ] Install APK on clean test device
- [ ] Verify app opens without crash
- [ ] Grant all requested permissions
- [ ] Configure backend URL (if needed)
- [ ] Enable Call Screening role
- [ ] Set as Default Dialer
- [ ] Verify "Setup Complete" status
- [ ] Toggle auto-calls ON
- [ ] Toggle auto-SMS ON

### Phase 2: Call Screening Flow

#### Test A: Unknown Call with Auto-Answer ON
1. Get incoming call from unknown number
2. Verfiy call answers within 2 seconds
3. Verify AI starts screening immediately
4. Check live transcript appears correctly
5. Verify caller hears AI responses
6. End call when AI determines risk

#### Test B: Unknown Call with Auto-Answer OFF
1. Disable auto-calls in Settings
2. Get call from unknown number
3. Verify call is silenced
4. Verify notification appears immediately
5. Tap notification
6. Verify live screen opens correctly
7. Verify microphone access granted

#### Test C: Known Contact Call
1. Place call from saved contact
2. Verify call rings through normally
3. Verify NO notification appears
4. Verify call completes normally

#### Test D: Blocked Number Call
1. Ensure number is blocked
2. Get call from blocked number
3. Verify call is terminated immediately
4. Verify no AI processing occurs

### Phase 3: Voice Screening Testing

| Test Case | Steps | Expected Result |
|-----------|-------|-----------------|
| Caller speaks | During live screen, speak as caller | Transcript updates in real-time |
| AI speaks | AI detects scam/risk | AI voice plays through speaker |
| AI terminates | High risk detected | Call ends, report generated |
| User takeover | Tap mic button | Microphone captured, human response |
| No speech | Caller silent for 10s | AI prompts caller to speak |

### Phase 4: SMS Screening Testing

| Test Case | Steps | Expected Result |
|-----------|-------|-----------------|
| Unknown sender | Send SMS from unknown number | Risk analysis triggered |
| Risky SMS | Send threatening/spam SMS | High priority notification |
| Safe SMS | Send normal SMS from unknown | Silently processed |
| Contact SMS | Send SMS from saved contact | Ignored, no processing |

---

## Device Testing Matrix

### Android Version Compatibility

| Android Version | API Level | Expected Behavior | Notes |
|-----------------|-----------|-------------------|-------|
| Android 10 | 29 | ✅ Full support | First API with Telecom framework |
| Android 11 | 30 | ✅ Full support | Background restrictions apply |
| Android 12 | 31 | ✅ Full support | Requires exact alarm permission (optional) |
| Android 13 | 33 | ✅ Full support | Notification permission required |
| Android 14 | 34 | ✅ Full support | Target SDK version |

### Device Brand Compatibility

| Brand | Model | Status | Notes |
|-------|-------|--------|-------|
| Google | Pixel 4a, 6, 7, 8 | ✅ Recommended | Stock Android, best compatibility |
| Samsung | S21, S22, S23 | ✅ Tested | Check OEM dialer settings |
| OnePlus | 9, 10, 11 | ✅ Tested | Good Telecom support |
| Xiaomi | Redmi Note 10, 11 | ⚠️ Mixed | MIUI battery optimization issues |
| Huawei | P40, P50 | ⚠️ Limited | No Google Play Services |

### Carrier Testing

| Carrier | Region | Status | Notes |
|---------|--------|--------|-------|
| Verizon | US | ✅ Tested | No carrier restrictions |
| AT&T | US | ✅ Tested | Works with default dialer |
| T-Mobile | US | ✅ Tested | No special permissions needed |
| Vodafone | UK | ✅ Tested | Compatible with EU regulations |
| Jio | India | ⚠️ Caution | May require additional permissions |

---

## Test Scenarios

### Critical Path Scenarios

#### Scenario 1: First-Time User Journey

```
1. Install app → Home shows Setup
2. Grant permissions → Next step
3. Grant Call Screening role → Next step
4. Set as Default Dialer → Setup complete
5. Enable auto-calls → Ready for calls
```

**Expected:** Green status throughout, no crashes

#### Scenario 2: Auto-Answer Flow

```
1. Auto-calls = ON
2. Unknown number calls
3. system: CallScreeningService.onScreenCall()
3. InterceptScreeningService: setAllowCall(true)
4. InterceptInCallService: onCallAdded()
5. AutoScreenService: screenCall()
6. STT starts → AI analyzes → TTS responds
```

**Expected:** Call answers within 2s, AI screening begins

#### Scenario 3: Manual Intercept Flow

```
1. Auto-calls = OFF
2. Unknown number calls
3. system: CallScreeningService.silenceCall()
4. Notification shown
5. User taps notification
6. MainActivity → LiveCallScreen
7. AI screening begins
```

**Expected:** Notification appears, call silenced, live screen opens

### Edge Cases

| Scenario | Description | Expected Result |
|----------|-------------|-----------------|
| App killed during call | Force-stop, then incoming call | Notification shown, app restarts |
| Low battery | 5% battery | Screening continues, low power mode |
| No internet | Airplane mode | Local processing or offline mode |
| Background app | App not in foreground | Foreground service handles call |
| Multiple calls | Rapid incoming calls | Each handled sequentially |
| SMS spam burst | Multiple suspicious SMS | Batch processed, notifications collapsed |

### Error Recovery Scenarios

| Error | Recovery Action |
|-------|-----------------|
| Backend timeout | Retry 3x, then show local risk assessment |
| Permission denied | Redirect to Settings page |
| Role revoked | Show alert with instructions |
| Service died | Restart via BroadcastReceiver |

---

## Debugging Tools

### Logcat Filters

```bash
# All Intercept logs
adb logcat | grep -i intercept

# Specific services
adb logcat | grep "InterceptInCallService"
adb logcat | grep "AutoScreenService"
adb logcat | grep "InterceptScreeningService"

# Filter by log level
adb logcat *:E | grep Intercept  # Errors only
adb logcat *.V | grep Intercept  # Verbose (most detailed)
```

### Key Debug Logs

| Tag | Component | What to Look For |
|-----|-----------|------------------|
| `InterceptInCallService` | Call answering | "answer() called", "audio route set" |
| `AutoScreenService` | AI processing | "screenCall started", "STT started", "API call sent" |
| `InterceptScreeningService` | Call decisions | "shouldAutoAnswer", "setAllowCall" |
| `MainActivity` | App lifecycle | "onCreate", "intent handling" |

### Debug Commands

```bash
# Check notification listeners
adb shell dumpsys notification

# Check Telecom state
adb shell cmd telecom dump

# Check default dialer
adb shell cmd telecom get-default-dialer

# Check granted permissions
adb shell pm list permissions -g -r com.intercept

# Check running services
adb shell dumpsys activity services | grep intercept
```

### Network Debugging

```bash
# Capture network traffic
adb logcat -s "OkHttp"

# Check API connectivity
adb shell curl -v http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com/health

# Monitor HTTP requests
adb logcat | grep "--> POST"
adb logcat | grep "<-- HTTP"
```

---

## Reporting Issues

### Issue Template

When reporting bugs, please include:

1. **Device Information**
   - Brand and model
   - Android version (`/version` or Settings → About phone)
   - Intercept app version

2. **Steps to Reproduce**
   ```
   1. Install APK v0.4.4
   2. Grant permissions
   3. Enable auto-calls
   4. Call from +1-555-123-4567
   5. Observe behavior
   ```

3. **Expected Behavior**
   - What should happen

4. **Actual Behavior**
   - What actually happened

5. **Logs**
   - Relevant logcat output

### How to Share Logs

```bash
# Capture logs for 30 seconds during issue
adb logcat -d > intercept_logs.txt

# Filter to relevant logs only
adb logcat -d | grep -i intercept > intercept_filtered.txt
```

### Crash Report Analysis

```bash
# Check crash reports
adb logcat | grep "FATAL EXCEPTION"

# Check crashdump
adb shell ls /data/tombstones/
```

---

## Performance Testing

### Latency Benchmarks

| Component | Target | Measurement Method |
|-----------|--------|---------------------|
| Call to auto-answer | < 2 seconds | Manual timing |
| STT transcription | < 200ms | Log timestamps |
| API round-trip | < 500ms | Network timing |
| TTS response | < 300ms | Audio file size |

### Battery Impact

```bash
# Monitor battery usage
adb shell dumpsys batterystats

# Check foreground service wake locks
adb shell dumpsys power | grep -A 20 "Wake Locks"
```

### Memory Profiling

Use Android Studio Profiler:
1. Run app on device
2. Profile → Memory → Record
3. Trigger call screening
4. Check for memory leaks

---

## Release Checklist

### Pre-Release Validation

- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] No critical errors in logs
- [ ] Auto-answer works on Pixel 4a
- [ ] Manual flow works on Samsung S21
- [ ] Permissions flow completes
- [ ] Role granting works
- [ ] Backend health check passes
- [ ] Crash-free rate > 99.5%
- [ ] ANR rate < 0.1%

### Store Testing

- [ ] APK uploads successfully
- [ ] Internal test track works
- [ ] Alpha track approval pending
- [ ] Release notes drafted
- [ ] Screenshots ready

---

## Appendix

### Test Data

| Test Number | Type | Risk Level | Expected Action |
|-------------|------|------------|-----------------|
| +1-555-000-0001 | Unknown | Low | Ring through |
| +1-555-000-0002 | Unknown + Auto | N/A | Auto-answer, screen |
| +1-555-000-0003 | Scam pattern | High | Terminate call |
| +1-987-654-3210 | Contact | N/A | No screening |

### Test Contact List

Create these contacts on test device:
- Known safe contact (for testing no screening)
- Blocked contact (for testing blocked number handling)

---

*Document Version: v0.4.4*  
*Last Updated: September 2026*
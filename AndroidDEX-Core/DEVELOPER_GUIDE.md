# AndroidDEX-Core Developer Guide

## Optimization Workflow
AndroidDEX is a benchmark-driven project. Do not make optimization changes blindly. Follow this exact workflow:
1. Benchmark
2. Optimize one thing
3. Compare against the baseline
4. Repeat

## Performance Presets
The system is tested against three defined profiles, which can be selected at runtime:
- **Low Latency**: Minimum delay, strict interactive control.
- **Balanced**: Default remote desktop experience.
- **High Quality**: Maximum visual quality at the expense of minor latency increases.

## How to Start a Benchmark
1. Compile and launch the Windows Receiver (`cargo run` in `androiddex-video/receiver`).
2. Deploy the Android Host application.
3. Instead of starting the normal UI, invoke `LiveValidator.executeLiveValidationSequence()` on the host. This will pipe synthetic, timestamped frames through the local `MediaCodec` and over QUIC.

## How to Read Diagnostics Output
The `DiagnosticsApi` emits a structured JSON object. 
Pay close attention to the **Performance Budgets**:
- `EncodeLatency` (Budget: ≤ 10 ms)
- `TransportLatency` (Budget: ≤ 10 ms)
- `DecodeLatency` (Budget: ≤ 10 ms)

If a metric exceeds its budget, it will be marked with a `❌`. Focus your optimizations on the failing stages first.

## How to Compare Benchmark Runs
To ensure a change did not cause a regression:
1. Take the `metrics.json` from the baseline run (e.g. `benchmarks/baseline/2026-07-21.json`).
2. Take the `metrics.json` from your current run.
3. Feed both to `BenchmarkComparison.compare(previousRun, currentRun)`.
4. Review the generated Delta Report to see the precise `improvementPercentage`. Do not merge code that degrades P95 or P99 latencies without strong justification.

## Adding a New Benchmark Profile
1. Add the new profile to the `StressProfile` enum in `BenchmarkRunner.kt`.
2. Update the `runBenchmark()` `when` block to map the profile to a specific `SyntheticFrameGenerator` pattern (e.g., `generateNoise` to stress bitrate).
3. Ensure the duration is long enough to gather a statistically significant latency distribution.

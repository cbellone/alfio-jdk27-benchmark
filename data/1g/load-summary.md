
Medians per configuration (runs: jdk25=3, jdk27-aot-workload=3, jdk27-parallel-aot=3). Δ is relative to jdk25.

| metric | jdk25 | jdk27-aot-workload | jdk27-parallel-aot | Δ jdk27-aot-workload | Δ jdk27-parallel-aot |
|---|---|---|---|---|---|
| cgroup.oom_kills | 0.0 | 0.0 | 0.0 |  |  |
| cgroup.peak_mb | 885.2 | 932.5 | 879.8 | +5.3% | -0.6% |
| container.oom_killed | 0.0 | 0.0 | 0.0 |  |  |
| cpu.app.jetty_pct_of_core | 44.0 | 40.8 | 35.7 | -7.2% | -18.9% |
| cpu.app.other_pct_of_core | 0.1 | 0.0 | 0.0 | -38.2% | -38.2% |
| cpu.app.scheduled_pct_of_core | 35.7 | 33.6 | 29.5 | -6.0% | -17.4% |
| cpu.gc_pct_of_core | 40.0 | 20.4 | 4.9 | -49.0% | -87.7% |
| cpu.jit_pct_of_core | 4.2 | 4.6 | 4.2 | +9.3% | +0.9% |
| cpu.process_pct_of_core | 123.4 | 99.4 | 74.5 | -19.4% | -39.7% |
| cpu.vm_pct_of_core | 0.3 | 0.2 | 0.1 | -33.8% | -60.3% |
| gc.live_data_mb | 50.0 | 47.0 | 47.0 | -6.0% | -6.0% |
| gc.log.count | 3727.0 | 2085.0 | 1540.0 | -44.1% | -58.7% |
| gc.log.full_count | 0.0 | 0.0 | 14.0 |  |  |
| gc.log.heap_committed_max_mb | 512.0 | 512.0 | 504.0 | +0.0% | -1.6% |
| gc.log.pause_max_ms | 50.1 | 54.0 | 141.4 | +7.7% | +182.0% |
| gc.log.pause_p99_ms | 31.5 | 37.5 | 24.9 | +19.0% | -21.0% |
| gc.log.pause_total_ms | 35071.4 | 20222.5 | 12552.2 | -42.3% | -64.2% |
| latency.error_pct | 0.0 | 0.0 | 0.0 |  |  |
| latency.max_ms | 464.0 | 366.0 | 280.0 | -21.1% | -39.7% |
| latency.p50_ms | 12.0 | 9.0 | 7.0 | -25.0% | -41.7% |
| latency.p95_ms | 71.0 | 55.0 | 39.0 | -22.5% | -45.1% |
| latency.p99_ms | 150.0 | 112.0 | 80.0 | -25.3% | -46.7% |
| latency.throughput_rps | 134.6 | 134.6 | 134.6 | -0.0% | -0.0% |
| sampled.anon_avg_mb | 787.6 | 785.2 | 788.6 | -0.3% | +0.1% |
| sampled.anon_max_mb | 800.3 | 797.0 | 802.2 | -0.4% | +0.2% |
| sampled.cgroup_avg_mb | 855.4 | 898.4 | 847.4 | +5.0% | -0.9% |
| sampled.cgroup_max_mb | 865.7 | 910.9 | 861.3 | +5.2% | -0.5% |
| sampled.file_avg_mb | 55.7 | 103.8 | 48.9 | +86.3% | -12.2% |
| sampled.file_max_mb | 57.1 | 104.4 | 49.4 | +82.8% | -13.5% |
| sampled.rss_avg_mb | 834.9 | 836.8 | 840.0 | +0.2% | +0.6% |
| sampled.rss_max_mb | 849.0 | 848.9 | 853.6 | -0.0% | +0.6% |
| startup.ready_seconds | 7.1 | 5.1 | 5.1 | -28.1% | -28.7% |
| startup.spring_seconds | 6.4 | 4.5 | 4.3 | -30.1% | -32.4% |
| warmup.cpu.app.jetty_pct_of_core | 43.5 | 36.1 | 37.2 | -16.9% | -14.5% |
| warmup.cpu.app.other_pct_of_core | 0.0 | 0.0 | 0.0 | +20.0% | +0.0% |
| warmup.cpu.app.scheduled_pct_of_core | 32.7 | 28.5 | 28.5 | -12.9% | -12.8% |
| warmup.cpu.gc_pct_of_core | 29.3 | 6.9 | 4.3 | -76.4% | -85.5% |
| warmup.cpu.jit_pct_of_core | 28.9 | 26.7 | 30.2 | -7.9% | +4.3% |
| warmup.cpu.process_pct_of_core | 134.8 | 98.1 | 100.6 | -27.2% | -25.4% |
| warmup.cpu.vm_pct_of_core | 0.2 | 0.2 | 0.1 | -23.9% | -49.3% |
| warmup.first_30s_p95_ms | 63.0 | 50.0 | 63.0 | -20.6% | +0.0% |
| warmup.first_30s_p99_ms | 163.0 | 109.0 | 160.0 | -33.1% | -1.8% |
| warmup.first_60s_p95_ms | 48.0 | 42.0 | 48.0 | -12.5% | +0.0% |
| warmup.first_60s_p99_ms | 110.0 | 96.0 | 109.0 | -12.7% | -0.9% |
| warmup.latency.error_pct | 0.0 | 0.0 | 0.0 |  |  |
| warmup.latency.max_ms | 443.0 | 322.0 | 354.0 | -27.3% | -20.1% |
| warmup.latency.p50_ms | 14.0 | 9.0 | 9.0 | -35.7% | -35.7% |
| warmup.latency.p95_ms | 70.0 | 47.0 | 44.0 | -32.9% | -37.1% |
| warmup.latency.p99_ms | 158.0 | 104.0 | 103.0 | -34.2% | -34.8% |
| warmup.latency.throughput_rps | 110.1 | 110.1 | 110.1 | -0.0% | +0.0% |
| warmup.time_to_steady_state_seconds | 0.0 | 0.0 | 10.0 |  |  |

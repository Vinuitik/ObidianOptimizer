# Memory summary

samples: 10644, span: 7306s

## Per-container peak

container | peak MB | limit MB | t(s) | events within 30s
--- | --- | --- | --- | ---
obsopt-exp-backend-1 | 238 | 1200 | 5788 | submit-pdf(t+5777s); submit-video(t+5777s)
obsopt-exp-embedder-1 | 2200 | 2200 | 58 | after health-ok
obsopt-exp-frontend-1 | 8 | 64 | 29 | health-ok(t+27s)
obsopt-exp-llm-1 | 40 | 200 | 0 | up(t-2s); health-ok(t+27s)
obsopt-exp-postgres-1 | 178 | 300 | 0 | up(t-2s); health-ok(t+27s)
obsopt-exp-rabbitmq-1 | 182 | 300 | 2142 | after soak-end

**Sum of containers peak: 2729 MB at t=1785s** (after soak-end)
composition: obsopt-exp-backend-1=229, obsopt-exp-embedder-1=2196, obsopt-exp-frontend-1=8, obsopt-exp-llm-1=28, obsopt-exp-postgres-1=89, obsopt-exp-rabbitmq-1=178

**Host min MemAvailable: 1502 MB at t=4200s** (after soak-end)

OOM-killed containers: none
Restarts: obsopt-exp-embedder-1=1
Time to /api/health 200: 29s

## Events

- t=   -2s  up
- t=   27s  health-ok
- t=  507s  soak-end
- t= 5777s  submit-pdf
- t= 5777s  submit-video
- t= 7310s  ingest-end

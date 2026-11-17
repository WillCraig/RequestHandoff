# RequestHandoff export report

Requests exported: 8
Requests skipped: 2
Warnings: 3

## Limitations

These limits apply to every v0.1 export:

- Inline response-handler and pre-request scripts are translated to Postman `pm.*` where a documented equivalent exists. Calls without a safe equivalent are left as comments and listed in this report. A script file is inlined when its text is available; a missing script file is named in a comment and is not dropped.
- WebSocket and gRPC requests are skipped.
- Authorization headers are copied as headers. Postman auth objects are not created.
- Dynamic variables with a Postman equivalent are rewritten (`{{$uuid}}` becomes `{{$guid}}`). Unmapped dynamic variables are left unchanged and listed below. `{{$random.integer(from, to)}}` becomes `{{$randomInt}}`, which ignores that range. Host environment variables (`{{$env.NAME}}`, `{{$processEnv NAME}}`, and `{{$dotenv NAME}}`) are not read.

## Skipped requests

- requests.http — Socket — WebSocket requests are skipped in v0.1.
- requests.http — Rpc — gRPC requests are skipped in v0.1.

## Not converted

- http-client.private.env.json — Private environment file was present and was not read. Private values are excluded unless you explicitly enable them.
- requests.http — List items — Request flag '@no-log' was not converted.
- requests.http — Graph — Response handler script file './check.js' was not found and was not inlined.

## Environments

Named environments written: local.

## Output files

- `kitchen.postman_collection.json`
- `local.postman_environment.json`
- `requestHandoff-report.md`

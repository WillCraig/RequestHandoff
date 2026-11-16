# RequestHandoff export report

Requests exported: 2
Requests skipped: 0
Warnings: 2

## Limitations

These limits apply to every v0.1 export:

- Response-handler and pre-request scripts are not translated. Inline scripts are copied into the collection as commented-out script text. Script files are named in those comments and are not inlined.
- WebSocket and gRPC requests are skipped.
- Authorization headers are copied as headers. Postman auth objects are not created.
- Dynamic variables such as `{{$uuid}}` are left unchanged.

## Skipped requests

None.

## Not converted

- http-client.private.env.json — Private environment file was present and was not read. Private values are excluded unless you explicitly enable them.
- users/create.http — Create user — Pre-request script was not translated and is included as comments.

## Environments

Named environments written: dev.

## Output files

- `api.postman_collection.json`
- `dev.postman_environment.json`
- `requestHandoff-report.md`

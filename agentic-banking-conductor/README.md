# The commercial flow on Conductor OSS

The opportunity workflows of the [agentic Mifos sandbox](../agentic-mifos-sandbox/README.md) (intake, eligibility, identity document, assessment, registration), run on [Conductor OSS](https://conductor-oss.org) instead of OpenFn Lightning, to compare the two engines on the same flow. Both run against the same LOS, Fineract, models and rules, as the same LOS callers and Fineract users. The OpenFn version is in [agentic-banking-openfn](../agentic-banking-openfn/openfn/project.yaml).

It runs in the sandbox under the `conductor` profile (sandbox README, 3.6):

```bash
cd agentic-mifos-sandbox
docker compose --profile conductor up -d --build --wait
open http://localhost:8100     # Conductor's UI, read-only
```

**So far:** intake. Eligibility, identity, assessment and registration run on OpenFn only, for now.

## Layout

| Path | What |
|---|---|
| `definitions/tasks.json` | The task definitions: one per OpenFn job, with retries off and the job's time limits |
| `definitions/workflows/*.json` | One workflow per OpenFn workflow |
| `src/jobs/<workflow>.mjs` | The workflow's tasks: each OpenFn job's code, ported with its rules unchanged |
| `src/worker.mjs` | Runs one workflow's tasks: `node src/worker.mjs <workflow>` |
| `src/http.mjs` | The HTTP plumbing the jobs share, answering like OpenFn's language-http |
| `src/entry.mjs` | Starts workflows for channels and officers, with their API keys |
| `src/setup.mjs` | Loads `definitions/` into Conductor on every `up`, and fails if Conductor refuses any of them |
| `tests/` | `node --test tests/*.test.mjs`: the rules against a stand-in LOS, the entry against a stand-in Conductor, and the definitions against the workers |

One image (`Dockerfile`) serves the workers, the entry and the setup, each with its own command.

## From OpenFn jobs to Conductor tasks

Each OpenFn job is one Conductor task (`SIMPLE`, run by a worker), with the same code:

| OpenFn workflow | OpenFn job | Conductor workflow | Conductor task |
|---|---|---|---|
| opportunity-intake | intake-opportunity | `opportunity_intake` | `intake_opportunity` |

What changes is only the plumbing:

- **State.** A task gets the state the task before it returned (`inputParameters.state`) and returns the next one as its output, as OpenFn passes its state from job to job. The first task's state is `{data: <the workflow's input>}`, like a webhook's `state.data`; the last task's output is the workflow's result (`outputParameters.data`)
- **Calls.** `los.get(path, {query})` and `los.post(path, body)` for language-http's `get` and `post`, with the same answer (`.data`) and errors (`statusCode`, `body`)
- **Logs.** `log(...)` for `console.log`: the lines go to the task's logs in Conductor's UI, as a job's go to its run in Lightning
- **Failures.** A thrown error fails the task with its message. Retries are off (`retryCount: 0`), as in OpenFn: the workflow fails and the officer asks again. Turning them on is one line per task definition
- **Time limits.** Each task has its job's: 60 seconds for intake. A task no worker polls for within `pollTimeoutSeconds` (60) times the workflow out with "Task poll timed out", rather than leaving it waiting
- **Bounds.** A task's run is cut at its time limit: every call a job makes goes through its clients, which abort when the task's deadline fires, so several slow calls together cannot outlast it; the task then fails with "ran past its time limit". Reporting a result to Conductor is bounded too: an SDK call ends within 14 seconds (its 10-second timeout and a connection back-off already started), and a result takes 3 attempts of up to two calls each (`update-v2`, then the legacy update if it answers 404 or 405) with 10 and 20 seconds between them, at most 114 seconds (`RESULT_SECONDS`)
- **Stopping.** On `docker compose stop`, a worker takes no new tasks and waits until the poll under way, the tasks it returned and the running tasks have finished and reported to Conductor, so a stop does not cut a job between two LOS calls. With the bounds above, that takes at most 14 seconds for the poll, its tasks' longest time limit and 114 seconds to report (intake: 188; `WORKER_DRAIN_SECONDS` overrides it), within the container's `stop_grace_period` (195 seconds; a worker with longer tasks needs a longer one); an idle worker stops at once. One task can still be dropped: one Conductor hands over together with the last result (`update-v2`), which the SDK does not run once polling has stopped. It never started, so it changed nothing; Conductor times it out and its workflow fails, to be asked again

## Workers and credentials

One worker container per workflow, each holding only its own credentials and joining only the networks of the services it calls ([check-hardening.py](../agentic-mifos-sandbox/scripts/check-hardening.py) checks this in CI):

| Worker | Credentials | Networks |
|---|---|---|
| `conductor-intake` | `LOS_INTAKE_TOKEN` | `conductor`, `los-api` |

A job lists the clients it needs (`needs`); the worker builds only those, from its environment (`src/worker.mjs`, `CLIENTS`), and fails to start when one is missing. OpenFn's single worker, by contrast, receives every workflow's credentials from Lightning.

## The entry

Conductor OSS has no authentication, so its API is not where channels and officers start workflows. They call the entry, through the Conductor container's nginx on the sandbox's `CONDUCTOR_PORT`, with the same API keys as Lightning's webhooks:

```bash
KEY=$(grep '^OPENFN_INTAKE_API_KEY=' .env | cut -d= -f2-)
curl -s -X POST localhost:8100/i/intake -H "x-api-key: $KEY" -H 'Content-Type: application/json' \
  -d '{"channel": "web", "external_id": "web-2001", "full_name": "Ana López", "phone": "+52 55 8765-4321"}'
```

| Route | Key | Reply |
|---|---|---|
| `/i/intake` | channels' (`OPENFN_INTAKE_API_KEY`) | When the workflow has finished |

The replies are Lightning's: `201 {"data": <result>, "meta": {"state": "success", "workflow_id": ...}}` once the workflow has finished, `504 {"error": "timeout"}` when it outlasts the 30-second reply deadline (it goes on), `500` with `meta.state` `failed` when it fails, `401` without the route's key and `404` for an unknown workflow. The entry runs the workflow with Conductor's synchronous call (`POST /api/workflow/execute/{name}/{version}`), so it does not poll.

## Adding a workflow

1. Port each OpenFn job to a function in `src/jobs/<workflow>.mjs`; export `needs` and `tasks` (task name to function, in order)
2. Add each task to `definitions/tasks.json` (retries off, the job's time limits) and the workflow to `definitions/workflows/`: each task's `inputParameters` is `{"state": "${<the task before>.output}"}`, and `outputParameters` is `{"data": "${<the last task>.output}"}`
3. A client the job needs and the worker cannot build yet goes in `CLIENTS` (`src/worker.mjs`)
4. Add a route to `ROUTES` (`src/entry.mjs`)
5. In the sandbox: a `conductor-<workflow>` service (copy `conductor-intake`) with only its credentials and networks, and its networks in `check-hardening.py`

`tests/definitions.test.mjs` fails until the definitions, workers and routes agree.

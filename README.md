# Fuchsia AI Chatbot

Ingestion service for the Fuchsia AI chatbot (currently: Temporal workflow that polls folder1 and copies new files to folder2).
Containerized using Docker and through VSCode's Dev Container Extension.

## Architecture

```
client ──gRPC :50051──▶ gRPC server ──▶ folder1 ──Temporal sync──▶ folder2
```

- **gRPC server** (`fuchsia.FileIngestionServer`): accepts file uploads and writes them to `folder1`. Rejects invalid filenames (path separators, leading `.`), empty files, duplicates, and files over ~4 MB.
- **Temporal worker** (`fuchsia.IngestionWorker`): polls `folder1` every 60s and copies new files to `folder2`.
- **Test client** (`fuchsia.FileIngestionClient`): uploads files to the gRPC server. Will be replaced by the email inbox in a later phase.

The gRPC server and the Temporal sync are independent: they only share `folder1`. Either can run without the other.

## Quick start **WITH** Docker Locally

Requires only [Docker Desktop](https://www.docker.com/products/docker-desktop/).

```bash
git clone https://github.com/Amsal-J/fuchsia-ai-chatbot.git
cd fuchsia-ai-chatbot
docker compose up --build
```

- Drop files into `folder1/`. They're copied to `folder2/` on the next poll (every 60s).
- Temporal Web UI: http://localhost:8233
- gRPC server: `localhost:50051` (starts automatically with `docker compose up`)
- Stop everything: `Ctrl+C`, then `docker compose down`

### Sending files through gRPC (Docker)

With `docker compose up` running, in a second terminal:

```bash
mkdir -p outbox
echo "some text" > outbox/FILENAME.txt
docker compose run --rm client /outbox/some-file.pdf
```

- Put files you want to send in `outbox/`, then pass their path as `/outbox/<filename>`.
- The client prints `OK` or `FAIL` with the reason for each file. You can pass several files at once.
- Uploaded files land in `folder1/`, then the Temporal sync copies them to `folder2/` on the next poll.

## Quick start **WITHOUT** Docker Locally

Requires Java 21, Maven, and the Temporal CLI. Each terminal command will require its own terminal window to be run on (3 total terminal windows).

Download links for the required programs to run locally:
- **Java 21**: [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) (or [Oracle JDK 21](https://www.oracle.com/java/technologies/downloads/#java21))
- **Maven**: [Download](https://maven.apache.org/download.cgi) · [Install guide](https://maven.apache.org/install.html)
- **Temporal CLI**: [Install guide](https://docs.temporal.io/cli/setup-cli) (macOS: `brew install temporal`)

Running the project locally:

1. `temporal server start-dev --db-filename ~/temporal.db` to start the server, will continue indefinitely
2. `mvn compile exec:java -Dexec.mainClass="fuchsia.IngestionWorker"` to start the worker, will continue indefinitely
3. `mvn compile exec:java -Dexec.mainClass="fuchsia.IngestionStarter"` to start the workflow, will stop once workflow has started

To stop the execution of program being run locally:
1. `Ctrl+C` the worker tab.
2. Run the command `temporal workflow terminate --workflow-id fuchsia-ingestion --reason "stopping sync"` to stop the workflow.
3. Then you may close down the Temporal Server by pressing `Ctrl+C` on that terminal window.
4. If running, `Ctrl+C` the gRPC server tab.

### Sending files through gRPC (locally)

Uses two more terminal windows:

1. `mvn compile exec:java -Dexec.mainClass="fuchsia.FileIngestionServer"` to start the gRPC server on port 50051, will continue indefinitely
2. `mvn -q compile exec:java -Dexec.mainClass="fuchsia.FileIngestionClient" -Dexec.args="/path/to/file.txt"` to upload a file, will stop once the upload is done

Pass several files in `-Dexec.args` separated by spaces to upload them all at once. The gRPC server works on its own: files land in `folder1/` even if the Temporal worker isn't running, and get synced once it is.

## Develop in a container (VS Code)

Requires [Docker Desktop](https://www.docker.com/products/docker-desktop/), [VS Code](https://code.visualstudio.com/), and the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers).

1. Clone the repo and open the folder in VS Code
2. Click **Reopen in Container** when prompted (or Cmd/Ctrl+Shift+P → **Dev Containers: Reopen in Container**)
3. Wait for the first build (a few minutes; later opens take seconds)

The container includes JDK 21, Maven, the Temporal CLI, and the Java extensions, with a Temporal server running alongside it. From the VS Code terminal:

```bash
mvn test
mvn compile exec:java -Dexec.mainClass="fuchsia.IngestionWorker"
mvn compile exec:java -Dexec.mainClass="fuchsia.IngestionStarter"
```

To run the gRPC server and send a file from the dev container:

```bash
mvn compile exec:java -Dexec.mainClass="fuchsia.FileIngestionServer"
mvn -q compile exec:java -Dexec.mainClass="fuchsia.FileIngestionClient" -Dexec.args="/path/to/file.txt"
```

- Temporal UI: http://localhost:8233
- gRPC server: port 50051 is forwarded, so clients on your machine can also reach it at `localhost:50051`
- Don't run this and `docker compose up` at the same time (both use ports 7233/8233)
- The gRPC server also can't run in both at once (both use port 50051)

Run the worker and starter in separate terminals (use the split-terminal button in VS Code's terminal panel).

To exit: click the `><` button in the bottom-left → **Reopen Folder Locally**. This also stops the containers.

## Tests

Run `mvn test` from the dev container terminal, or locally if you have Java 21 and Maven installed. Tests also run automatically on every pull request via GitHub Actions.

The suite (68 tests) covers:
- **Temporal activities**: scanning `folder1`, copying to `folder2`, skipping hidden files and duplicates
- **Temporal workflow**: polling interval, retries, failure recovery, continue-as-new (uses Temporal's time-skipping test server)
- **Filename validation**: allowed names, boundaries, path traversal attempts
- **gRPC service**: uploads, rejections, concurrent uploads, disk failures (uses an in-process gRPC server)
- **gRPC server**: start/stop, port handling, the 4 MB message limit
- **gRPC client**: success and failure results, client-side size check
- **Handoff**: files uploaded via gRPC are found and copied by the Temporal sync
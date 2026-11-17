# Fuchsia AI Chatbot

Ingestion service for the Fuchsia AI chatbot (currently: Temporal workflow that polls folder1 and copies new files to folder2).
Containerized using Docker and through VSCode's Dev Container Extension.

## Quick start **WITH** Docker Locally

Requires only [Docker Desktop](https://www.docker.com/products/docker-desktop/).

```bash
git clone https://github.com/Amsal-J/fuchsia-ai-chatbot.git
cd fuchsia-ai-chatbot
docker compose up --build
```

- Drop files into `folder1/`. They're copied to `folder2/` on the next poll (every 60s).
- Temporal Web UI: http://localhost:8233
- Stop everything: `Ctrl+C`, then `docker compose down`

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
2. Run the command `temporal workflow terminate --workflow-id fuchsia-ai-chatbot --reason "stopping sync"` to stop the workflow.
3. Then you may close down the Temporal Server by pressing `Ctrl+C` on that terminal window.

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

- Temporal UI: http://localhost:8233
- Don't run this and `docker compose up` at the same time (both use ports 7233/8233)

Run the worker and starter in separate terminals (use the split-terminal button in VS Code's terminal panel).

To exit: click the `><` button in the bottom-left → **Reopen Folder Locally**. This also stops the containers.

## Tests

Run `mvn test` from the dev container terminal, or locally if you have Java 21 and Maven installed. Tests also run automatically on every pull request via GitHub Actions.
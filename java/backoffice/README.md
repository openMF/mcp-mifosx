# Mifos X - AI - Model Context Protocol (MCP)

## Use

In your AI agent tool (ChatGPT, Google AI Studio, Claude Desktop) add this MCP configuration

```shell script
{
    "mcpServers": {
        "mifosx": {
            "command": "jbang",
            "args": ["--quiet",
                    "org.mifos.community.ai:mcp-server:1.0.0-SNAPSHOT:runner"],
             "env":{
				"MIFOSX_BASE_URL":"",
				"MIFOSX_BASIC_AUTH_TOKEN":"",
				"MIFOS_TENANT_ID":""
             }
        }
    }
}
```

## Introduction

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/mcp-server-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- Qute ([guide](https://quarkus.io/guides/qute)): Offer templating support for web, email, etc in a build time, type-safe way
- Model Context Protocol (MCP) Server for standard input/output (stdio) ([guide](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)): This extension enables developers to implement the MCP server features easily using stanard input/output (stdio).

## MCP Developer Tools

For development and testing, you can use the MCP Inspector tool:

```shell script
npx @modelcontextprotocol/inspector
```

This starts a local web server where you can test your MCP server and you can connect it using STDIO or SSE


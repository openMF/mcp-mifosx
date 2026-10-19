# Build the dependency wheels. Some dependencies have no prebuilt wheel for a
# new Python release yet (zstandard, via langchain-core) and need a compiler,
# which stays in this stage.
FROM python:3.15-slim AS wheels
RUN apt-get update \
 && apt-get install -y --no-install-recommends gcc libc6-dev \
 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt

# Use the official Python image
FROM python:3.15-slim

# Set environment variables
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# Set the working directory
WORKDIR /app

# Copy the requirements file
COPY requirements.txt .

# Install dependencies from the wheels built above, without a compiler
RUN --mount=type=bind,from=wheels,source=/wheels,target=/wheels \
    pip install --no-cache-dir --no-index --find-links /wheels -r requirements.txt

# Copy the rest of the application code
COPY . .

# Set the default command to run the MCP Server
ENTRYPOINT ["python", "mcp_server.py"]

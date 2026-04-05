# Build stage
FROM golang:1.23-alpine AS builder

WORKDIR /app

# Install git for fetching dependencies
RUN apk add --no-cache git

# Copy go mod and sum files
COPY go.mod go.sum ./

# Download all dependencies
RUN go mod download

# Copy the source code
COPY . .

# Build the application
# Disable CGO for a statically linked binary
RUN CGO_ENABLED=0 GOOS=linux go build -o mcp-server main.go

# Final stage
FROM alpine:latest

WORKDIR /app

# Install ca-certificates for HTTPS
RUN apk --no-cache add ca-certificates tzdata

# Copy the binary from the builder stage
COPY --from=builder /app/mcp-server .

# Add non-root user for security
RUN adduser -D mcpuser
RUN chown -R mcpuser:mcpuser /app
USER mcpuser

# Stdio servers don't expose ports, they communicate via stdin/stdout
# Entrypoint defaults to running the binary
ENTRYPOINT ["./mcp-server"]

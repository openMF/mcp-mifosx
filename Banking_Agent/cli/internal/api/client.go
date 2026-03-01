package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
)

// ServerURL is set by the root command's --server flag.
var ServerURL = "http://localhost:8000"

func prettyPrint(data []byte) {
	var out bytes.Buffer
	if err := json.Indent(&out, data, "", "  "); err != nil {
		fmt.Println(string(data))
		return
	}
	fmt.Println(out.String())
}

func do(method, path string, body interface{}) {
	url := ServerURL + path

	var reqBody io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			fmt.Fprintf(os.Stderr, "❌  Failed to encode request: %v\n", err)
			return
		}
		reqBody = bytes.NewReader(b)
	}

	req, err := http.NewRequest(method, url, reqBody)
	if err != nil {
		fmt.Fprintf(os.Stderr, "❌  Failed to create request: %v\n", err)
		return
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		fmt.Fprintf(os.Stderr, "❌  Connection failed — is the server running at %s?\n    %v\n", ServerURL, err)
		return
	}
	defer resp.Body.Close()

	respBytes, _ := io.ReadAll(resp.Body)

	if resp.StatusCode >= 400 {
		fmt.Fprintf(os.Stderr, "⚠️  HTTP %d:\n", resp.StatusCode)
		prettyPrint(respBytes)
		return
	}

	fmt.Printf("✅  HTTP %d\n", resp.StatusCode)
	prettyPrint(respBytes)
}

func Get(path string)                    { do("GET", path, nil) }
func Post(path string, body interface{}) { do("POST", path, body) }
func Put(path string, body interface{})  { do("PUT", path, body) }

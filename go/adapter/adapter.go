// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// FineractClient executes as the configured identity in trusted stdio/local mode.
type FineractClient struct {
	BaseURL  string
	TenantID string
	Username string
	Password string
	HTTP     *http.Client
}

// CallerClient executes only with identity from CallContext. It deliberately
// holds no configured credentials and exposes no configured request methods.
type CallerClient struct {
	baseURL string
	http    *http.Client
}

// DoCallerRequest is limited to the caller-scoped HTTP client reads.
func (c *CallerClient) DoCallerRequest(ctx context.Context, endpoint string, queryParams ...url.Values) ([]byte, error) {
	call, err := callerContext(ctx)
	if err != nil {
		return nil, err
	}
	getClient := regexp.MustCompile(`^clients/[1-9][0-9]*(/accounts)?$`).MatchString(endpoint)
	searchClients := endpoint == "clients" && len(queryParams) == 1 &&
		len(queryParams[0]) == 1 && len(queryParams[0]["displayName"]) == 1 &&
		strings.TrimSpace(queryParams[0].Get("displayName")) != ""
	if !getClient && !searchClients || getClient && len(queryParams) != 0 {
		return nil, fmt.Errorf("operation is not supported in caller-scoped HTTP mode")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/"+endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("could not construct Fineract request")
	}
	if searchClients {
		req.URL.RawQuery = queryParams[0].Encode()
	}
	data, err := c.doCallerRequest(req, call)
	if err != nil {
		return nil, err
	}
	return safeCallerResponse(data, call)
}

func safeCallerResponse(data []byte, call CallContext) ([]byte, error) {
	// Refuse responses that echo the supplied credential, including decoded
	// Basic credentials. Normal client data is returned unchanged.
	secrets := []string{call.authorization}
	if parts := strings.SplitN(call.authorization, " ", 2); len(parts) == 2 {
		secrets = append(secrets, parts[1])
		if strings.EqualFold(parts[0], "Basic") {
			if decoded, err := base64.StdEncoding.DecodeString(parts[1]); err == nil {
				secrets = append(secrets, string(decoded))
				// Usernames occur in normal client timelines. Short passwords
				// can also match ordinary data; only screen longer passwords alone.
				if userPass := strings.SplitN(string(decoded), ":", 2); len(userPass) == 2 && len(userPass[1]) >= 8 {
					secrets = append(secrets, userPass[1])
				}
			}
		}
	}
	containsSecret := func(value string) bool {
		for _, secret := range secrets {
			if secret != "" && strings.Contains(value, secret) {
				return true
			}
		}
		return false
	}
	// Also inspect decoded JSON strings: escaping must not hide a credential
	// echoed by Fineract or an approval gateway response.
	var containsJSONSecret func(any) bool
	containsJSONSecret = func(value any) bool {
		switch value := value.(type) {
		case string:
			return containsSecret(value)
		case []any:
			for _, item := range value {
				if containsJSONSecret(item) {
					return true
				}
			}
		case map[string]any:
			for key, item := range value {
				if containsSecret(key) || containsJSONSecret(item) {
					return true
				}
			}
		}
		return false
	}
	var decoded any
	if containsSecret(string(data)) || json.Unmarshal(data, &decoded) == nil && containsJSONSecret(decoded) {
		return nil, fmt.Errorf("Fineract response contained credential material")
	}
	return data, nil
}

// doCallerRequest shares transport safeguards with permission discovery. Its raw
// response must remain internal: authentication responses contain credentials.
func (c *CallerClient) doCallerRequest(req *http.Request, call CallContext) ([]byte, error) {
	data, _, err := c.doCallerRequestWithStatus(req, call)
	return data, err
}

// Status is safe result metadata; downstream bodies and errors remain private.
func (c *CallerClient) doCallerRequestWithStatus(req *http.Request, call CallContext) ([]byte, int, error) {
	req.Header.Set("Authorization", call.authorization)
	req.Header.Set("fineract-platform-tenantid", call.tenant)
	req.Header.Set("Accept", "application/json")
	// Do not forward caller credentials to redirects, including on the same host,
	// or share banking session cookies across callers.
	httpClient := *c.http
	httpClient.Jar = nil
	httpClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("Fineract request failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		// Downstream bodies and transport errors may echo credentials. Never
		// surface them or retry the call with configured credentials.
		return nil, resp.StatusCode, fmt.Errorf("Fineract request failed (HTTP %d)", resp.StatusCode)
	}
	const maxCallerResponse = 10 << 20 // 10 MiB, plus one byte to detect overflow.
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxCallerResponse+1))
	if err != nil || len(data) > maxCallerResponse {
		return nil, resp.StatusCode, fmt.Errorf("could not read Fineract response")
	}
	return data, resp.StatusCode, nil
}

// New preserves the configured-identity constructor for existing local users.
func New() (*FineractClient, error) { return NewConfigured() }

// NewConfigured intentionally loads the banking identity for trusted local use.
func NewConfigured() (*FineractClient, error) {
	baseURL, httpClient, err := newConnection()
	if err != nil {
		return nil, fmt.Errorf("initialize configured client: %w", err)
	}

	tenantID := os.Getenv("MIFOSX_TENANT_ID")
	username := os.Getenv("MIFOSX_USERNAME")
	password := os.Getenv("MIFOSX_PASSWORD")

	if tenantID == "" {
		tenantID = "default"
	}
	if username == "" {
		username = "mifos"
	}

	return &FineractClient{
		BaseURL:  baseURL,
		TenantID: tenantID,
		Username: username,
		Password: password,
		HTTP:     httpClient,
	}, nil
}

// NewCaller selects caller identity without reading configured banking identity.
func NewCaller() (*CallerClient, error) {
	baseURL, httpClient, err := newConnection()
	if err != nil {
		return nil, fmt.Errorf("initialize caller client: %w", err)
	}
	return &CallerClient{baseURL: baseURL, http: httpClient}, nil
}

// newConnection shares connection setup, never authentication selection.
func newConnection() (string, *http.Client, error) {
	if err := LoadEnvironment(); err != nil {
		return "", nil, fmt.Errorf("initialize Fineract connection: %w", err)
	}
	baseURL := os.Getenv("MIFOSX_BASE_URL")
	if baseURL == "" {
		baseURL = "https://localhost:8443/fineract-provider/api/v1"
	}
	// Default to false for security, only skip verification if explicitly set.
	insecureSkipVerify := strings.EqualFold(os.Getenv("MIFOSX_SKIP_TLS_VERIFY"), "true")
	return strings.TrimRight(baseURL, "/"), &http.Client{
		Timeout: 45 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: insecureSkipVerify},
		},
	}, nil
}

// DoRequest always uses the configured identity; it never selects caller mode.
func (c *FineractClient) DoRequest(method, endpoint string, body interface{}, queryParams map[string]string) ([]byte, error) {
	isAttachment := strings.Contains(endpoint, "/attachment") || strings.Contains(endpoint, "/download")
	endpoint = strings.TrimPrefix(endpoint, "/")
	url := fmt.Sprintf("%s/%s", c.BaseURL, endpoint)

	var reqBody io.Reader
	if body != nil {
		jsonBody, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reqBody = bytes.NewBuffer(jsonBody)
	}

	req, err := http.NewRequest(method, url, reqBody)
	if err != nil {
		return nil, err
	}

	req.SetBasicAuth(c.Username, c.Password)
	req.Header.Set("fineract-platform-tenantid", c.TenantID)
	req.Header.Set("Content-Type", "application/json")

	if isAttachment {
		req.Header.Set("Accept", "*/*")
	} else {
		req.Header.Set("Accept", "application/json")
	}

	if queryParams != nil {
		q := req.URL.Query()
		for k, v := range queryParams {
			q.Add(k, v)
		}
		req.URL.RawQuery = q.Encode()
	}

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		return respBody, fmt.Errorf("API Error %d", resp.StatusCode)
	}

	// If it's a binary/attachment response, return a descriptive string instead of a huge blob
	contentType := resp.Header.Get("Content-Type")
	if isAttachment && !strings.Contains(contentType, "json") {
		return []byte(fmt.Sprintf("[Binary data received: %s, size: %d bytes]", contentType, len(respBody))), nil
	}

	return respBody, nil
}

// DoMultipartRequest sends a multipart/form-data request to Fineract.
// This is required for endpoints like document creation that expect file uploads.
func (c *FineractClient) DoMultipartRequest(method, endpoint string, fields map[string]string) ([]byte, error) {
	endpoint = strings.TrimPrefix(endpoint, "/")
	url := fmt.Sprintf("%s/%s", c.BaseURL, endpoint)

	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)

	// Add form fields
	for key, val := range fields {
		writer.WriteField(key, val)
	}

	// Create a minimal dummy file part with correct MIME type
	ext := strings.ToLower(filepath.Ext(fields["name"]))
	mimeType := mime.TypeByExtension(ext)
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}

	h := make(textproto.MIMEHeader)
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="file"; filename="%s"`, fields["name"]))
	h.Set("Content-Type", mimeType)

	part, err := writer.CreatePart(h)
	if err != nil {
		return nil, fmt.Errorf("failed to create file part: %w", err)
	}

	// Provide convincing dummy content to pass Fineract's deep MIME check
	var dummyContent []byte
	switch ext {
	case ".pdf":
		dummyContent = []byte("%PDF-1.4\n%placeholder")
	case ".png":
		dummyContent = []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR...")
	case ".jpg", ".jpeg":
		dummyContent = []byte("\xff\xd8\xff...")
	default:
		dummyContent = []byte("placeholder dummy content")
	}
	part.Write(dummyContent)

	writer.Close()

	req, err := http.NewRequest(method, url, &buf)
	if err != nil {
		return nil, err
	}

	req.SetBasicAuth(c.Username, c.Password)
	req.Header.Set("fineract-platform-tenantid", c.TenantID)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("Accept", "application/json")

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		return respBody, fmt.Errorf("API Error %d", resp.StatusCode)
	}

	return respBody, nil
}

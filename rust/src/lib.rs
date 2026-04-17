// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//! Library entry point for the Mifos MCP Server.
//! This module re-exports the public API surface needed by benchmarks and tests.

pub mod adapter;
pub mod domains;
pub mod embeddings;
pub mod registry;
pub mod server;

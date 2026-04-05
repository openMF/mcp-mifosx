// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

func GetGroupToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "list_groups", Description: "List available groups",
			Method: "GET", EndpointURL: "groups", Params: []ToolParam{{Name: "limit", Type: "number", Required: false, IsQueryVar: true}},
		},
		{
			Name: "get_group", Description: "Get group details",
			Method: "GET", EndpointURL: "groups/%v", Params: []ToolParam{{Name: "groupId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "create_group", Description: "Create group",
			Method: "POST", EndpointURL: "groups", Params: []ToolParam{
				{Name: "name", Type: "string", Required: true, IsBodyVar: true},
				{Name: "officeId", Type: "number", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "activate_group", Description: "Activate group",
			Method: "POST", EndpointURL: "groups/%v?command=activate", Params: []ToolParam{
				{Name: "groupId", Type: "number", Required: true, IsPathVar: true},
				{Name: "activationDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "add_group_member", Description: "Add member to group",
			Method: "POST", EndpointURL: "groups/%v?command=associateClients", Params: []ToolParam{
				{Name: "groupId", Type: "number", Required: true, IsPathVar: true},
				{Name: "clientMembers", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "remove_group_member", Description: "Remove member from group",
			Method: "POST", EndpointURL: "groups/%v?command=disassociateClients", Params: []ToolParam{
				{Name: "groupId", Type: "number", Required: true, IsPathVar: true},
				{Name: "clientMembers", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "get_group_accounts", Description: "Get group accounts",
			Method: "GET", EndpointURL: "groups/%v/accounts", Params: []ToolParam{{Name: "groupId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "create_group_savings_account", Description: "Create savings account for a group",
			Method: "POST", EndpointURL: "savingsaccounts", Params: []ToolParam{
				{Name: "groupId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "productId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "submittedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "update_group", Description: "Update group settings",
			Method: "PUT", EndpointURL: "groups/%v", Params: []ToolParam{
				{Name: "groupId", Type: "number", Required: true, IsPathVar: true},
				{Name: "name", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "close_group", Description: "Close group",
			Method: "POST", EndpointURL: "groups/%v?command=close", Params: []ToolParam{
				{Name: "groupId", Type: "number", Required: true, IsPathVar: true},
				{Name: "closureDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "closureReasonId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "list_centers", Description: "List centers",
			Method: "GET", EndpointURL: "centers", Params: []ToolParam{{Name: "limit", Type: "number", Required: false, IsQueryVar: true}},
		},
		{
			Name: "get_center", Description: "Get center",
			Method: "GET", EndpointURL: "centers/%v", Params: []ToolParam{{Name: "centerId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "create_center", Description: "Create center",
			Method: "POST", EndpointURL: "centers", Params: []ToolParam{
				{Name: "name", Type: "string", Required: true, IsBodyVar: true},
				{Name: "officeId", Type: "number", Required: true, IsBodyVar: true},
			},
		},
	}
}

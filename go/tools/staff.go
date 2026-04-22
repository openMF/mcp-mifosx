// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

func GetStaffToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "list_staff", Description: "List all staff members",
			Method: "GET", EndpointURL: "staff", Params: []ToolParam{},
		},
		{
			Name: "get_staff_details", Description: "Get staff details",
			Method: "GET", EndpointURL: "staff/%v", Params: []ToolParam{{Name: "staffId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "list_offices", Description: "List branches",
			Method: "GET", EndpointURL: "offices", Params: []ToolParam{},
		},
		{
			Name: "get_office_details", Description: "Get office info",
			Method: "GET", EndpointURL: "offices/%v", Params: []ToolParam{{Name: "officeId", Type: "number", Required: true, IsPathVar: true}},
		},
	}
}

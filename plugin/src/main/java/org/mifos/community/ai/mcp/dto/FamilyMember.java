/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties({"relationshipCodeValueId", "defaultRelationshipId", "genderCodeValueId",
        "defaultGenderId", "professionCodeValueId", "maritalStatusCodeValueId"})
public class FamilyMember {
    String firstName;
    String middleName;
    String lastName;
    String qualification;
    Integer age;
    String isDependent;
    Integer relationshipId;
    Integer genderId;
    Integer professionId;
    Integer maritalStatusId;
    String dateOfBirth;
    String dateFormat;
    String locale;

    final Integer genderCodeValueId = 4;
    final Integer defaultGenderId = 20;
    final Integer relationshipCodeValueId = 6;
    final Integer defaultRelationshipId = 21;
    final Integer maritalStatusCodeValueId = 30;
    final Integer professionCodeValueId = 32;
}
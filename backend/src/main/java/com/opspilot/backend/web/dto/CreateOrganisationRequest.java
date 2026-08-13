package com.opspilot.backend.web.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrganisationRequest (
        @NotBlank
        @Size(min = 1, max = 100)
        String name,
        @NotBlank
        @Size(min = 2, max = 100)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
        String slug) {


}


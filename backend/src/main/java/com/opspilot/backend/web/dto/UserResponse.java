package com.opspilot.backend.web.dto;

import java.util.UUID;

public record UserResponse(UUID id, String displayName) { }

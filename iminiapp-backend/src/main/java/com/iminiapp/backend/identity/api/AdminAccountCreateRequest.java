package com.iminiapp.backend.identity.api;

import java.util.List;

public record AdminAccountCreateRequest(String username, String displayName, String password, List<String> roles) {
}

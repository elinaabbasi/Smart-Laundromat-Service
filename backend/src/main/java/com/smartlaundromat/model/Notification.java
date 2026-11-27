package com.smartlaundromat.model;

import java.time.OffsetDateTime;

public record Notification(long id, String message, OffsetDateTime createdAt) {}

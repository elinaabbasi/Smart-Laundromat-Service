package com.smartlaundromat.model;

public record Machine(long id, String name, String type, String status) {
    public record Details(long id, String name, String type, String status, boolean active) {}
}

package com.jacobus.common.model;

// ユーザーエンティティ（JDK21 record）
public record User(long id, String name, int age, String gender) {}

package com.iminiapp.backend.member.api;

public final class PersonalDataModels {
    private PersonalDataModels() {}

    public record AddressRequest(String name, String phone, String detail, Boolean isDefault) {}
    public record AddressResponse(long id, String name, String phone, String detail, boolean isDefault) {}
    public record TravelerRequest(String name, String idCard, String phone) {}
    public record TravelerResponse(long id, String name, String avatarText, String idCard, String phone,
                                   String maskedIdCard, String maskedPhone) {}
}

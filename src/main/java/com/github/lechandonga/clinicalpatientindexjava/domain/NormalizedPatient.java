package com.github.lechandonga.clinicalpatientindexjava.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 解析 + 归一之后、尚未与主索引合并的患者记录。 */
public class NormalizedPatient {

    private String familyName;
    private String givenName;
    /** ISO 本地日期 yyyy-MM-dd。 */
    private String birthDate;
    private Gender gender = Gender.UNK;
    private String phone;
    private final List<NormalizedIdentifier> identifiers = new ArrayList<>();

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getGivenName() {
        return givenName;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public List<NormalizedIdentifier> getIdentifiers() {
        return identifiers;
    }

    public void addIdentifier(NormalizedIdentifier identifier) {
        if (identifier != null) {
            identifiers.add(identifier);
        }
    }

    public boolean hasSameDemographics(NormalizedPatient other) {
        return Objects.equals(birthDate, other.birthDate)
                && Objects.equals(familyName, other.familyName)
                && Objects.equals(givenName, other.givenName);
    }
}

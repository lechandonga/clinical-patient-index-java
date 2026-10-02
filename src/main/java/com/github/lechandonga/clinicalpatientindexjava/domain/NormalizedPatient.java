package com.github.lechandonga.clinicalpatientindexjava.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 归一后的患者记录。
 */
public class NormalizedPatient {

    private final Set<PatientIdentifier> identifiers = new LinkedHashSet<>();
    private String familyName;
    private String givenName;
    private String birthDate;
    private Gender gender = Gender.UNKNOWN;
    private final List<String> notes = new ArrayList<>();

    public Set<PatientIdentifier> getIdentifiers() {
        return identifiers;
    }

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

    public String fullName() {
        if (givenName == null && familyName == null) {
            return null;
        }
        return ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
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

    public List<String> getNotes() {
        return notes;
    }

    /**
     * 用于日志/审计的输入摘要，不含完整明文敏感值（姓名仅保留首字符与长度）。
     */
    public String summary() {
        String name = fullName();
        String maskedName = name == null ? "null"
                : name.charAt(0) + "*(" + name.length() + ")";
        String birthYear = birthDate == null ? "null" : birthDate.substring(0, 4);
        return "identifiers=" + identifiers.size()
                + ", name=" + maskedName
                + ", birthYear=" + birthYear
                + ", gender=" + gender
                + ", notes=" + notes;
    }
}

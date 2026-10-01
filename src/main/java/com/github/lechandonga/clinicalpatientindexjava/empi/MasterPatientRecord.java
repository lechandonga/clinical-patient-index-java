package com.github.lechandonga.clinicalpatientindexjava.empi;

import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.PatientIdentifier;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 主索引记录：一个唯一 MPI ID 对应一个真实患者。
 */
public class MasterPatientRecord {

    private final String mpiId;
    private final Set<String> identifierKeys = new LinkedHashSet<>();
    private final Set<PatientIdentifier> identifiers = new LinkedHashSet<>();
    private String familyName;
    private String givenName;
    private String birthDate;
    private Gender gender;

    public MasterPatientRecord(String mpiId) {
        this.mpiId = mpiId;
    }

    public String getMpiId() {
        return mpiId;
    }

    public Set<String> getIdentifierKeys() {
        return Collections.unmodifiableSet(identifierKeys);
    }

    public Set<PatientIdentifier> getIdentifiers() {
        return Collections.unmodifiableSet(identifiers);
    }

    void addIdentifier(PatientIdentifier identifier) {
        identifiers.add(identifier);
        identifierKeys.add(identifier.canonicalKey());
    }

    public String getFamilyName() {
        return familyName;
    }

    void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getGivenName() {
        return givenName;
    }

    void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getBirthDate() {
        return birthDate;
    }

    void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    public Gender getGender() {
        return gender;
    }

    void setGender(Gender gender) {
        this.gender = gender;
    }
}

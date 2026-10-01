package com.github.lechandonga.clinicalpatientindexjava.mpi;

import com.github.lechandonga.clinicalpatientindexjava.domain.Gender;
import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedIdentifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主索引中的唯一患者聚合根。敏感字段仅存在于进程内存，不进入任何对外视图。 */
public class MasterRecord {

    private String masterId;
    private String familyName;
    private String givenName;
    private String birthDate;
    private Gender gender = Gender.UNK;
    private String phone;
    private final Map<String, NormalizedIdentifier> identifiers = new LinkedHashMap<>();
    private final List<String> mergeWarnings = new ArrayList<>();
    private long version;

    public String getMasterId() {
        return masterId;
    }

    public void setMasterId(String masterId) {
        this.masterId = masterId;
    }

    public String getFamilyName() {
        return familyName;
    }

    public String getGivenName() {
        return givenName;
    }

    public String getBirthDate() {
        return birthDate;
    }

    public Gender getGender() {
        return gender;
    }

    public String getPhone() {
        return phone;
    }

    public Map<String, NormalizedIdentifier> getIdentifiers() {
        return identifiers;
    }

    public List<String> getMergeWarnings() {
        return mergeWarnings;
    }

    public long getVersion() {
        return version;
    }

    /** 用新来源记录补全字段；绝不覆盖已有非空值，保证不丢失。 */
    public void mergeDemographics(String newFamily, String newGiven, String newBirthDate,
                                  Gender newGender, String newPhone,
                                  List<String> conflictWarnings) {
        if (familyName == null && newFamily != null) {
            familyName = newFamily;
        } else if (newFamily != null && !newFamily.equals(familyName)) {
            conflictWarnings.add("姓氏不一致，保留主索引现值: existing=" + familyName + ", incoming=" + newFamily);
        }
        if (givenName == null && newGiven != null) {
            givenName = newGiven;
        } else if (newGiven != null && !newGiven.equals(givenName)) {
            conflictWarnings.add("名字不一致，保留主索引现值");
        }
        if (birthDate == null && newBirthDate != null) {
            birthDate = newBirthDate;
        } else if (newBirthDate != null && !newBirthDate.equals(birthDate)) {
            conflictWarnings.add("出生日期不一致，拒绝以人口学字段触发合并，保留现值: " + birthDate);
        }
        if ((gender == null || gender == Gender.UNK) && newGender != null && newGender != Gender.UNK) {
            gender = newGender;
        }
        if (phone == null && newPhone != null) {
            phone = newPhone;
        }
    }

    public void incrementVersion() {
        version++;
    }
}

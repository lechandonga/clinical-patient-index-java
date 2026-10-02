package com.github.lechandonga.clinicalpatientindexjava.review;

import com.github.lechandonga.clinicalpatientindexjava.domain.NormalizedPatient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待复核原始提交的进程内暂存区。
 *
 * <p>仅用于批准合并时重放原始归一消息；键为待办 id，值含明文人口学，
 * 因此：
 * <ul>
 *   <li>不出现在任何待办记录、对外响应、日志或审计中；</li>
 *   <li>裁决（批准/驳回）完成后立即删除；</li>
 *   <li>重启即丢失（本地内存版；真实系统应替换为受控加密存储）。</li>
 * </ul>
 */
public class ReviewStagingStore {

    private final Map<String, NormalizedPatient> payloads = new ConcurrentHashMap<>();

    public void put(String reviewId, NormalizedPatient patient) {
        payloads.put(reviewId, patient);
    }

    public NormalizedPatient take(String reviewId) {
        return payloads.remove(reviewId);
    }

    public NormalizedPatient peek(String reviewId) {
        return payloads.get(reviewId);
    }

    public int size() {
        return payloads.size();
    }
}

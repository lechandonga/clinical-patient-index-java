package com.github.lechandonga.clinicalpatientindexjava.review;

import com.github.lechandonga.clinicalpatientindexjava.domain.IngestionException;
import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex.FieldDecision;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueueFullException;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewTicket;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewQueueTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueTest.class);

    @Test
    void samePairAndCategoryPendingIsDeduplicated() {
        ReviewQueue queue = new ReviewQueue(100);
        ReviewTicket first = queue.register("MPI-0001", "MPI-0002", "PSEU-a", "PSEU-b",
                "BIRTH_DATE", "source-a");
        ReviewTicket second = queue.register("MPI-0002", "MPI-0001", "PSEU-b", "PSEU-a",
                "BIRTH_DATE", "source-b");
        log.info("[TEST INPUT] same conflicting pair submitted twice in different order");
        assertSame(first, second, "pending duplicate conflict must not pile up tickets");
        assertEquals(1, queue.countPending());
        assertEquals(2, queue.occurrenceCount(first));
        log.info("[TEST BASIS] pending={} occurrenceCount={}", queue.countPending(),
                queue.occurrenceCount(first));

        ReviewTicket otherCategory = queue.register("MPI-0001", "MPI-0002", "PSEU-a", "PSEU-b",
                "FAMILY_NAME", "source-a");
        assertEquals(2, queue.countPending(), "different conflict category is a distinct ticket");
        assertTrue(!otherCategory.getTicketId().equals(first.getTicketId()));
    }

    @Test
    void queueFullIsReportedAndNothingIsSilentlyDropped() {
        ReviewQueue queue = new ReviewQueue(2);
        queue.register("MPI-0001", "MPI-0002", "PSEU-1", "PSEU-2", "BIRTH_DATE", "a");
        queue.register("MPI-0003", "MPI-0004", "PSEU-3", "PSEU-4", "BIRTH_DATE", "a");
        log.info("[TEST INPUT] backlog limit=2 reached, third distinct conflict arrives");
        ReviewQueueFullException ex = assertThrows(ReviewQueueFullException.class,
                () -> queue.register("MPI-0005", "MPI-0006", "PSEU-5", "PSEU-6",
                        "BIRTH_DATE", "a"));
        log.info("[TEST BASIS] rejection reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(IngestionException.RejectionReason.REVIEW_QUEUE_FULL, ex.getReason());
        assertEquals(2, queue.countPending());

        queue.casDecide(queue.findByStatus(ReviewTicket.Status.PENDING).get(0).getTicketId(),
                ticket -> ticket.markRejected("reviewer", Instant.now(), "rejected in test"));
        assertEquals(1, queue.countPending(), "resolved ticket frees a slot");
    }

    @Test
    void concurrentDecisionsApplyExactlyOnceAndReturnSameResult() throws Exception {
        ReviewQueue queue = new ReviewQueue(100);
        ReviewTicket ticket = queue.register("MPI-0001", "MPI-0002", "PSEU-a", "PSEU-b",
                "BIRTH_DATE", "source-a");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    queue.casDecide(ticket.getTicketId(), t -> {
                        applied.incrementAndGet();
                        t.markApproved("reviewer", Instant.now(),
                                List.<FieldDecision>of(), "once");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[TEST INPUT] {} concurrent APPROVE decisions -> [TEST BASIS] applied={} status={}",
                threads, applied.get(), ticket.getStatus());
        assertEquals(1, applied.get(), "decision must apply exactly once");
        assertEquals(ReviewTicket.Status.APPROVED, ticket.getStatus());
        assertEquals(0, queue.countPending());
    }
}

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.engine;

import com.carrotsearch.hppc.LongArrayList;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.store.Directory;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.index.translog.TranslogDeletionPolicy;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Collections.singletonList;
import static org.elasticsearch.index.translog.TranslogDeletionPolicies.createTranslogDeletionPolicy;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.doAnswer;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CombinedDeletionPolicyTests extends ESTestCase {

    public void testKeepCommitsAfterGlobalCheckpoint() throws Exception {
        final AtomicLong globalCheckpoint = new AtomicLong();
        final int extraRetainedOps = between(0, 100);
        final SoftDeletesPolicy softDeletesPolicy = new SoftDeletesPolicy(globalCheckpoint::get, -1, extraRetainedOps);
        TranslogDeletionPolicy translogPolicy = createTranslogDeletionPolicy();
        CombinedDeletionPolicy indexPolicy = new CombinedDeletionPolicy(logger, translogPolicy, softDeletesPolicy, globalCheckpoint::get);

        final LongArrayList maxSeqNoList = new LongArrayList();
        final LongArrayList translogGenList = new LongArrayList();
        final List<IndexCommit> commitList = new ArrayList<>();
        int totalCommits = between(2, 20);
        long lastMaxSeqNo = 0;
        long lastCheckpoint = lastMaxSeqNo;
        long lastTranslogGen = 0;
        final UUID translogUUID = UUID.randomUUID();
        for (int i = 0; i < totalCommits; i++) {
            lastMaxSeqNo += between(1, 10000);
            lastCheckpoint = randomLongBetween(lastCheckpoint, lastMaxSeqNo);
            lastTranslogGen += between(1, 100);
            commitList.add(mockIndexCommit(lastCheckpoint, lastMaxSeqNo, translogUUID, lastTranslogGen));
            maxSeqNoList.add(lastMaxSeqNo);
            translogGenList.add(lastTranslogGen);
        }

        int keptIndex = randomInt(commitList.size() - 1);
        final long lower = maxSeqNoList.get(keptIndex);
        final long upper = keptIndex == commitList.size() - 1 ?
            Long.MAX_VALUE : Math.max(maxSeqNoList.get(keptIndex), maxSeqNoList.get(keptIndex + 1) - 1);
        globalCheckpoint.set(randomLongBetween(lower, upper));
        indexPolicy.onCommit(commitList);

        for (int i = 0; i < commitList.size(); i++) {
            if (i < keptIndex) {
                verify(commitList.get(i), times(1)).delete();
            } else {
                verify(commitList.get(i), never()).delete();
            }
        }
        assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(translogGenList.get(keptIndex)));
        assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(lastTranslogGen));
        assertThat(softDeletesPolicy.getMinRetainedSeqNo(), equalTo(
            Math.max(0, Math.min(getLocalCheckpoint(commitList.get(keptIndex)) + 1, globalCheckpoint.get() + 1 - extraRetainedOps))));
    }

    public void testAcquireIndexCommit() throws Exception {
        final AtomicLong globalCheckpoint = new AtomicLong();
        final int extraRetainedOps = between(0, 100);
        final SoftDeletesPolicy softDeletesPolicy = new SoftDeletesPolicy(globalCheckpoint::get, -1, extraRetainedOps);
        final UUID translogUUID = UUID.randomUUID();
        TranslogDeletionPolicy translogPolicy = createTranslogDeletionPolicy();
        CombinedDeletionPolicy indexPolicy = new CombinedDeletionPolicy(logger, translogPolicy, softDeletesPolicy, globalCheckpoint::get);
        long lastMaxSeqNo = between(1, 1000);
        long lastCheckpoint = randomLongBetween(-1, lastMaxSeqNo);
        long lastTranslogGen = between(1, 20);
        int safeIndex = 0;
        List<IndexCommit> commitList = new ArrayList<>();
        List<IndexCommit> snapshottingCommits = new ArrayList<>();
        final int iters = between(10, 100);
        for (int i = 0; i < iters; i++) {
            int newCommits = between(1, 10);
            for (int n = 0; n < newCommits; n++) {
                lastMaxSeqNo += between(1, 1000);
                lastCheckpoint = randomLongBetween(lastCheckpoint, lastMaxSeqNo);
                lastTranslogGen += between(1, 20);
                commitList.add(mockIndexCommit(lastCheckpoint, lastMaxSeqNo, translogUUID, lastTranslogGen));
            }
            // Advance the global checkpoint to between [safeIndex, safeIndex + 1)
            safeIndex = randomIntBetween(safeIndex, commitList.size() - 1);
            long lower = Math.max(globalCheckpoint.get(),
                Long.parseLong(commitList.get(safeIndex).getUserData().get(SequenceNumbers.MAX_SEQ_NO)));
            long upper = safeIndex == commitList.size() - 1 ? lastMaxSeqNo :
                Long.parseLong(commitList.get(safeIndex + 1).getUserData().get(SequenceNumbers.MAX_SEQ_NO)) - 1;
            globalCheckpoint.set(randomLongBetween(lower, upper));
            commitList.forEach(this::resetDeletion);
            indexPolicy.onCommit(commitList);
            IndexCommit safeCommit = CombinedDeletionPolicy.findSafeCommitPoint(commitList, globalCheckpoint.get());
            assertThat(softDeletesPolicy.getMinRetainedSeqNo(), equalTo(
                Math.max(0, Math.min(getLocalCheckpoint(safeCommit) + 1, globalCheckpoint.get() + 1 - extraRetainedOps))));
            // Captures and releases some commits
            int captures = between(0, 5);
            for (int n = 0; n < captures; n++) {
                boolean safe = randomBoolean();
                final IndexCommit snapshot = indexPolicy.acquireIndexCommit(safe);
                expectThrows(UnsupportedOperationException.class, snapshot::delete);
                snapshottingCommits.add(snapshot);
                if (safe) {
                    assertThat(snapshot.getUserData(), equalTo(commitList.get(safeIndex).getUserData()));
                } else {
                    assertThat(snapshot.getUserData(), equalTo(commitList.get(commitList.size() - 1).getUserData()));
                }
            }
            final List<IndexCommit> releasingSnapshots = randomSubsetOf(snapshottingCommits);
            for (IndexCommit snapshot : releasingSnapshots) {
                snapshottingCommits.remove(snapshot);
                final long pendingSnapshots = snapshottingCommits.stream().filter(snapshot::equals).count();
                final IndexCommit lastCommit = commitList.get(commitList.size() - 1);
                safeCommit = CombinedDeletionPolicy.findSafeCommitPoint(commitList, globalCheckpoint.get());
                assertThat(indexPolicy.releaseCommit(snapshot),
                    equalTo(pendingSnapshots == 0 && snapshot.equals(lastCommit) == false && snapshot.equals(safeCommit) == false));
            }
            // Snapshotting commits must not be deleted.
            snapshottingCommits.forEach(snapshot -> assertThat(snapshot.isDeleted(), equalTo(false)));
            // We don't need to retain translog for snapshotting commits.
            assertThat(translogPolicy.getMinTranslogGenerationForRecovery(),
                equalTo(Long.parseLong(commitList.get(safeIndex).getUserData().get(Translog.TRANSLOG_GENERATION_KEY))));
            assertThat(translogPolicy.getTranslogGenerationOfLastCommit(),
                equalTo(Long.parseLong(commitList.get(commitList.size() - 1).getUserData().get(Translog.TRANSLOG_GENERATION_KEY))));
            assertThat(softDeletesPolicy.getMinRetainedSeqNo(), equalTo(
                Math.max(0, Math.min(getLocalCheckpoint(commitList.get(safeIndex)) + 1, globalCheckpoint.get() + 1 - extraRetainedOps))));
        }
        snapshottingCommits.forEach(indexPolicy::releaseCommit);
        globalCheckpoint.set(randomLongBetween(lastMaxSeqNo, Long.MAX_VALUE));
        commitList.forEach(this::resetDeletion);
        indexPolicy.onCommit(commitList);
        for (int i = 0; i < commitList.size() - 1; i++) {
            assertThat(commitList.get(i).isDeleted(), equalTo(true));
        }
        assertThat(commitList.get(commitList.size() - 1).isDeleted(), equalTo(false));
        assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(lastTranslogGen));
        assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(lastTranslogGen));
        IndexCommit safeCommit = CombinedDeletionPolicy.findSafeCommitPoint(commitList, globalCheckpoint.get());
        assertThat(softDeletesPolicy.getMinRetainedSeqNo(), equalTo(
            Math.max(0, Math.min(getLocalCheckpoint(safeCommit) + 1, globalCheckpoint.get() + 1 - extraRetainedOps))));
    }

    public void testLegacyIndex() throws Exception {
        final AtomicLong globalCheckpoint = new AtomicLong();
        final SoftDeletesPolicy softDeletesPolicy = new SoftDeletesPolicy(globalCheckpoint::get, -1, 0);
        final UUID translogUUID = UUID.randomUUID();

        TranslogDeletionPolicy translogPolicy = createTranslogDeletionPolicy();
        CombinedDeletionPolicy indexPolicy = new CombinedDeletionPolicy(logger, translogPolicy, softDeletesPolicy, globalCheckpoint::get);

        long legacyTranslogGen = randomNonNegativeLong();
        IndexCommit legacyCommit = mockLegacyIndexCommit(translogUUID, legacyTranslogGen);
        assertThat(CombinedDeletionPolicy.findSafeCommitPoint(singletonList(legacyCommit), globalCheckpoint.get()),
            equalTo(legacyCommit));

        long safeTranslogGen = randomLongBetween(legacyTranslogGen, Long.MAX_VALUE);
        long maxSeqNo = randomLongBetween(1, Long.MAX_VALUE);
        final IndexCommit freshCommit = mockIndexCommit(randomLongBetween(-1, maxSeqNo), maxSeqNo, translogUUID, safeTranslogGen);

        globalCheckpoint.set(randomLongBetween(0, maxSeqNo - 1));
        indexPolicy.onCommit(Arrays.asList(legacyCommit, freshCommit));
        verify(legacyCommit, times(1)).delete(); // Do not keep the legacy commit once we have a new commit.
        verify(freshCommit, times(0)).delete();
        assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(safeTranslogGen));
        assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(safeTranslogGen));

        // Make the fresh commit safe.
        resetDeletion(legacyCommit);
        globalCheckpoint.set(randomLongBetween(maxSeqNo, Long.MAX_VALUE));
        indexPolicy.onCommit(Arrays.asList(legacyCommit, freshCommit));
        verify(legacyCommit, times(2)).delete();
        verify(freshCommit, times(0)).delete();
        assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(safeTranslogGen));
        assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(safeTranslogGen));
        assertThat(softDeletesPolicy.getMinRetainedSeqNo(), equalTo(getLocalCheckpoint(freshCommit) + 1));
    }

    public void testKeepSingleNoOpsCommits() throws Exception {
        final AtomicLong globalCheckpoint = new AtomicLong(randomLong());
        final UUID translogUUID = UUID.randomUUID();
        final SoftDeletesPolicy softDeletesPolicy = new SoftDeletesPolicy(globalCheckpoint::get, -1, 0);
        TranslogDeletionPolicy translogPolicy = createTranslogDeletionPolicy();
        CombinedDeletionPolicy indexPolicy = new CombinedDeletionPolicy(logger, translogPolicy, softDeletesPolicy, globalCheckpoint::get);

        final List<IndexCommit> commitList = new ArrayList<>();
        final int numOfNoOpsCommits = between(1, 10);
        long lastNoopTranslogGen = 0;
        for (int i = 0; i < numOfNoOpsCommits; i++) {
            lastNoopTranslogGen += between(1, 20);
            commitList.add(
                mockIndexCommit(SequenceNumbers.NO_OPS_PERFORMED, SequenceNumbers.NO_OPS_PERFORMED, translogUUID, lastNoopTranslogGen));
        }
        // Keep only one no_ops commit.
        indexPolicy.onCommit(commitList);
        assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(lastNoopTranslogGen));
        assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(lastNoopTranslogGen));
        for (int i = 0; i < numOfNoOpsCommits - 1; i++) {
            verify(commitList.get(i), times(1)).delete();
        }
        verify(commitList.get(commitList.size() - 1), never()).delete();
        // Add a some good commits.
        final int numOfGoodCommits = between(1, 5);
        long maxSeqNo = 0;
        long lastTranslogGen = lastNoopTranslogGen;
        for (int i = 0; i < numOfGoodCommits; i++) {
            maxSeqNo += between(1, 1000);
            lastTranslogGen += between(1, 20);
            commitList.add(mockIndexCommit(maxSeqNo, maxSeqNo, translogUUID, lastTranslogGen));
        }
        // If the global checkpoint is still unassigned, we should still keep one NO_OPS_PERFORMED commit.
        globalCheckpoint.set(SequenceNumbers.UNASSIGNED_SEQ_NO);
        commitList.forEach(this::resetDeletion);
        indexPolicy.onCommit(commitList);
        assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(lastNoopTranslogGen));
        assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(lastTranslogGen));
        for (int i = 0; i < numOfNoOpsCommits - 1; i++) {
            verify(commitList.get(i), times(2)).delete();
        }
        verify(commitList.get(numOfNoOpsCommits - 1), never()).delete();
        // Delete no-ops commit if global checkpoint advanced enough.
        final long lower = Long.parseLong(commitList.get(numOfNoOpsCommits).getUserData().get(SequenceNumbers.MAX_SEQ_NO));
        globalCheckpoint.set(randomLongBetween(lower, Long.MAX_VALUE));
        commitList.forEach(this::resetDeletion);
        indexPolicy.onCommit(commitList);
        assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), greaterThan(lastNoopTranslogGen));
        assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(lastTranslogGen));
        verify(commitList.get(numOfNoOpsCommits - 1), times(1)).delete();
    }

    public void testDeleteInvalidCommits() throws Exception {
        final AtomicLong globalCheckpoint = new AtomicLong(randomNonNegativeLong());
        final SoftDeletesPolicy softDeletesPolicy = new SoftDeletesPolicy(globalCheckpoint::get, -1, 0);
        TranslogDeletionPolicy translogPolicy = createTranslogDeletionPolicy();
        CombinedDeletionPolicy indexPolicy = new CombinedDeletionPolicy(logger, translogPolicy, softDeletesPolicy, globalCheckpoint::get);

        final int invalidCommits = between(1, 10);
        final List<IndexCommit> commitList = new ArrayList<>();
        for (int i = 0; i < invalidCommits; i++) {
            long maxSeqNo = randomNonNegativeLong();
            commitList.add(mockIndexCommit(randomLongBetween(-1, maxSeqNo), maxSeqNo, UUID.randomUUID(), randomNonNegativeLong()));
        }

        final UUID expectedTranslogUUID = UUID.randomUUID();
        long lastTranslogGen = 0;
        final int validCommits = between(1, 10);
        long lastMaxSeqNo = between(1, 1000);
        long lastCheckpoint = randomLongBetween(-1, lastMaxSeqNo);
        for (int i = 0; i < validCommits; i++) {
            lastTranslogGen += between(1, 1000);
            lastMaxSeqNo += between(1, 1000);
            lastCheckpoint = randomLongBetween(lastCheckpoint, lastMaxSeqNo);
            commitList.add(mockIndexCommit(lastCheckpoint, lastMaxSeqNo, expectedTranslogUUID, lastTranslogGen));
        }

        // We should never keep invalid commits regardless of the value of the global checkpoint.
        indexPolicy.onCommit(commitList);
        for (int i = 0; i < invalidCommits - 1; i++) {
            verify(commitList.get(i), times(1)).delete();
        }
        assertThat(softDeletesPolicy.getMinRetainedSeqNo(),
            equalTo(getLocalCheckpoint(CombinedDeletionPolicy.findSafeCommitPoint(commitList, globalCheckpoint.get())) + 1));
    }

    public void testCheckUnreferencedCommits() throws Exception {
        final AtomicLong globalCheckpoint = new AtomicLong(SequenceNumbers.UNASSIGNED_SEQ_NO);
        final SoftDeletesPolicy softDeletesPolicy = new SoftDeletesPolicy(globalCheckpoint::get, -1, 0);
        final UUID translogUUID = UUID.randomUUID();
        final TranslogDeletionPolicy translogPolicy = createTranslogDeletionPolicy();
        CombinedDeletionPolicy indexPolicy = new CombinedDeletionPolicy(logger, translogPolicy, softDeletesPolicy, globalCheckpoint::get);
        final List<IndexCommit> commitList = new ArrayList<>();
        int totalCommits = between(2, 20);
        long lastMaxSeqNo = between(1, 1000);
        long lastCheckpoint = randomLongBetween(-1, lastMaxSeqNo);
        long lastTranslogGen = between(1, 50);
        for (int i = 0; i < totalCommits; i++) {
            lastMaxSeqNo += between(1, 10000);
            lastTranslogGen += between(1, 100);
            lastCheckpoint = randomLongBetween(lastCheckpoint, lastMaxSeqNo);
            commitList.add(mockIndexCommit(lastCheckpoint, lastMaxSeqNo, translogUUID, lastTranslogGen));
        }
        IndexCommit safeCommit = randomFrom(commitList);
        globalCheckpoint.set(Long.parseLong(safeCommit.getUserData().get(SequenceNumbers.MAX_SEQ_NO)));
        commitList.forEach(this::resetDeletion);
        indexPolicy.onCommit(commitList);
        if (safeCommit == commitList.get(commitList.size() - 1)) {
            // Safe commit is the last commit - no need to clean up
            assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(lastTranslogGen));
            assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(lastTranslogGen));
            assertThat(indexPolicy.hasUnreferencedCommits(), equalTo(false));
        } else {
            // Advanced but not enough
            globalCheckpoint.set(randomLongBetween(globalCheckpoint.get(), lastMaxSeqNo - 1));
            assertThat(indexPolicy.hasUnreferencedCommits(), equalTo(false));
            // Advanced enough
            globalCheckpoint.set(randomLongBetween(lastMaxSeqNo, Long.MAX_VALUE));
            assertThat(indexPolicy.hasUnreferencedCommits(), equalTo(true));
            commitList.forEach(this::resetDeletion);
            indexPolicy.onCommit(commitList);
            // Safe commit is the last commit - no need to clean up
            assertThat(translogPolicy.getMinTranslogGenerationForRecovery(), equalTo(lastTranslogGen));
            assertThat(translogPolicy.getTranslogGenerationOfLastCommit(), equalTo(lastTranslogGen));
            assertThat(indexPolicy.hasUnreferencedCommits(), equalTo(false));
        }
    }

    IndexCommit mockIndexCommit(long localCheckpoint, long maxSeqNo, UUID translogUUID, long translogGen) throws IOException {
        final Map<String, String> userData = new HashMap<>();
        userData.put(SequenceNumbers.LOCAL_CHECKPOINT_KEY, Long.toString(localCheckpoint));
        userData.put(SequenceNumbers.MAX_SEQ_NO, Long.toString(maxSeqNo));
        userData.put(Translog.TRANSLOG_UUID_KEY, translogUUID.toString());
        userData.put(Translog.TRANSLOG_GENERATION_KEY, Long.toString(translogGen));
        final IndexCommit commit = mock(IndexCommit.class);
        final Directory directory = mock(Directory.class);
        when(commit.getUserData()).thenReturn(userData);
        when(commit.getDirectory()).thenReturn(directory);
        resetDeletion(commit);
        return commit;
    }

    void resetDeletion(IndexCommit commit) {
        final AtomicBoolean deleted = new AtomicBoolean();
        when(commit.isDeleted()).thenAnswer(args -> deleted.get());
        doAnswer(arg -> {
            deleted.set(true);
            return null;
        }).when(commit).delete();
    }

    private long getLocalCheckpoint(IndexCommit commit) throws IOException {
        return Long.parseLong(commit.getUserData().get(SequenceNumbers.LOCAL_CHECKPOINT_KEY));
    }

    IndexCommit mockLegacyIndexCommit(UUID translogUUID, long translogGen) throws IOException {
        final Map<String, String> userData = new HashMap<>();
        userData.put(Translog.TRANSLOG_UUID_KEY, translogUUID.toString());
        userData.put(Translog.TRANSLOG_GENERATION_KEY, Long.toString(translogGen));
        final IndexCommit commit = mock(IndexCommit.class);
        when(commit.getUserData()).thenReturn(userData);
        resetDeletion(commit);
        return commit;
    }

}

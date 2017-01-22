/*
 *  Copyright 2016 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.Flushable;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.util.concurrent.ThreadLocalRandom;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.cojen.tupl.util.Latch;

import static org.cojen.tupl.RedoOps.*;
import static org.cojen.tupl.Utils.*;

/**
 * State shared by multiple transactions. Contention is reduced by creating many context
 * instances, and distributing them among the transactions. The context vends out transaction
 * ids, supports undo log registration, and contains redo log buffers. All redo actions
 * performed by transactions flow through the context, to reduce contention on the redo writer.
 *
 * @author Brian S O'Neill
 */
/*P*/
final class TransactionContext extends Latch implements Flushable {
    private final static AtomicLongFieldUpdater<TransactionContext> cHighTxnIdUpdater =
        AtomicLongFieldUpdater.newUpdater(TransactionContext.class, "mHighTxnId");

    private final static AtomicLongFieldUpdater<TransactionContext> cConfirmedPosUpdater =
        AtomicLongFieldUpdater.newUpdater(TransactionContext.class, "mConfirmedPos");

    private static final int SPIN_LIMIT = Runtime.getRuntime().availableProcessors();

    private final int mTxnStride;

    // Access to these fields is protected by synchronizing on this context object.
    private long mInitialTxnId;
    private volatile long mHighTxnId;
    private UndoLog mTopUndoLog;
    private int mUndoLogCount;

    // Access to these fields is protected by the inherited latch.
    private final byte[] mRedoBuffer;
    private int mRedoPos;
    private long mRedoFirstTxnId;
    private long mRedoLastTxnId;
    private RedoWriter mRedoWriter;
    private boolean mRedoWriterLatched;

    // These fields capture the state of the highest confirmed commit, used by replication.
    // Access to these fields is protected by spinning on the mConfirmedPos field.
    private volatile long mConfirmedPos;
    private long mConfirmedTxnId;

    /**
     * @param txnStride transaction id increment
     */
    TransactionContext(int txnStride, int redoBufferSize) {
        if (txnStride <= 0) {
            throw new IllegalArgumentException();
        }
        mTxnStride = txnStride;
        mRedoBuffer = new byte[redoBufferSize];
    }

    synchronized void addStats(Database.Stats stats) {
        stats.txnCount += mUndoLogCount;
        stats.txnsCreated += mHighTxnId / mTxnStride;
    }

    /**
     * Set the previously vended transaction id. A call to nextTransactionId returns a higher one.
     */
    void resetTransactionId(long txnId) {
        if (txnId < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (this) {
            mInitialTxnId = txnId;
            mHighTxnId = txnId;
        }
    }

    /**
     * To be called only by transaction instances, and caller must hold commit lock. The commit
     * lock ensures that highest transaction id is persisted correctly by checkpoint.
     *
     * @return positive non-zero transaction id
     */
    long nextTransactionId() {
        long txnId = cHighTxnIdUpdater.addAndGet(this, mTxnStride);

        if (txnId <= 0) {
            // Improbably, the transaction identifier has wrapped around. Only vend positive
            // identifiers. Non-replicated transactions always have negative identifiers.
            synchronized (this) {
                if (mHighTxnId <= 0 && (txnId = mHighTxnId + mTxnStride) <= 0) {
                    txnId = mInitialTxnId % mTxnStride;
                }
                mHighTxnId = txnId;
            }
        }

        return txnId;
    }

    void acquireRedoLatch() {
        acquireExclusive();
    }

    void releaseRedoLatch() throws IOException {
        try {
            if (mRedoWriterLatched) try {
                if (mRedoFirstTxnId == 0) {
                    int length = mRedoPos;
                    if (length != 0) {
                        // Flush out the remaining messages.
                        try {
                            mRedoWriter.write(mRedoBuffer, 0, length, -1);
                        } catch (IOException e) {
                            throw rethrow(e, mRedoWriter.mCloseCause);
                        }
                        mRedoPos = 0;
                    }
                }
            } finally {
                mRedoWriter.releaseExclusive();
                mRedoWriterLatched = false;
            }
        } finally {
            releaseExclusive();
        }
    }

    /**
     * Acquire the redo latch for this context, switch to the given redo writer, and then latch
     * the redo writer. Call releaseRedoLatch to release both latches.
     */
    void fullAcquireRedoLatch(RedoWriter redo) throws IOException {
        acquireExclusive();
        try {
            if (redo != mRedoWriter) {
                switchRedo(redo);
            }
            redo.acquireExclusive();
        } catch (Throwable e) {
            releaseRedoLatch();
            throw e;
        }

        mRedoWriterLatched = true;
    }

    /**
     * Auto-commit transactional store.
     *
     * @param indexId non-zero index id
     * @param key non-null key
     * @param value value to store; null to delete
     * @return non-zero position if caller should call txnCommitSync
     */
    long redoStoreAutoCommit(RedoWriter redo, long indexId, byte[] key, byte[] value,
                             DurabilityMode mode)
        throws IOException
    {
        keyCheck(key);
        mode = redo.opWriteCheck(mode);

        acquireRedoLatch();
        try {
            if (value == null) {
                redoWriteOp(redo, OP_DELETE, indexId);
                redoWriteUnsignedVarInt(key.length);
                redoWriteBytes(key);
            } else {
                redoWriteOp(redo, OP_STORE, indexId);
                redoWriteUnsignedVarInt(key.length);
                redoWriteBytes(key);
                redoWriteUnsignedVarInt(value.length);
                redoWriteBytes(value);
            }

            return redoNonTxnTerminateCommit(redo, mode);
        } finally {
            releaseRedoLatch();
        }
    }

    /**
     * Auto-commit non-transactional store.
     *
     * @param indexId non-zero index id
     * @param key non-null key
     * @param value value to store; null to delete
     * @return non-zero position if caller should call txnCommitSync
     */
    long redoStoreNoLockAutoCommit(RedoWriter redo, long indexId, byte[] key, byte[] value,
                                   DurabilityMode mode)
        throws IOException
    {
        keyCheck(key);
        mode = redo.opWriteCheck(mode);

        acquireRedoLatch();
        try {
            if (value == null) {
                redoWriteOp(redo, OP_DELETE_NO_LOCK, indexId);
                redoWriteUnsignedVarInt(key.length);
                redoWriteBytes(key);
            } else {
                redoWriteOp(redo, OP_STORE_NO_LOCK, indexId);
                redoWriteUnsignedVarInt(key.length);
                redoWriteBytes(key);
                redoWriteUnsignedVarInt(value.length);
                redoWriteBytes(value);
            }

            return redoNonTxnTerminateCommit(redo, mode);
        } finally {
            releaseRedoLatch();
        }
    }

    /**
     * Auto-commit index rename.
     *
     * @param indexId non-zero index id
     * @param newName non-null new index name
     * @return non-zero position if caller should call txnCommitSync
     */
    long redoRenameIndexCommitFinal(RedoWriter redo, long txnId, long indexId,
                                    byte[] newName, DurabilityMode mode)
        throws IOException
    {
        mode = redo.opWriteCheck(mode);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_RENAME_INDEX, txnId);
            redoWriteLongLE(indexId);
            redoWriteUnsignedVarInt(newName.length);
            redoWriteBytes(newName);
            redoWriteTerminator(redo);
            return redoFlushCommit(mode);
        } finally {
            releaseRedoLatch();
        }
    }

    /**
     * Auto-commit index delete.
     *
     * @param indexId non-zero index id
     * @return non-zero position if caller should call txnCommitSync
     */
    long redoDeleteIndexCommitFinal(RedoWriter redo, long txnId, long indexId,
                                    DurabilityMode mode)
        throws IOException
    {
        mode = redo.opWriteCheck(mode);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_DELETE_INDEX, txnId);
            redoWriteLongLE(indexId);
            redoWriteTerminator(redo);
            return redoFlushCommit(mode);
        } finally {
            releaseRedoLatch();
        }
    }

    void redoEnter(RedoWriter redo, long txnId) throws IOException {
        redo.opWriteCheck(null);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_TXN_ENTER, txnId);
            redoWriteTerminator(redo);
        } finally {
            releaseRedoLatch();
        }
    }

    void redoRollback(RedoWriter redo, long txnId) throws IOException {
        // Because rollback can release locks, it must always be flushed like a commit.
        // Otherwise, recovery can deadlock or timeout when attempting to acquire the released
        // locks. Lock releases must always be logged before acquires.
        DurabilityMode mode = redo.opWriteCheck(DurabilityMode.NO_FLUSH);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_TXN_ROLLBACK, txnId);
            redoWriteTerminator(redo);
            redoFlushCommit(mode);
        } finally {
            releaseRedoLatch();
        }
    }

    void redoRollbackFinal(RedoWriter redo, long txnId) throws IOException {
        // See comments in redoRollback method.
        DurabilityMode mode = redo.opWriteCheck(DurabilityMode.NO_FLUSH);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_TXN_ROLLBACK_FINAL, txnId);
            redoWriteTerminator(redo);
            redoFlushCommit(mode);
        } finally {
            releaseRedoLatch();
        }
    }

    void redoCommit(RedoWriter redo, long txnId) throws IOException {
        redo.opWriteCheck(null);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_TXN_COMMIT, txnId);
            redoWriteTerminator(redo);
        } finally {
            releaseRedoLatch();
        }
    }

    /**
     * @return non-zero position if caller should call txnCommitSync
     */
    long redoCommitFinal(RedoWriter redo, long txnId, DurabilityMode mode)
        throws IOException
    {
        mode = redo.opWriteCheck(mode);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_TXN_COMMIT_FINAL, txnId);
            redoWriteTerminator(redo);
            return redoFlushCommit(mode);
        } finally {
            releaseRedoLatch();
        }
    }

    void redoStore(RedoWriter redo, byte op, long txnId, long indexId,
                   byte[] key, byte[] value)
        throws IOException
    {
        keyCheck(key);
        redo.opWriteCheck(null);

        acquireRedoLatch();
        try {
            doRedoStore(redo, op, txnId, indexId, key, value);
        } finally {
            releaseRedoLatch();
        }
    }

    /**
     * @return non-zero position if caller should call txnCommitSync
     */
    long redoStoreCommitFinal(RedoWriter redo, long txnId, long indexId,
                              byte[] key, byte[] value, DurabilityMode mode)
        throws IOException
    {
        keyCheck(key);
        mode = redo.opWriteCheck(mode);

        acquireRedoLatch();
        try {
            doRedoStore(redo, OP_TXN_STORE_COMMIT_FINAL, txnId, indexId, key, value);
            return redoFlushCommit(mode);
        } finally {
            releaseRedoLatch();
        }
    }

    private void doRedoStore(RedoWriter redo, byte op, long txnId, long indexId,
                             byte[] key, byte[] value)
        throws IOException
    {
        redoWriteTxnOp(redo, op, txnId);
        redoWriteLongLE(indexId);
        redoWriteUnsignedVarInt(key.length);
        redoWriteBytes(key);
        redoWriteUnsignedVarInt(value.length);
        redoWriteBytes(value);
        redoWriteTerminator(redo);
    }

    void redoDelete(RedoWriter redo, byte op, long txnId, long indexId, byte[] key)
        throws IOException
    {
        keyCheck(key);
        redo.opWriteCheck(null);

        acquireRedoLatch();
        try {
            doRedoDelete(redo, op, txnId, indexId, key);
        } finally {
            releaseRedoLatch();
        }
    }

    /**
     * @return non-zero position if caller should call txnCommitSync
     */
    long redoDeleteCommitFinal(RedoWriter redo, long txnId, long indexId,
                               byte[] key, DurabilityMode mode)
        throws IOException
    {
        keyCheck(key);
        mode = redo.opWriteCheck(mode);

        acquireRedoLatch();
        try {
            doRedoDelete(redo, OP_TXN_DELETE_COMMIT_FINAL, txnId, indexId, key);
            return redoFlushCommit(mode);
        } finally {
            releaseRedoLatch();
        }
    }

    private void doRedoDelete(RedoWriter redo, byte op, long txnId, long indexId, byte[] key)
        throws IOException
    {
        redoWriteTxnOp(redo, op, txnId);
        redoWriteLongLE(indexId);
        redoWriteUnsignedVarInt(key.length);
        redoWriteBytes(key);
        redoWriteTerminator(redo);
    }

    void redoCustom(RedoWriter redo, long txnId, byte[] message) throws IOException {
        if (message == null) {
            throw new NullPointerException("Message is null");
        }
        redo.opWriteCheck(null);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_TXN_CUSTOM, txnId);
            redoWriteUnsignedVarInt(message.length);
            redoWriteBytes(message);
            redoWriteTerminator(redo);
        } finally {
            releaseRedoLatch();
        }
    }

    void redoCustomLock(RedoWriter redo, long txnId, byte[] message, long indexId, byte[] key)
        throws IOException
    {
        keyCheck(key);
        if (message == null) {
            throw new NullPointerException("Message is null");
        }
        redo.opWriteCheck(null);

        acquireRedoLatch();
        try {
            redoWriteTxnOp(redo, OP_TXN_CUSTOM_LOCK, txnId);
            redoWriteLongLE(indexId);
            redoWriteUnsignedVarInt(key.length);
            redoWriteBytes(key);
            redoWriteUnsignedVarInt(message.length);
            redoWriteBytes(message);
            redoWriteTerminator(redo);
        } finally {
            releaseRedoLatch();
        }
    }

    // Caller must hold redo latch.
    void doRedoReset(RedoWriter redo) throws IOException {
        redo.opWriteCheck(null);
        redoWriteOp(redo, OP_RESET);
        redoNonTxnTerminateCommit(redo, DurabilityMode.NO_FLUSH);
        assert mRedoWriterLatched;
        redo.mLastTxnId = 0;
    }

    /**
     * @param op OP_TIMESTAMP, OP_SHUTDOWN, OP_CLOSE, or OP_END_FILE
     */
    void redoTimestamp(RedoWriter redo, byte op) throws IOException {
        acquireRedoLatch();
        try {
            doRedoTimestamp(redo, op);
        } finally {
            releaseRedoLatch();
        }
    }

    /**
     * @param op OP_TIMESTAMP, OP_SHUTDOWN, OP_CLOSE, or OP_END_FILE
     */
    // Caller must hold redo latch.
    void doRedoTimestamp(RedoWriter redo, byte op) throws IOException {
        doRedoOp(redo, op, System.currentTimeMillis());
    }

    // Caller must hold redo latch.
    void doRedoNopRandom(RedoWriter redo) throws IOException {
        doRedoOp(redo, OP_NOP_RANDOM, ThreadLocalRandom.current().nextLong());
    }

    // Caller must hold redo latch.
    private void doRedoOp(RedoWriter redo, byte op, long operand) throws IOException {
        redo.opWriteCheck(null);
        redoWriteOp(redo, op, operand);
        redoNonTxnTerminateCommit(redo, DurabilityMode.NO_FLUSH);
    }

    /**
     * Terminate and commit a non-transactional operation. Caller must hold redo latch.
     *
     * @return non-zero position if sync is required.
     */
    private long redoNonTxnTerminateCommit(RedoWriter redo, DurabilityMode mode)
        throws IOException
    {
        if (!redo.shouldWriteTerminators()) {
            // Commit the normal way.
            return redoFlushCommit(mode);
        }

        if (mRedoFirstTxnId != 0) {
            // Terminate and commit the normal way.
            redoWriteIntLE(nzHash(mRedoLastTxnId));
            return redoFlushCommit(mode);
        }

        boolean commit = mode == DurabilityMode.SYNC || mode == DurabilityMode.NO_SYNC;

        int length = mRedoPos;
        byte[] buffer = mRedoBuffer;
        redo = latchWriter();

        if (length > buffer.length - 4) {
            // Flush and make room for the terminator.
            try {
                redo.write(buffer, 0, length, -1);
            } catch (IOException e) {
                throw rethrow(e, redo.mCloseCause);
            }
            length = 0;
        }

        // Encode the terminator using the "true" last transaction id.
        Utils.encodeIntLE(buffer, length, nzHash(redo.mLastTxnId));
        length += 4;

        long commitPos;
        try {
            commitPos = redo.write(buffer, 0, length, commit ? length : -1);
        } catch (IOException e) {
            throw rethrow(e, redo.mCloseCause);
        }

        mRedoPos = 0;

        return mode == DurabilityMode.SYNC ? commitPos : 0;
    }

    // Caller must hold redo latch.
    private void redoWriteTerminator(RedoWriter redo) throws IOException {
        if (redo.shouldWriteTerminators()) {
            redoWriteIntLE(nzHash(mRedoLastTxnId));
        }
    }

    // Caller must hold redo latch.
    private void redoWriteIntLE(int v) throws IOException {
        byte[] buffer = mRedoBuffer;
        int pos = mRedoPos;
        if (pos > buffer.length - 4) {
            redoFlush(false);
            pos = 0;
        }
        Utils.encodeIntLE(buffer, pos, v);
        mRedoPos = pos + 4;
    }

    // Caller must hold redo latch.
    private void redoWriteLongLE(long v) throws IOException {
        byte[] buffer = mRedoBuffer;
        int pos = mRedoPos;
        if (pos > buffer.length - 8) {
            redoFlush(false);
            pos = 0;
        }
        Utils.encodeLongLE(buffer, pos, v);
        mRedoPos = pos + 8;
    }

    // Caller must hold redo latch.
    private void redoWriteUnsignedVarInt(int v) throws IOException {
        byte[] buffer = mRedoBuffer;
        int pos = mRedoPos;
        if (pos > buffer.length - 5) {
            redoFlush(false);
            pos = 0;
        }
        mRedoPos = Utils.encodeUnsignedVarInt(buffer, pos, v);
    }

    // Caller must hold redo latch.
    private void redoWriteBytes(byte[] bytes) throws IOException {
        redoWriteBytes(bytes, 0, bytes.length);
    }

    // Caller must hold redo latch.
    private void redoWriteBytes(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) {
            return;
        }

        byte[] buffer = mRedoBuffer;
        int avail = buffer.length - mRedoPos;

        if (avail >= length) {
            if (mRedoPos == 0 && avail == length) {
                RedoWriter redo = latchWriter();
                try {
                    redo.write(bytes, offset, length, -1);
                } catch (IOException e) {
                    throw rethrow(e, redo.mCloseCause);
                }
            } else {
                System.arraycopy(bytes, offset, buffer, mRedoPos, length);
                mRedoPos += length;
            }
        } else {
            // Fill remainder of buffer and flush it.
            System.arraycopy(bytes, offset, buffer, mRedoPos, avail);
            mRedoPos = buffer.length;

            // Latches writer as a side-effect.
            redoFlush(false);

            offset += avail;
            length -= avail;

            if (length >= buffer.length) {
                try {
                    mRedoWriter.write(bytes, offset, length, -1);
                } catch (IOException e) {
                    throw rethrow(e, mRedoWriter.mCloseCause);
                }
            } else {
                System.arraycopy(bytes, offset, buffer, 0, length);
                mRedoPos = length;
            }
        }
    }

    /**
     * Write a non-transactional operation. Caller must hold redo latch and always flush the
     * operation. Flushing ensures that transactional operations that follow can encode
     * transaction id deltas correctly.
     */
    private void redoWriteOp(RedoWriter redo, byte op) throws IOException {
        mRedoPos = doRedoWriteOp(redo, op, 1); // 1 for op
    }

    /**
     * Write a non-transactional operation. Caller must hold redo latch and always flush the
     * operation. Flushing ensures that transactional operations that follow can encode
     * transaction id deltas correctly.
     */
    private void redoWriteOp(RedoWriter redo, byte op, long operand) throws IOException {
        int pos = doRedoWriteOp(redo, op, 1 + 8); // 1 for op, 8 for operand
        Utils.encodeLongLE(mRedoBuffer, pos, operand);
        mRedoPos = pos + 8;
    }

    // Caller must hold redo latch.
    private int doRedoWriteOp(RedoWriter redo, byte op, int len) throws IOException {
        if (redo != mRedoWriter) {
            switchRedo(redo);
        }

        byte[] buffer = mRedoBuffer;
        int pos = mRedoPos;

        if (pos > buffer.length - len) {
            redoFlush(false);
            pos = 0;
        }

        buffer[pos] = op;
        return pos + 1;
    }

    // Caller must hold redo latch.
    private void redoWriteTxnOp(RedoWriter redo, byte op, long txnId) throws IOException {
        if (redo != mRedoWriter) {
            switchRedo(redo);
        }

        byte[] buffer = mRedoBuffer;
        int pos = mRedoPos;

        prepare: {
            if (pos > buffer.length - (1 + 9)) { // 1 for op, up to 9 for txn delta
                redoFlush(false);
                pos = 0;
            } else if (pos != 0) {
                mRedoPos = Utils.encodeSignedVarLong(buffer, pos + 1, txnId - mRedoLastTxnId);
                break prepare;
            }
            mRedoFirstTxnId = txnId;
            mRedoPos = 1 + 9; // 1 for op, and reserve 9 for txn delta
        }

        buffer[pos] = op;
        mRedoLastTxnId = txnId;
    }

    /**
     * Flush redo buffer to the current RedoWriter.
     */
    @Override
    public void flush() throws IOException {
        acquireRedoLatch();
        try {
            doFlush();
        } finally {
            releaseRedoLatch();
        }
    }

    // Caller must hold redo latch.
    void doFlush() throws IOException {
        redoFlush(false);
    }

    // Caller must hold redo latch.
    private void switchRedo(RedoWriter redo) throws IOException {
        try {
            redoFlush(false);
        } catch (UnmodifiableReplicaException e) {
            // Terminal state, so safe to discard everything.
            mRedoPos = 0;
            mRedoFirstTxnId = 0;
        } finally {
            if (mRedoWriterLatched) {
                mRedoWriter.releaseExclusive();
            }
        }

        mRedoWriter = redo;
    }

    /**
     * Caller must hold redo latch and ensure that mRedoWriter is set.
     *
     * @return non-zero position if sync is required.
     */
    private long redoFlushCommit(DurabilityMode mode) throws IOException {
        if (mode == DurabilityMode.SYNC) {
            return redoFlush(true);
        } else {
            redoFlush(mode == DurabilityMode.NO_SYNC); // ignore commit for NO_FLUSH, etc.
            return 0;
        }
    }

    /**
     * Caller must hold redo latch and ensure that mRedoWriter is set.
     *
     * @param commit true if last encoded operation should be treated as a transaction commit
     * and be flushed immediately
     * @return highest log position afterwards
     */
    private long redoFlush(boolean commit) throws IOException {
        int length = mRedoPos;
        if (length == 0) {
            return 0;
        }

        byte[] buffer = mRedoBuffer;
        int offset = 0;
        RedoWriter redo = latchWriter();

        final long redoWriterLastTxnId = redo.mLastTxnId;

        if (mRedoFirstTxnId != 0) {
            // Encode the first transaction delta and shift the opcode.
            long delta = convertSignedVarLong(mRedoFirstTxnId - redoWriterLastTxnId);
            int varLen = calcUnsignedVarLongLength(delta);
            offset = (1 + 9) - varLen;
            encodeUnsignedVarLong(buffer, offset, delta);
            buffer[--offset] = buffer[0];
            length -= offset;
            // Must always set before write is called, so that it can see the update.
            redo.mLastTxnId = mRedoLastTxnId;
        }

        long commitPos;
        try {
            try {
                commitPos = redo.write(buffer, offset, length, commit ? length : -1);
            } catch (IOException e) {
                throw rethrow(e, redo.mCloseCause);
            }
        } catch (Throwable e) {
            // Rollback.
            redo.mLastTxnId = redoWriterLastTxnId;
            throw e;
        }

        mRedoPos = 0;
        mRedoFirstTxnId = 0;

        return commitPos;
    }

    // Caller must hold redo latch.
    private RedoWriter latchWriter() {
        RedoWriter redo = mRedoWriter;
        if (!mRedoWriterLatched) {
            redo.acquireExclusive();
            mRedoWriterLatched = true;
        }
        return redo;
    }

    void confirmed(long commitPos, long txnId) {
        if (commitPos == -1) {
            throw new IllegalArgumentException();
        }

        long confirmedPos = mConfirmedPos;

        check: {
            if (confirmedPos != -1) {
                if (commitPos <= confirmedPos) {
                    return;
                }
                if (cConfirmedPosUpdater.compareAndSet(this, confirmedPos, -1)) {
                    break check;
                }
            }

            confirmedPos = latchConfirmed();

            if (commitPos <= confirmedPos) {
                // Release the latch.
                mConfirmedPos = confirmedPos;
                return;
            }
        }

        mConfirmedTxnId = txnId;
        // Set this last, because it releases the latch.
        mConfirmedPos = commitPos;
    }

    /**
     * Returns the context with the higher confirmed position.
     */
    TransactionContext higherConfirmed(TransactionContext other) {
        return mConfirmedPos >= other.mConfirmedPos ? this : other;
    }

    /**
     * Copy the confirmed position and transaction id to the returned array.
     */
    long[] copyConfirmed() {
        long[] result = new long[2];
        long confirmedPos = latchConfirmed();
        result[0] = confirmedPos;
        result[1] = mConfirmedTxnId;
        // Release the latch.
        mConfirmedPos = confirmedPos;
        return result;
    }

    /**
     * @return value of mConfirmedPos to set to release the latch
     */
    private long latchConfirmed() {
        int trials = 0;
        while (true) {
            long confirmedPos = mConfirmedPos;
            if (confirmedPos != -1 && cConfirmedPosUpdater.compareAndSet(this, confirmedPos, -1)) {
                return confirmedPos;
            }
            trials++;
            if (trials >= SPIN_LIMIT) {
                Thread.yield();
                trials = 0;
            }
        }
    }

    /**
     * Caller must hold db commit lock.
     */
    synchronized void register(UndoLog undo) {
        UndoLog top = mTopUndoLog;
        if (top != null) {
            undo.mPrev = top;
            top.mNext = undo;
        }
        mTopUndoLog = undo;
        mUndoLogCount++;
    }

    /**
     * Should only be called after all log entries have been truncated or rolled back. Caller
     * does not need to hold db commit lock.
     */
    synchronized void unregister(UndoLog log) {
        UndoLog prev = log.mPrev;
        UndoLog next = log.mNext;
        if (prev != null) {
            prev.mNext = next;
            log.mPrev = null;
        }
        if (next != null) {
            next.mPrev = prev;
            log.mNext = null;
        } else if (log == mTopUndoLog) {
            mTopUndoLog = prev;
        }
        mUndoLogCount--;
    }

    /**
     * Returns the current transaction id or the given one, depending on which is higher.
     */
    long higherTransactionId(long txnId) {
        return Math.max(mHighTxnId, txnId);
    }

    /**
     * Caller must synchronize on this context object.
     */
    boolean hasUndoLogs() {
        return mTopUndoLog != null;
    }

    /**
     * Write any undo log references to the master undo log. Caller must hold db commit lock
     * and synchronize on this context object.
     *
     * @param workspace temporary buffer, allocated on demand
     * @return new or original workspace instance
     */
    byte[] writeToMaster(UndoLog master, byte[] workspace) throws IOException {
        for (UndoLog log = mTopUndoLog; log != null; log = log.mPrev) {
            workspace = log.writeToMaster(master, workspace);
        }
        return workspace;
    }

    /**
     * Deletes any UndoLog instances, as part of database close sequence. Caller must hold
     * exclusive db commit lock.
     */
    synchronized void deleteUndoLogs() {
        for (UndoLog log = mTopUndoLog; log != null; log = log.mPrev) {
            log.delete();
        }
        mTopUndoLog = null;
    }
}

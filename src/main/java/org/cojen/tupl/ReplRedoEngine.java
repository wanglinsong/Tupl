/*
 *  Copyright 2012-2017 Cojen.org
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

import java.io.IOException;

import java.lang.ref.SoftReference;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.cojen.tupl.ext.ReplicationManager;
import org.cojen.tupl.ext.TransactionHandler;

import org.cojen.tupl.util.Latch;
import org.cojen.tupl.util.LatchCondition;
import org.cojen.tupl.util.Worker;
import org.cojen.tupl.util.WorkerGroup;

import static org.cojen.tupl.Utils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see ReplRedoEngine
 */
/*P*/
class ReplRedoEngine implements RedoVisitor, ThreadFactory {
    private static final int MAX_QUEUE_SIZE = 100;
    private static final int MAX_KEEP_ALIVE_MILLIS = 60_000;
    private static final long INFINITE_TIMEOUT = -1L;

    // Hash spreader. Based on rounded value of 2 ** 63 * (sqrt(5) - 1) equivalent 
    // to unsigned 11400714819323198485.
    private static final long HASH_SPREAD = -7046029254386353131L;

    final ReplicationManager mManager;
    final LocalDatabase mDatabase;

    final ReplRedoController mController;

    private final WorkerGroup mWorkerGroup;

    private final Latch mDecodeLatch;
    private final LatchCondition mDecodeCondition;
    private static final int
        DECODE_DISABLED = 0, DECODE_DO_SUSPEND = 1,
        DECODE_SUSPENDED = 2, DECODE_RUNNING = 3;
    private int mDecodeState;

    private final TxnTable mTransactions;

    // Maintain soft references to indexes, allowing them to get closed if not
    // used for awhile. Without the soft references, Database maintains only
    // weak references to indexes. They'd get closed too soon.
    private final LHashTable.Obj<SoftReference<Index>> mIndexes;

    private volatile ReplRedoDecoder mDecoder;

    // Updated by lone decoder thread with shared op lock held. Values can be read with op lock
    // exclusively held, when engine is suspended.
    long mDecodePosition;
    long mDecodeTransactionId;

    /**
     * @param manager already started
     * @param txns recovered transactions; can be null; cleared as a side-effect
     */
    ReplRedoEngine(ReplicationManager manager, int maxThreads,
                   LocalDatabase db, LHashTable.Obj<LocalTransaction> txns)
        throws IOException
    {
        if (maxThreads <= 0) {
            int procCount = Runtime.getRuntime().availableProcessors();
            maxThreads = maxThreads == 0 ? procCount : (-maxThreads * procCount);
            if (maxThreads <= 0) {
                // Overflowed.
                maxThreads = Integer.MAX_VALUE;
            }
        }

        mManager = manager;
        mDatabase = db;

        mController = new ReplRedoController(this);

        mDecodeLatch = new Latch();
        mDecodeCondition = new LatchCondition();

        if (maxThreads <= 1) {
            // Just use the decoder thread and don't hand off tasks to worker threads.
            mWorkerGroup = null;
        } else {
            mWorkerGroup = WorkerGroup.make(maxThreads - 1, // one thread will be the decoder
                                            MAX_QUEUE_SIZE,
                                            MAX_KEEP_ALIVE_MILLIS, TimeUnit.MILLISECONDS,
                                            this); // ThreadFactory
        }

        final TxnTable txnTable;
        if (txns == null) {
            txnTable = new TxnTable(16);
        } else {
            txnTable = new TxnTable(txns.size());

            txns.traverse(te -> {
                long scrambledTxnId = mix(te.key);
                LocalTransaction txn = te.value;
                if (!txn.recoveryCleanup(false)) {
                    txnTable.insert(scrambledTxnId).init(txn);
                }
                // Delete entry.
                return true;
            });
        }

        mTransactions = txnTable;

        mIndexes = new LHashTable.Obj<>(16);

        // Initialize the decode position early.
        mDecodeLatch.acquireExclusive();
        mDecodePosition = manager.readPosition();
        mDecodeLatch.releaseExclusive();
    }

    public RedoWriter initWriter(long redoNum) {
        mController.initCheckpointNumber(redoNum);
        return mController;
    }

    public void startReceiving(long initialPosition, long initialTxnId) {
        try {
            mDecodeLatch.acquireExclusive();
            try {
                if (mDecoder == null) {
                    mDecoder = new ReplRedoDecoder(mManager, initialPosition, initialTxnId);
                    newThread(this::decode).start();
                }
            } finally {
                mDecodeLatch.releaseExclusive();
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ReplicationReceiver-" + Long.toUnsignedString(t.getId()));
        return t;
    }

    @Override
    public boolean reset() throws IOException {
        CountDownLatch cd = new CountDownLatch(mTransactions.size());

        // Reset and discard all transactions.
        mTransactions.traverse(te -> {
            runTask(te, new Worker.Task() {
                public void run() {
                    try {
                        te.mTxn.recoveryCleanup(true);
                        cd.countDown();
                    } catch (Throwable e) {
                        fail(e);
                    }
                }
            });
            return true;
        });

        try {
            cd.await();
        } catch (InterruptedException e) {
            fail(e);
        }

        // Although it might seem like a good time to clean out any lingering trash, concurrent
        // transactions are still active and need the trash to rollback properly.
        //mDatabase.emptyAllFragmentedTrash(false);

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean timestamp(long timestamp) throws IOException {
        return false;
    }

    @Override
    public boolean shutdown(long timestamp) throws IOException {
        return false;
    }

    @Override
    public boolean close(long timestamp) throws IOException {
        return false;
    }

    @Override
    public boolean endFile(long timestamp) throws IOException {
        return false;
    }

    @Override
    public boolean store(long indexId, byte[] key, byte[] value) throws IOException {
        // Must acquire lock before task is enqueued.
        Locker locker = mDatabase.mLockManager.localLocker();
        locker.lockUpgradable(indexId, key, INFINITE_TIMEOUT);

        runTaskAnywhere(new Worker.Task() {
            public void run() {
                Index ix;
                try {
                    ix = getIndex(indexId);

                    // Full exclusive lock is required.
                    locker.lockExclusive(indexId, key, INFINITE_TIMEOUT);

                    while (true) {
                        try {
                            ix = getIndex(indexId);
                            ix.store(Transaction.BOGUS, key, value);
                            break;
                        } catch (ClosedIndexException e) {
                            // User closed the shared index reference, so re-open it.
                            ix = openIndex(indexId, null);
                        }
                    }
                } catch (Throwable e) {
                    fail(e);
                    return;
                }

                notifyStore(ix, key, value);
            }
        });

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean storeNoLock(long indexId, byte[] key, byte[] value) throws IOException {
        // A no-lock change is created when using the UNSAFE lock mode. If the application has
        // performed its own locking, consistency can be preserved by locking the index
        // entry. Otherwise, the outcome is unpredictable.

        return store(indexId, key, value);
    }

    @Override
    public boolean renameIndex(long txnId, long indexId, byte[] newName) throws IOException {
        Index ix = getIndex(indexId);
        byte[] oldName = null;

        if (ix != null) {
            oldName = ix.getName();
            try {
                mDatabase.renameIndex(ix, newName, txnId);
            } catch (RuntimeException e) {
                EventListener listener = mDatabase.eventListener();
                if (listener != null) {
                    listener.notify(EventType.REPLICATION_WARNING,
                                    "Unable to rename index: %1$s", rootCause(e));
                    // Disable notification.
                    ix = null;
                }
            }
        }

        if (ix != null) {
            try {
                mManager.notifyRename(ix, oldName, newName.clone());
            } catch (Throwable e) {
                uncaught(e);
            }
        }

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean deleteIndex(long txnId, long indexId) throws IOException {
        TxnEntry te = getTxnEntry(txnId);

        runTask(te, new Worker.Task() {
            public void run() {
                try {
                    LocalTransaction txn = te.mTxn;

                    // Open the index with the transaction to prevent deadlock
                    // when the instance is not cached and has to be loaded.
                    Index ix = getIndex(txn, indexId);
                    mIndexes.remove(indexId);

                    try {
                        txn.commit();
                    } finally {
                        txn.exit();
                    }

                    if (ix != null) {
                        ix.close();
                        try {
                            mManager.notifyDrop(ix);
                        } catch (Throwable e) {
                            uncaught(e);
                        }
                    }

                    Runnable task = mDatabase.replicaDeleteTree(indexId);

                    if (task != null) {
                        try {
                            // Allow index deletion to run concurrently. If multiple deletes
                            // are received concurrently, then the application is likely doing
                            // concurrent deletes.
                            Thread deletion = new Thread
                                (task, "IndexDeletion-" +
                                 (ix == null ? indexId : ix.getNameString()));
                            deletion.setDaemon(true);
                            deletion.start();
                        } catch (Throwable e) {
                            EventListener listener = mDatabase.eventListener();
                            if (listener != null) {
                                listener.notify(EventType.REPLICATION_WARNING,
                                                "Unable to immediately delete index: %1$s",
                                                rootCause(e));
                            }
                            // Index will get fully deleted when database is re-opened.
                        }
                    }
                } catch (Throwable e) {
                    fail(e);
                }
            }
        });

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnEnter(long txnId) throws IOException {
        long scrambledTxnId = mix(txnId);
        TxnEntry te = mTransactions.get(scrambledTxnId);

        if (te == null) {
            // Create a new transaction.

            mTransactions.insert(scrambledTxnId).init(newTransaction(txnId));
        } else {
            // Enter nested scope of an existing transaction.

            runTask(te, new Worker.Task() {
                public void run() {
                    try {
                        te.mTxn.enter();
                    } catch (Throwable e) {
                        fail(e);
                    }
                }
            });
        }

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnRollback(long txnId) throws IOException {
        TxnEntry te = getTxnEntry(txnId);

        runTask(te, new Worker.Task() {
            public void run() {
                try {
                    te.mTxn.exit();
                } catch (Throwable e) {
                    fail(e);
                }
            }
        });

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnRollbackFinal(long txnId) throws IOException {
        TxnEntry te = removeTxnEntry(txnId);

        if (te != null) {
            runTask(te, new Worker.Task() {
                public void run() {
                    try {
                        te.mTxn.reset();
                    } catch (Throwable e) {
                        fail(e);
                    }
                }
            });
        }

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnCommit(long txnId) throws IOException {
        TxnEntry te = getTxnEntry(txnId);

        runTask(te, new Worker.Task() {
            public void run() {
                try {
                    te.mTxn.commit();
                } catch (Throwable e) {
                    fail(e);
                }
            }
        });

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnCommitFinal(long txnId) throws IOException {
        TxnEntry te = removeTxnEntry(txnId);

        if (te != null) {
            runTask(te, new Worker.Task() {
                public void run() {
                    try {
                        te.mTxn.commitAll();
                    } catch (Throwable e) {
                        fail(e);
                    }
                }
            });
        }

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnEnterStore(long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        long scrambledTxnId = mix(txnId);
        TxnEntry te = mTransactions.get(scrambledTxnId);

        LocalTransaction txn;
        if (te == null) {
            // Create a new transaction.
            txn = newTransaction(txnId);
            mTransactions.insert(scrambledTxnId).init(txn);
        } else {
            // Enter nested scope of an existing transaction.
            txn = te.mTxn;
        }

        // Must acquire lock before task is enqueued.
        txn.lockUpgradable(indexId, key, INFINITE_TIMEOUT);

        Worker.Task task = new Worker.Task() {
            public void run() {
                Index ix;
                try {
                    if (te != null) {
                        txn.enter();
                    }

                    ix = getIndex(indexId);

                    while (true) {
                        try {
                            ix.store(txn, key, value);
                            break;
                        } catch (ClosedIndexException e) {
                            // User closed the shared index reference, so re-open it.
                            ix = openIndex(indexId, null);
                        }
                    }
                } catch (Throwable e) {
                    fail(e);
                    return;
                }

                notifyStore(ix, key, value);
            }
        };

        if (te == null) {
            runTaskAnywhere(task);
        } else {
            runTask(te, task);
        }

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnStore(long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        TxnEntry te = getTxnEntry(txnId);
        LocalTransaction txn = te.mTxn;

        // Must acquire lock before task is enqueued.
        txn.lockUpgradable(indexId, key, INFINITE_TIMEOUT);

        runTask(te, new Worker.Task() {
            public void run() {
                Index ix;
                try {
                    ix = getIndex(indexId);

                    while (true) {
                        try {
                            ix.store(txn, key, value);
                            break;
                        } catch (ClosedIndexException e) {
                            // User closed the shared index reference, so re-open it.
                            ix = openIndex(indexId, null);
                        }
                    }
                } catch (Throwable e) {
                    fail(e);
                    return;
                }

                notifyStore(ix, key, value);
            }
        });

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnStoreCommit(long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        TxnEntry te = getTxnEntry(txnId);

        LocalTransaction txn;
        if (te == null) {
            // Create the transaction, but don't store it in the transaction table.
            txn = newTransaction(txnId);
        } else {
            txn = te.mTxn;
        }

        // Must acquire lock before task is enqueued.
        txn.lockUpgradable(indexId, key, INFINITE_TIMEOUT);

        Worker.Task task = new Worker.Task() {
            public void run() {
                Index ix;
                try {
                    ix = getIndex(indexId);

                    while (true) {
                        try {
                            ix.store(txn, key, value);
                            break;
                        } catch (ClosedIndexException e) {
                            // User closed the shared index reference, so re-open it.
                            ix = openIndex(indexId, null);
                        }
                    }

                    txn.commit();
                } catch (Throwable e) {
                    fail(e);
                    return;
                }

                notifyStore(ix, key, value);
            }
        };

        if (te == null) {
            runTaskAnywhere(task);
        } else {
            runTask(te, task);
        }

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnStoreCommitFinal(long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        TxnEntry te = removeTxnEntry(txnId);

        LocalTransaction txn;
        if (te == null) {
            // Create the transaction, but don't store it in the transaction table.
            txn = newTransaction(txnId);
        } else {
            txn = te.mTxn;
        }

        // Must acquire lock before task is enqueued.
        txn.lockUpgradable(indexId, key, INFINITE_TIMEOUT);

        Worker.Task task = new Worker.Task() {
            public void run() {
                Index ix;
                try {
                    ix = getIndex(indexId);

                    while (true) {
                        try {
                            ix.store(txn, key, value);
                            break;
                        } catch (ClosedIndexException e) {
                            // User closed the shared index reference, so re-open it.
                            ix = openIndex(indexId, null);
                        }
                    }

                    txn.commitAll();
                } catch (Throwable e) {
                    fail(e);
                    return;
                }

                notifyStore(ix, key, value);
            }
        };

        if (te == null) {
            runTaskAnywhere(task);
        } else {
            runTask(te, task);
        }

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnLockShared(long txnId, long indexId, byte[] key) throws IOException {
        getTxnEntry(txnId).mTxn.lockShared(indexId, key, INFINITE_TIMEOUT);
        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnLockUpgradable(long txnId, long indexId, byte[] key) throws IOException {
        getTxnEntry(txnId).mTxn.lockUpgradable(indexId, key, INFINITE_TIMEOUT);
        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnLockExclusive(long txnId, long indexId, byte[] key) throws IOException {
        getTxnEntry(txnId).mTxn.lockExclusive(indexId, key, INFINITE_TIMEOUT);
        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnCustom(long txnId, byte[] message) throws IOException {
        TransactionHandler handler = customHandler();
        TxnEntry te = getTxnEntry(txnId);
        LocalTransaction txn = te.mTxn;

        runTask(te, new Worker.Task() {
            public void run() {
                try {
                    handler.redo(mDatabase, txn, message);
                } catch (Throwable e) {
                    fail(e);
                    return;
                }
            }
        });

        // Return control back to the decode method.
        return false;
    }

    @Override
    public boolean txnCustomLock(long txnId, byte[] message, long indexId, byte[] key)
        throws IOException
    {
        TransactionHandler handler = customHandler();
        TxnEntry te = getTxnEntry(txnId);
        LocalTransaction txn = te.mTxn;

        // Must acquire lock before task is enqueued.
        txn.lockUpgradable(indexId, key, INFINITE_TIMEOUT);

        runTask(te, new Worker.Task() {
            public void run() {
                try {
                    // Full exclusive lock is required.
                    txn.lockExclusive(indexId, key, INFINITE_TIMEOUT);

                    handler.redo(mDatabase, txn, message, indexId, key);
                } catch (Throwable e) {
                    fail(e);
                    return;
                }
            }
        });

        // Return control back to the decode method.
        return false;
    }

    /**
     * Prevents new operations from starting and waits for in-flight operations to complete.
     */
    void suspend() {
        // Prevent new operations from being decoded.
        mDecodeLatch.acquireExclusive();
        try {
            if (mDecodeState == DECODE_RUNNING) {
                mDecodeState = DECODE_DO_SUSPEND;
                while (mDecodeState == DECODE_DO_SUSPEND) {
                    mDecodeCondition.await(mDecodeLatch, -1, 0);
                }
            }
        } catch (Throwable e) {
            mDecodeLatch.releaseExclusive();
            throw e;
        }

        // Wait for work to complete.
        if (mWorkerGroup != null) {
            mWorkerGroup.join(false);
        }
    }

    void resume() {
        if (mDecodeState == DECODE_SUSPENDED) {
            mDecodeState = DECODE_RUNNING;
            mDecodeCondition.signalAll();
        }
        mDecodeLatch.releaseExclusive();
    }

    /**
     * @return TxnEntry with scrambled transaction id
     */
    private TxnEntry getTxnEntry(long txnId) throws IOException {
        long scrambledTxnId = mix(txnId);
        TxnEntry te = mTransactions.get(scrambledTxnId);

        if (te == null) {
            // Create transaction on demand if necessary. Startup transaction recovery only
            // applies to those which generated undo log entries.
            LocalTransaction txn = newTransaction(txnId);
            te = mTransactions.insert(scrambledTxnId);
            te.init(txn);
        }

        return te;
    }

    /**
     * Only to be called from decode thread. Selects a worker for the first task against the
     * given transaction, and then uses the same worker for subsequent tasks.
     */
    private void runTask(TxnEntry te, Worker.Task task) {
        Worker w = te.mWorker;
        if (w == null) {
            te.mWorker = runTaskAnywhere(task);
        } else {
            w.enqueue(task);
        }
    }

    private Worker runTaskAnywhere(Worker.Task task) {
        if (mWorkerGroup == null) {
            try {
                task.run();
            } catch (Throwable e) {
                uncaught(e);
            }
            return null;
        } else {
            return mWorkerGroup.enqueue(task);
        }
    }

    private LocalTransaction newTransaction(long txnId) {
        LocalTransaction txn = new LocalTransaction
            (mDatabase, txnId, LockMode.UPGRADABLE_READ, INFINITE_TIMEOUT);
        txn.attach("replication");
        return txn;
    }

    /**
     * @return TxnEntry with scrambled transaction id; null if not found
     */
    private TxnEntry removeTxnEntry(long txnId) throws IOException {
        long scrambledTxnId = mix(txnId);
        return mTransactions.remove(scrambledTxnId);
    }

    /**
     * Returns the index from the local cache, opening it if necessary.
     *
     * @return null if not found
     */
    private Index getIndex(Transaction txn, long indexId) throws IOException {
        LHashTable.ObjEntry<SoftReference<Index>> entry = mIndexes.get(indexId);
        if (entry != null) {
            Index ix = entry.value.get();
            if (ix != null) {
                return ix;
            }
        }
        return openIndex(txn, indexId, entry);
    }


    /**
     * Returns the index from the local cache, opening it if necessary.
     *
     * @return null if not found
     */
    private Index getIndex(long indexId) throws IOException {
        return getIndex(null, indexId);
    }

    /**
     * Opens the index and puts it into the local cache, replacing the existing entry.
     *
     * @return null if not found
     */
    private Index openIndex(Transaction txn, long indexId,
                            LHashTable.ObjEntry<SoftReference<Index>> entry)
        throws IOException
    {
        Index ix = mDatabase.anyIndexById(txn, indexId);
        if (ix == null) {
            return null;
        }

        SoftReference<Index> ref = new SoftReference<>(ix);
        if (entry == null) {
            mIndexes.insert(indexId).value = ref;
        } else {
            entry.value = ref;
        }

        if (entry != null) {
            // Remove entries for all other cleared references, freeing up memory.
            mIndexes.traverse(e -> e.value.get() == null);
        }

        return ix;
    }

    /**
     * Opens the index and puts it into the local cache, replacing the existing entry.
     *
     * @return null if not found
     */
    private Index openIndex(long indexId, LHashTable.ObjEntry<SoftReference<Index>> entry)
        throws IOException
    {
        return openIndex(null, indexId, entry);
    }

    private void decode() {
        try {
            final ReplRedoDecoder decoder = mDecoder;

            while (true) {
                mDecodeLatch.acquireExclusive();
                try {
                    if (mDecodeState != DECODE_RUNNING) {
                        if (mDecodeState == DECODE_DISABLED) {
                            mDecodeState = DECODE_RUNNING;
                        } else {
                            mDecodeState = DECODE_SUSPENDED;
                            mDecodeCondition.signalAll();
                            while (mDecodeState != DECODE_RUNNING) {
                                mDecodeCondition.await(mDecodeLatch, -1, 0);
                            }
                        }
                    }

                    // Capture the position for the next operation. Also capture the last
                    // transaction id, before a delta is applied.
                    mDecodePosition = decoder.in().mPos;
                    mDecodeTransactionId = decoder.mTxnId;
                } finally {
                    mDecodeLatch.releaseExclusive();
                }

                if (decoder.run(this)) {
                    // End of stream reached, and so local instance is now the leader.
                    break;
                }
            }

            // Wait for work to complete.
            if (mWorkerGroup != null) {
                mWorkerGroup.join(false);
            }

            // Rollback any lingering transactions.
            reset();
        } catch (Throwable e) {
            fail(e);
            return;
        } finally {
            mDecodeLatch.acquireExclusive();
            mDecodeState = DECODE_DISABLED;
            mDecodeCondition.signalAll();
            mDecodeLatch.releaseExclusive();
        }

        mDecoder = null;

        try {
            mController.leaderNotify();
        } catch (UnmodifiableReplicaException e) {
            // Should already be receiving again due to this exception.
        } catch (Throwable e) {
            // Could try to switch to receiving mode, but panic seems to be the safe option.
            closeQuietly(null, mDatabase, e);
        }
    }

    private TransactionHandler customHandler() throws DatabaseException {
        TransactionHandler handler = mDatabase.mCustomTxnHandler;
        if (handler == null) {
            throw new DatabaseException("Custom transaction handler is not installed");
        }
        return handler;
    }

    private void notifyStore(Index ix, byte[] key, byte[] value) {
        if (ix != null && !Tree.isInternal(ix.getId())) {
            try {
                mManager.notifyStore(ix, key, value);
            } catch (Throwable e) {
                uncaught(e);
            }
        }
    }

    private void fail(Throwable e) {
        if (!mDatabase.isClosed()) {
            EventListener listener = mDatabase.eventListener();
            if (listener != null) {
                listener.notify(EventType.REPLICATION_PANIC,
                                "Unexpected replication exception: %1$s", rootCause(e));
            } else {
                uncaught(e);
            }
        }
        // Panic.
        closeQuietly(null, mDatabase, e);
    }

    UnmodifiableReplicaException unmodifiable() throws DatabaseException {
        mDatabase.checkClosed();
        return new UnmodifiableReplicaException();
    }

    private static long mix(long txnId) {
        return HASH_SPREAD * txnId;
    }

    static final class TxnEntry extends LHashTable.Entry<TxnEntry> {
        LocalTransaction mTxn;
        Worker mWorker;

        void init(LocalTransaction txn) {
            mTxn = txn;
        }
    }

    static final class TxnTable extends LHashTable<TxnEntry> {
        TxnTable(int capacity) {
            super(capacity);
        }

        protected TxnEntry newEntry() {
            return new TxnEntry();
        }
    }
}

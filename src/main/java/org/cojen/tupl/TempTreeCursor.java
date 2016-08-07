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

import java.io.IOException;

/**
 * TreeCursor which prohibits redo durabilty.
 *
 * @author Brian S O'Neill
 */
/*P*/
final class TempTreeCursor extends TreeCursor {
    TempTreeCursor(TempTree tree, Transaction txn) {
        super(tree, txn);
    }

    TempTreeCursor(TempTree tree) {
        super(tree);
    }

    @Override
    public final void store(byte[] value) throws IOException {
        byte[] key = mKey;
        ViewUtils.positionCheck(key);

        try {
            final LocalTransaction txn = mTxn;
            if (txn == null) {
                store(LocalTransaction.BOGUS, leafExclusive(), value);
            } else {
                if (txn.lockMode() != LockMode.UNSAFE) {
                    txn.lockExclusive(mTree.mId, key, keyHash());
                }
                CursorFrame leaf = leafExclusive();
                final DurabilityMode dmode = txn.durabilityMode();
                if (dmode == DurabilityMode.NO_REDO) {
                    store(txn, leaf, value);
                } else {
                    txn.durabilityMode(DurabilityMode.NO_REDO);
                    try {
                        store(txn, leaf, value);
                    } finally {
                        txn.durabilityMode(dmode);
                    }
                }
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }

    @Override
    public final void commit(byte[] value) throws IOException {
        byte[] key = mKey;
        ViewUtils.positionCheck(key);

        try {
            final LocalTransaction txn = mTxn;
            if (txn == null) {
                store(LocalTransaction.BOGUS, leafExclusive(), value);
            } else {
                if (txn.lockMode() != LockMode.UNSAFE) {
                    txn.lockExclusive(mTree.mId, key, keyHash());
                }
                CursorFrame leaf = leafExclusive();
                final DurabilityMode dmode = txn.durabilityMode();
                if (dmode == DurabilityMode.NO_REDO) {
                    store(txn, leaf, value);
                } else {
                    txn.durabilityMode(DurabilityMode.NO_REDO);
                    try {
                        store(txn, leaf, value);
                    } finally {
                        txn.durabilityMode(dmode);
                    }
                }
                txn.commit();
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }
}
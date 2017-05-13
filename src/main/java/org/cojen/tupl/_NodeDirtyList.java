/*
 *  Copyright (C) 2011-2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl;

import java.io.IOException;

import org.cojen.tupl.util.Latch;

/**
 * List of dirty nodes.
 *
 * @author Generated by PageAccessTransformer from NodeDirtyList.java
 */
/*P*/
@SuppressWarnings("serial")
final class _NodeDirtyList extends Latch {
    // Linked list of dirty nodes.
    private _Node mFirstDirty;
    private _Node mLastDirty;

    // Iterator over dirty nodes.
    private _Node mFlushNext;

    _NodeDirtyList() {
    }

    /**
     * Move or add node to the end of the dirty list.
     *
     * @param cachedState node cached state to set
     */
    void add(_Node node, byte cachedState) {
        acquireExclusive();
        try {
            node.mCachedState = cachedState;

            final _Node next = node.mNextDirty;
            final _Node prev = node.mPrevDirty;
            if (next != null) {
                if ((next.mPrevDirty = prev) == null) {
                    mFirstDirty = next;
                } else {
                    prev.mNextDirty = next;
                }
                node.mNextDirty = null;
                (node.mPrevDirty = mLastDirty).mNextDirty = node;
            } else if (prev == null) {
                _Node last = mLastDirty;
                if (last == node) {
                    return;
                }
                if (last == null) {
                    mFirstDirty = node;
                } else {
                    node.mPrevDirty = last;
                    last.mNextDirty = node;
                }
            }

            mLastDirty = node;

            // See flush method for explanation of node latch requirement.
            if (mFlushNext == node) {
                mFlushNext = next;
            }
        } finally {
            releaseExclusive();
        }
    }

    /**
     * Remove the old node from the dirty list and swap in the new node. The cached state of
     * the nodes is not altered.
     */
    void swapIfDirty(_Node oldNode, _Node newNode) {
        acquireExclusive();
        try {
            _Node next = oldNode.mNextDirty;
            if (next != null) {
                newNode.mNextDirty = next;
                next.mPrevDirty = newNode;
                oldNode.mNextDirty = null;
            }
            _Node prev = oldNode.mPrevDirty;
            if (prev != null) {
                newNode.mPrevDirty = prev;
                prev.mNextDirty = newNode;
                oldNode.mPrevDirty = null;
            }
            if (oldNode == mFirstDirty) {
                mFirstDirty = newNode;
            }
            if (oldNode == mLastDirty) {
                mLastDirty = newNode;
            }
            if (oldNode == mFlushNext) {
                mFlushNext = newNode;
            }
        } finally {
            releaseExclusive();
        }
    }

    /**
     * Flush all nodes matching the given state. Only one flush at a time is allowed.
     */
    void flush(final _PageDb pageDb, final int dirtyState) throws IOException {
        acquireExclusive();
        mFlushNext = mFirstDirty;
        releaseExclusive();

        while (true) {
            _Node node;
            while (true) {
                int state;
                acquireExclusive();
                try {
                    node = mFlushNext;
                    if (node == null) {
                        return;
                    }
                    state = node.mCachedState;
                    mFlushNext = node.mNextDirty;
                } finally {
                    releaseExclusive();
                }

                if (state == dirtyState) {
                    node.acquireExclusive();
                    state = node.mCachedState;
                    if (state == dirtyState) {
                        break;
                    }
                    node.releaseExclusive();
                } else if (state != _Node.CACHED_CLEAN) {
                    // Now seeing nodes with new dirty state, so all done flushing.
                    return;
                }
            }

            // Remove from list. Because allocPage requires nodes to be latched,
            // there's no need to update mFlushNext. The removed node will never be
            // the same as mFlushNext.
            acquireExclusive();
            try {
                _Node next = node.mNextDirty;
                _Node prev = node.mPrevDirty;
                if (next != null) {
                    next.mPrevDirty = prev;
                    node.mNextDirty = null;
                } else if (mLastDirty == node) {
                    mLastDirty = prev;
                }
                if (prev != null) {
                    prev.mNextDirty = next;
                    node.mPrevDirty = null;
                } else if (mFirstDirty == node) {
                    mFirstDirty = next;
                }
            } finally {
                releaseExclusive();
            }

            node.downgrade();
            try {
                node.write(pageDb);
                // Clean state must be set after write completes. Although latch has been
                // downgraded to shared, modifying the state is safe because no other thread
                // could have changed it. This is because the exclusive latch was acquired
                // first.  Releasing the shared latch performs a volatile write, and so the
                // state change gets propagated correctly.
                node.mCachedState = _Node.CACHED_CLEAN;
            } finally {
                node.releaseShared();
            }
        }
    }

    /**
     * Remove and delete nodes from dirty list, as part of close sequence.
     */
    void delete(_LocalDatabase db) {
        acquireExclusive();
        try {
            _Node node = mFirstDirty;
            mFlushNext = null;
            mFirstDirty = null;
            mLastDirty = null;
            while (node != null) {
                node.delete(db);
                _Node next = node.mNextDirty;
                node.mPrevDirty = null;
                node.mNextDirty = null;
                node = next;
            }
        } finally {
            releaseExclusive();
        }
    }
}

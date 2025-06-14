/*-
 * Copyright (C) 2002, 2025, Oracle and/or its affiliates. All rights reserved.
 *
 * This file was distributed by Oracle as part of a version of Oracle NoSQL
 * Database made available at:
 *
 * http://www.oracle.com/technetwork/database/database-technologies/nosqldb/downloads/index.html
 *
 * Please see the LICENSE file included in the top-level directory of the
 * appropriate version of Oracle NoSQL Database for a copy of the license and
 * additional information.
 */

package com.sleepycat.je.txn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.dbi.MemoryBudget;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A Lock embodies the lock state of a LSN.  It includes a set of owners and
 * a list of waiters.
 */
public // for Sizeof
class LockImpl implements Lock {
    private static final int REMOVE_LOCKINFO_OVERHEAD =
        0 - MemoryBudget.LOCKINFO_OVERHEAD;

    /**
     * A single locker always appears only once in the logical set of owners.
     * The owners set is always in one of the following states.
     *
     * 1- Empty
     * 2- A single writer
     * 3- One or more readers
     * 4- Multiple writers or a mix of readers and writers, all for
     * txns which share locks (all ThreadLocker instances for the same
     * thread)
     *
     * Both ownerSet and waiterList are a collection of LockInfo.  Since the
     * common case is that there is only one owner or waiter, we have added an
     * optimization to avoid the cost of collections.  FirstOwner and
     * firstWaiter are used for the first owner or waiter of the lock, and the
     * corresponding collection is instantiated and used only if more owners
     * arrive.
     *
     * In terms of memory accounting, we count up the cost of each added or
     * removed LockInfo, but not the cost of the HashSet/List entry
     * overhead. We could do the latter for more precise accounting.
     */
    private LockInfo firstOwner;
    private Set<LockInfo> ownerSet;
    private LockInfo firstWaiter;
    private List<LockInfo> waiterList;

    /**
     * Create a Lock.
     */
    public LockImpl() {
    }

    /* Used when releasing lock. */
    LockImpl(LockImpl lock) {
        this.firstOwner = lock.firstOwner;
        this.ownerSet = lock.ownerSet;
        this.firstWaiter = lock.firstWaiter;
        this.waiterList = lock.waiterList;
    }

    /* Used when mutating from a ThinLockImpl to a LockImpl. */
    LockImpl(LockInfo firstOwner) {
        this.firstOwner = firstOwner;
    }

    /**
     * The first waiter goes into the firstWaiter member variable.  Once the
     * waiterList is made, all appended waiters go into waiterList, even after
     * the firstWaiter goes away and leaves that field null, so as to leave the
     * list ordered.
     */
    private void addWaiterToEndOfList(LockInfo waiter,
                                      MemoryBudget mb,
                                      int lockTableIndex) {
        /* Careful: order important! */
        if (waiterList == null) {
            if (firstWaiter == null) {
                firstWaiter = waiter;
            } else {
                waiterList = new ArrayList<LockInfo>();
                waiterList.add(waiter);
            }
        } else {
            waiterList.add(waiter);
        }
        mb.updateLockMemoryUsage
            (MemoryBudget.LOCKINFO_OVERHEAD, lockTableIndex);
    }

    /**
     * Add this waiter to the front of the list.
     */
    private void addWaiterToHeadOfList(LockInfo waiter,
                                       MemoryBudget mb,
                                       int lockTableIndex) {
        /* Shuffle the current first waiter down a slot. */
        if (firstWaiter != null) {
            if (waiterList == null) {
                waiterList = new ArrayList<LockInfo>();
            }
            waiterList.add(0, firstWaiter);
        }

        firstWaiter = waiter;
        mb.updateLockMemoryUsage
            (MemoryBudget.LOCKINFO_OVERHEAD, lockTableIndex);
    }

    /**
     * Get a list of waiters for debugging and error messages.
     */
    public List<LockInfo> getWaitersListClone() {
        List<LockInfo> dumpWaiters = new ArrayList<LockInfo>();
        if (firstWaiter != null) {
            dumpWaiters.add(firstWaiter);
        }

        if (waiterList != null) {
            dumpWaiters.addAll(waiterList);
        }

        return dumpWaiters;
    }

    /**
     * Remove this locker from the waiter list.
     */
    public void flushWaiter(Locker locker,
                            MemoryBudget mb,
                            int lockTableIndex) {
        if ((firstWaiter != null) && (firstWaiter.getLocker() == locker)) {
            firstWaiter = null;
            mb.updateLockMemoryUsage
                (REMOVE_LOCKINFO_OVERHEAD, lockTableIndex);
        } else if (waiterList != null) {
            Iterator<LockInfo> iter = waiterList.iterator();
            while (iter.hasNext()) {
                LockInfo info = iter.next();
                if (info.getLocker() == locker) {
                    iter.remove();
                    mb.updateLockMemoryUsage
                        (REMOVE_LOCKINFO_OVERHEAD, lockTableIndex);
                    return;
                }
            }
        }
    }

    private void addOwner(LockInfo newLock,
                          MemoryBudget mb,
                          int lockTableIndex) {
        if (firstOwner == null) {
            firstOwner = newLock;
        } else {
            if (ownerSet == null) {
                ownerSet = new HashSet<LockInfo>();
            }
            ownerSet.add(newLock);
        }
        mb.updateLockMemoryUsage
            (MemoryBudget.LOCKINFO_OVERHEAD, lockTableIndex);
    }

    /**
     * Get a new Set of the owners.
     */
    public Set<LockInfo> getOwnersClone(boolean cloneLockInfo) {

        /* No need to update memory usage, the returned Set is transient. */
        Set<LockInfo> owners;
        if (ownerSet != null) {
            if (cloneLockInfo) {
                owners = new HashSet<LockInfo>();
                for (LockInfo owner : ownerSet) {
                    owners.add(owner.clone());
                }
            } else {
                owners = new HashSet<LockInfo>(ownerSet);
            }
        } else {
            owners = new HashSet<LockInfo>();
        }
        if (firstOwner != null) {
            owners.add(cloneLockInfo ? firstOwner.clone() : firstOwner);
        }
        return owners;
    }

    /**
     * Remove this LockInfo from the owner set and clear its memory budget.
     */
    private boolean flushOwner(LockInfo oldOwner,
                               MemoryBudget mb,
                               int lockTableIndex) {
        boolean removed = false;
        if (oldOwner != null) {
            if (firstOwner == oldOwner) {
                firstOwner = null;
                removed = true;
            } else if (ownerSet != null) {
                removed = ownerSet.remove(oldOwner);
            }
        }

        if (removed) {
            mb.updateLockMemoryUsage(REMOVE_LOCKINFO_OVERHEAD, lockTableIndex);
        }
        return removed;
    }

    /**
     * Remove this locker from the owner set.
     */
    private LockInfo flushOwner(Locker locker,
                                MemoryBudget mb,
                                int lockTableIndex) {
        LockInfo flushedInfo = null;
        if ((firstOwner != null) &&
            (firstOwner.getLocker() == locker)) {
            flushedInfo = firstOwner;
            firstOwner = null;
        } else if (ownerSet != null) {
            Iterator<LockInfo> iter = ownerSet.iterator();
            while (iter.hasNext()) {
                LockInfo o = iter.next();
                if (o.getLocker() == locker) {
                    iter.remove();
                    flushedInfo = o;
                }
            }
        }
        if (flushedInfo != null) {
            mb.updateLockMemoryUsage(REMOVE_LOCKINFO_OVERHEAD, lockTableIndex);
        }

        return flushedInfo;
    }

    /**
     * Returns the owner LockInfo for a locker, or null if locker is not an
     * owner.
     */
    private LockInfo getOwnerLockInfo(Locker locker) {
        if ((firstOwner != null) && (firstOwner.getLocker() == locker)) {
            return firstOwner;
        }

        if (ownerSet != null) {
            Iterator<LockInfo> iter = ownerSet.iterator();
            while (iter.hasNext()) {
                LockInfo o = iter.next();
                if (o.getLocker() == locker) {
                    return o;
                }
            }
        }

        return null;
    }

    /**
     * Return true if locker is an owner of this Lock for lockType,
     * false otherwise.
     */
    public boolean isOwner(Locker locker, LockType lockType) {
        LockInfo o = getOwnerLockInfo(locker);
        return o != null && o.getLockType() == lockType;
    }

    /**
     * Return true if locker is an owner of this Lock and this is a write
     * lock.
     */
    public boolean isOwnedWriteLock(Locker locker) {
        LockInfo o = getOwnerLockInfo(locker);
        return o != null && o.getLockType().isWriteLock();
    }

    public LockType getOwnedLockType(Locker locker) {
        LockInfo o = getOwnerLockInfo(locker);
        return (o != null) ? o.getLockType() : null;
    }

    /**
     * Return true if locker is a waiter on this Lock.
     *
     * This method is only used by unit tests.
     */
    public boolean isWaiter(Locker locker) {

        if (firstWaiter != null) {
            if (firstWaiter.getLocker() == locker) {
                return true;
            }
        }

        if (waiterList != null) {
            Iterator<LockInfo> iter = waiterList.iterator();
            while (iter.hasNext()) {
                LockInfo info = iter.next();
                if (info.getLocker() == locker) {
                    return true;
                }
            }
        }
        return false;
    }

    public int nWaiters() {
        int count = 0;
        if (firstWaiter != null) {
            count++;
        }
        if (waiterList != null) {
            count += waiterList.size();
        }
        return count;
    }

    public int nOwners() {
        int count = 0;
        if (firstOwner != null) {
            count++;
        }

        if (ownerSet != null) {
            count += ownerSet.size();
        }
        return count;
    }

    /**
     * Attempts to acquire the lock and returns the LockGrantType.
     *
     * Assumes we hold the lockTableLatch when entering this method.
     */
    public LockAttemptResult lock(LockType requestType,
                                  Locker locker,
                                  boolean nonBlockingRequest,
                                  boolean jumpAheadOfWaiters,
                                  MemoryBudget mb,
                                  int lockTableIndex) {

        assert validateRequest(locker); // intentional side effect

        /* Request an ordinary lock by checking the owners list. */
        LockInfo newLock = new LockInfo(locker, requestType);
        LockGrantType grant = tryLock
            (newLock, jumpAheadOfWaiters || (nWaiters() == 0), mb,
             lockTableIndex);

        /* Do we have to wait for this lock? */
        if (grant == LockGrantType.WAIT_NEW ||
            grant == LockGrantType.WAIT_PROMOTION) {

            /* Add the waiter or deny the lock as appropriate. */
            if (nonBlockingRequest) {
                grant = LockGrantType.DENIED;
            } else {
                if (grant == LockGrantType.WAIT_PROMOTION) {
                    /*
                     * By moving our waiter to the top of the list we reduce
                     * the time window where deadlocks can occur due to the
                     * promotion.
                     */
                    addWaiterToHeadOfList(newLock, mb, lockTableIndex);
                } else {
                    addWaiterToEndOfList(newLock, mb, lockTableIndex);
                }
            }
        }

        /* Set 'success' later. */
        return new LockAttemptResult(this, grant, false);
    }

    /**
     * Releases a lock and moves the next waiter(s) to the owners.
     * @return
     * null if we were not the owner,
     * a non-empty set if owners should be notified after releasing,
     * an empty set if no notification is required.
     */
    public Set<Locker> release(Locker locker,
                               MemoryBudget mb,
                               int lockTableIndex) {

        LockInfo removedLock = flushOwner(locker, mb, lockTableIndex);
        if (removedLock == null) {
            /* Not owner. */
            return null;
        }

        Set<Locker> lockersToNotify = Collections.emptySet();

        if (nWaiters() == 0) {
            /* No more waiters, so no one to notify. */
            return lockersToNotify;
        }

        /*
         * Move the next set of waiters to the owners set. Iterate through the
         * firstWaiter field, then the waiterList.
         */
        LockInfo waiter = null;
        Iterator<LockInfo> iter = null;
        boolean isFirstWaiter = false;

        if (waiterList != null) {
            iter = waiterList.iterator();
        }

        if (firstWaiter != null) {
            waiter = firstWaiter;
            isFirstWaiter = true;
        } else if ((iter != null) && (iter.hasNext())) {
            waiter = iter.next();
        }

        while (waiter != null) {
            /* Make the waiter an owner if the lock can be acquired. */
            LockType waiterType = waiter.getLockType();
            Locker waiterLocker = waiter.getLocker();
            LockGrantType grant;
            /* Try locking. */
            grant = tryLock(waiter, true, mb, lockTableIndex);
            /* Check if granted. */
            if (grant == LockGrantType.NEW ||
                grant == LockGrantType.EXISTING ||
                grant == LockGrantType.PROMOTION) {
                /* Remove it from the waiters list. */
                if (isFirstWaiter) {
                    firstWaiter = null;
                } else {
                    iter.remove();
                }
                if (lockersToNotify == Collections.EMPTY_SET) {
                    lockersToNotify = new HashSet<Locker>();
                }
                lockersToNotify.add(waiterLocker);
                mb.updateLockMemoryUsage
                    (REMOVE_LOCKINFO_OVERHEAD, lockTableIndex);
            } else {
                assert grant == LockGrantType.WAIT_NEW ||
                       grant == LockGrantType.WAIT_PROMOTION;
                /* Stop on first waiter that cannot be an owner. */
                break;
            }

            /* Move to the next waiter, if it's in the list. */
            if ((iter != null) && (iter.hasNext())) {
                waiter = iter.next();
                isFirstWaiter = false;
            } else {
                waiter = null;
            }
        }
        return lockersToNotify;
    }

    public void stealLock(@Nullable Locker locker,
                          MemoryBudget mb,
                          int lockTableIndex) {

        if (firstOwner != null) {
            if (preemptLocker(firstOwner.getLocker(), locker)) {
                firstOwner = null;
                mb.updateLockMemoryUsage(REMOVE_LOCKINFO_OVERHEAD,
                                         lockTableIndex);
            }
        }

        if (ownerSet != null) {
            Iterator<LockInfo> iter = ownerSet.iterator();
            while (iter.hasNext()) {
                if (preemptLocker(iter.next().getLocker(), locker)) {
                    iter.remove();
                    mb.updateLockMemoryUsage(REMOVE_LOCKINFO_OVERHEAD,
                                             lockTableIndex);
                }
            }
        }
    }

    /*
     * Preempts a non-replay owning txn (preemptLocker) when lock stealing is
     * requested by the 'importunate' replay txn (forLocker).
     *
     * True is returned if preemptLocker is set to preempted, and the caller
     * should remove it from the owners list.
     *
     * False is returned if the two lockers are the same, i.e, the requester
     * already holds the lock. In this case the lock will be granted.
     *
     * False is also returned if preemptLocker is set to non-preemptable. This
     * is a special case used for short-lived locks held by the cleaner or
     * other internal operations. In this case the lock will not be stolen and
     * LockManager.waitForLock will retry until the internal operation
     * finishes and the lock can be granted.
     *
     * An EnvironmentFailureException is thrown if preemptLocker is also a
     * replay txn (is also 'importunate'). This indicates that the replication
     * stream is invalid, since lock conflicts between two replay txns should
     * never occur.
     */
    static boolean preemptLocker(Locker preemptLocker, Locker forLocker) {
        if (preemptLocker == forLocker) {
            return false;
        }
        if (preemptLocker.getImportunate()) {
            throw new EnvironmentFailureException(null,
                EnvironmentFailureReason.LOG_INTEGRITY,
                "Replay locker txn=" + forLocker.getId() +
                    " attempt to acquire lock held by another replay" +
                    " locker txn=" + preemptLocker.getId());
        }
        if (preemptLocker.getPreemptable()) {
            preemptLocker.setPreempted();
            return true;
        }
        return false;
    }

    /**
     * Called from lock() to try locking a new request, and from release() to
     * try locking a waiting request.
     *
     * @param newLock is the lock that is requested.
     *
     * @param firstWaiterInLine determines whether to grant the lock when a
     * NEW lock can be granted, but other non-conflicting owners exist; for
     * example, when a new READ lock is requested but READ locks are held by
     * other owners.  This parameter should be true if the requestor is the
     * first waiter in line (or if there are no waiters), and false otherwise.
     *
     * @param mb is the current memory budget.
     *
     * @return LockGrantType.EXISTING, NEW, PROMOTION, WAIT_RESTART, WAIT_NEW
     * or WAIT_PROMOTION.
     */
    private LockGrantType tryLock(LockInfo newLock,
                                  boolean firstWaiterInLine,
                                  MemoryBudget mb,
                                  int lockTableIndex) {

        /* If no one owns this right now, just grab it. */
        if (nOwners() == 0) {
            addOwner(newLock, mb, lockTableIndex);
            return LockGrantType.NEW;
        }

        Locker locker = newLock.getLocker();
        LockType requestType = newLock.getLockType();
        LockUpgrade upgrade = null;
        LockInfo lockToUpgrade = null;
        boolean ownerExists = false;
        boolean ownerConflicts = false;

        /*
         * Iterate through the current owners. See if there is a current owner
         * who has to be upgraded from read to write. Also track whether there
         * is a conflict with another owner.
         */
        LockInfo owner = null;
        Iterator<LockInfo> iter = null;

        if (ownerSet != null) {
            iter = ownerSet.iterator();
        }

        if (firstOwner != null) {
            owner = firstOwner;
        } else if ((iter != null) && (iter.hasNext())) {
            owner = iter.next();
        }

        while (owner != null) {
            Locker ownerLocker = owner.getLocker();
            LockType ownerType = owner.getLockType();
            if (locker == ownerLocker) {

                /*
                 * Requestor currently holds this lock: check for upgrades.
                 * If no type change is needed, return EXISTING now to avoid
                 * iterating further; otherwise, we need to check for conflicts
                 * before granting the upgrade.
                 */
                assert (upgrade == null); // An owner should appear only once
                upgrade = ownerType.getUpgrade(requestType);
                if (upgrade.getUpgrade() == null) {
                    return LockGrantType.EXISTING;
                } else {
                    lockToUpgrade = owner;
                }
            } else {

                /*
                 * Requestor does not hold this lock: check for conflicts.
                 * If the owner shares locks with the requestor, ignore it;
                 * otherwise, save the conflict information.
                 */
                if (!locker.sharesLocksWith(ownerLocker) &&
                    !ownerLocker.sharesLocksWith(locker)) {
                    LockConflict conflict = ownerType.getConflict(requestType);
                    if (!conflict.getAllowed()) {
                        ownerConflicts = true;
                    }
                    ownerExists = true;
                }
            }

            /* Move on to the next owner. */
            if ((iter != null) && (iter.hasNext())) {
                owner = iter.next();
            } else {
                owner = null;
            }
        }

        /* Now handle the upgrade or conflict as appropriate. */
        if (upgrade != null) {
            /* The requestor holds this lock. */
            LockType upgradeType = upgrade.getUpgrade();
            assert upgradeType != null;
            if (!ownerConflicts) {
                /* No conflict: grant the upgrade.  */
                lockToUpgrade.setLockType(upgradeType);
                return upgrade.getPromotion() ?
                    LockGrantType.PROMOTION : LockGrantType.EXISTING;
            } else {
                /* Upgrade cannot be granted at this time. */
                return LockGrantType.WAIT_PROMOTION;
            }
        } else {
            /* The requestor doesn't hold this lock. */
            if (!ownerConflicts && (!ownerExists || firstWaiterInLine)) {
                /* No conflict: grant the lock. */
                addOwner(newLock, mb, lockTableIndex);
                return LockGrantType.NEW;
            } else {
                /* Lock cannot be granted at this time. */
                return LockGrantType.WAIT_NEW;
            }
        }
    }

    /**
     * Downgrade a write lock to a read lock.
     */
    public boolean demote(Locker locker) {
        LockInfo owner = getOwnerLockInfo(locker);
        if (owner != null) {
            LockType type = owner.getLockType();
            if (type.isWriteLock()) {
                owner.setLockType(LockType.READ);
                return true;
            }
        }
        return false;
    }

    /**
     * Return the locker that has a write ownership on this lock. If no
     * write owner exists, return null.
     */
    public Locker getWriteOwnerLocker() {

        LockInfo owner = null;
        Iterator<LockInfo> iter = null;

        if (ownerSet != null) {
            iter = ownerSet.iterator();
        }

        if (firstOwner != null) {
            owner = firstOwner;
        } else if ((iter != null) && (iter.hasNext())) {
            owner = iter.next();
        }

        while (owner != null) {
            /* Return locker if it owns a write lock. */
            if (owner.getLockType().isWriteLock()) {
                return owner.getLocker();
            }

            /* Move on to the next owner. */
            if ((iter != null) && (iter.hasNext())) {
                owner = iter.next();
            } else {
                owner = null;
            }
        }

        return null;
    }

    /**
     * Debugging aid, validation before a lock request.
     */
    private boolean validateRequest(Locker locker) {
        if (firstWaiter != null) {
            if (firstWaiter.getLocker() == locker) {
                assert false : "locker " + locker +
                                " is already on waiters list.";
            }
        }

        if (waiterList != null) {
            Iterator<LockInfo> iter = waiterList.iterator();
            while (iter.hasNext()) {
                LockInfo o = iter.next();
                if (o.getLocker() == locker) {
                    assert false : "locker " + locker +
                        " is already on waiters list.";
                }
            }
        }
        return true;
    }

    public boolean isThin() {
        return false;
    }

    /**
     * Debug dumper.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(" LockAddr:").append(System.identityHashCode(this));
        sb.append(" Owners:");
        if (nOwners() == 0) {
            sb.append(" (none)");
        } else {
            if (firstOwner != null) {
                sb.append(firstOwner);
            }

            if (ownerSet != null) {
                Iterator<LockInfo> iter = ownerSet.iterator();
                while (iter.hasNext()) {
                    LockInfo info = iter.next();
                    sb.append(info);
                }
            }
        }

        sb.append(" Waiters:");
        if (nWaiters() == 0) {
            sb.append(" (none)");
        } else {
            sb.append(getWaitersListClone());
        }
        return sb.toString();
    }
}

package org.jgroups.protocols;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.jgroups.Address;
import org.jgroups.View;
import org.jgroups.annotations.Experimental;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.locking.LockNotification;
import org.jgroups.blocks.locking.Owner;
import org.jgroups.util.Util;


/**
 * Implementation of a locking protocol which acquires locks by contacting the coordinator.</p> Because the
 * coordinator maintains all locks, no total order configuration is required.<p>
 * CENTRAL_LOCK has all members send lock and unlock requests to a central coordinator. The coordinator has a queue for
 * incoming requests, and grants locks based on order of arrival. To prevent all acquired locks from being forgotten
 * when the coordinator crashes, setting num_backups lets the coordinator backup lock information to a number of
 * backup nodes. Valid values for num_backups are 0 (no backup) to N-1, e.g. in a cluster of 4, we can have only 3 backup
 * nodes.</p>
 * Say we have a cluster of {A,B,C,D,E} and num_backups=1. A is the coordinator, and A updates all locks (and released
 * locks) in B as well. When A crashes, everybody falls over to B for sending lock and unlock requests.
 * B in turn copies all existing locks over to C and - when locks are acquired or released - forwards this
 * information to C as well.
 * <p/>
 * An alternative is also the {@link org.jgroups.protocols.PEER_LOCK} protocol.
 * @author Bela Ban
 * @since 2.12
 * @see Locking
 * @see PEER_LOCK
 */
@Experimental
public class CENTRAL_LOCK extends Locking implements LockNotification {

    @Property(description="Number of backups to the coordinator. Server locks get replicated to these nodes as well")
    protected int num_backups=1;

    protected Address coord;

    @ManagedAttribute
    protected boolean is_coord;

    protected final List<Address> backups=new ArrayList<Address>();


    public CENTRAL_LOCK() {
        super();
        addLockListener(this);
    }

    public Address getCoord() {
        return coord;
    }

    public boolean isCoord() {
        return is_coord;
    }

    @ManagedAttribute
    public String getCoordinator() {
        return coord != null? coord.toString() : "n/a";
    }

    public int getNumberOfBackups() {
        return num_backups;
    }

    public void setNumberOfBackups(int num_backups) {
        this.num_backups=num_backups;
    }

    @ManagedAttribute
    public String getBackups() {
        return backups != null? backups.toString() : null;
    }

    protected void sendGrantLockRequest(String lock_name, Owner owner, long timeout, boolean is_trylock) {
        if(coord != null)
            sendRequest(coord, Type.GRANT_LOCK, lock_name, owner, timeout, is_trylock);
    }

    protected void sendReleaseLockRequest(String lock_name, Owner owner) {
        if(coord != null)
            sendRequest(coord, Type.RELEASE_LOCK, lock_name, owner, 0, false);
    }

    protected void sendCreateLockRequest(Address dest, String lock_name, Owner owner) {
        sendRequest(dest, Type.CREATE_LOCK, lock_name, owner, 0, false);
    }

    protected void sendDeleteLockRequest(Address dest, String lock_name) {
        sendRequest(dest, Type.DELETE_LOCK, lock_name, null, 0, false);
    }

    @Override
    protected void sendAwaitConditionRequest(String lock_name, Owner owner) {
        sendRequest(coord, Type.LOCK_AWAIT, lock_name, owner, 0, false);
    }

    @Override
    protected void sendSignalConditionRequest(String lock_name, boolean all) {
        sendRequest(coord, all ? Type.COND_SIG_ALL : Type.COND_SIG, lock_name, null, 0, false);
    }
    
    @Override
    protected void sendDeleteAwaitConditionRequest(String lock_name, Owner owner) {
        sendRequest(coord, Type.DELETE_LOCK_AWAIT, lock_name, owner, 0, false);
    }

    public void handleView(View view) {
        super.handleView(view);
        Address old_coord=coord;
        if(view.size() > 0) {
            coord=view.getMembers().firstElement();
            is_coord=coord.equals(local_addr);
            if(log.isDebugEnabled())
                log.debug("local_addr=" + local_addr + ", coord=" + coord + ", is_coord=" + is_coord);
        }

        if(is_coord && num_backups > 0) {
            List<Address> new_backups=Util.pickNext(view.getMembers(), local_addr, num_backups);
            List<Address> copy_locks_list=null;
            synchronized(backups) {
                if(!backups.equals(new_backups)) {
                    copy_locks_list=new ArrayList<Address>(new_backups);
                    copy_locks_list.removeAll(backups);
                    backups.clear();
                    backups.addAll(new_backups);
                }
            }

            if(copy_locks_list != null && !copy_locks_list.isEmpty())
                copyLocksTo(copy_locks_list);
        }

        // For all non-acquired client locks, send the GRANT_LOCK request to the new coordinator (if changed)
        if(old_coord != null && !old_coord.equals(coord)) {
            Map<String,Map<Owner,ClientLock>> copy;
            synchronized(client_locks) {
                copy=new HashMap<String,Map<Owner,ClientLock>>(client_locks);
            }
            if(!copy.isEmpty()) {
                for(Map<Owner,ClientLock> map: copy.values()) {
                    for(ClientLock lock: map.values()) {
                        if(!lock.acquired && !lock.denied)
                            sendGrantLockRequest(lock.name, lock.owner, lock.timeout, lock.is_trylock);
                    }
                }
            }
        }
    }

    public void lockCreated(String name) {
    }

    public void lockDeleted(String name) {
    }

    public void locked(String lock_name, Owner owner) {
        if(is_coord)
            updateBackups(Type.CREATE_LOCK, lock_name, owner);
    }

    public void unlocked(String lock_name, Owner owner) {
        if(is_coord)
            updateBackups(Type.DELETE_LOCK, lock_name, owner);
    }
    
    public void awaiting(String lock_name, Owner owner) {
        if(is_coord)
            updateBackups(Type.CREATE_AWAITER, lock_name, owner);
    }

    public void awaited(String lock_name, Owner owner) {
        if(is_coord)
            updateBackups(Type.DELETE_AWAITER, lock_name, owner);
    }

    protected void updateBackups(Type type, String lock_name, Owner owner) {
        synchronized(backups) {
            for(Address backup: backups)
                sendRequest(backup, type, lock_name, owner, 0, false);
        }
    }



    protected void copyLocksTo(List<Address> new_joiners) {
        Map<String,ServerLock> copy;

        synchronized(server_locks) {
            copy=new HashMap<String,ServerLock>(server_locks);
        }

        if(log.isTraceEnabled())
            log.trace("copying locks to " + new_joiners);
        for(Map.Entry<String,ServerLock> entry: copy.entrySet()) {
            for(Address joiner: new_joiners) {
                ServerLock lock = entry.getValue();
                if (lock.current_owner != null) {
                    sendCreateLockRequest(joiner, entry.getKey(), entry.getValue().current_owner);
                }
                synchronized (lock.condition) {
                    Queue<Owner> queue = lock.condition.queue;
                    for (Owner owner : queue) {
                        sendAwaitConditionRequest(lock.lock_name, owner);
                    }
                }
            }
        }
    }
}


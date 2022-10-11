package org.jgroups.protocols;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Header;
import org.jgroups.Message;
import org.jgroups.View;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.executor.ExecutionService.DistributedFuture;
import org.jgroups.blocks.executor.ExecutorEvent;
import org.jgroups.blocks.executor.ExecutorNotification;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Streamable;
import org.jgroups.util.Util;

/**
 * This is the base protocol used for executions.
 * @author wburns
 * @see org.jgroups.protocols.CENTRAL_EXECUTOR
 */
@MBean(description="Based class for executor service functionality")
abstract public class Executing extends Protocol {

    @Property(description="bypasses message bundling if set")
    protected boolean bypass_bundling=true;


    protected Address local_addr;

    protected View view;
    
    /**
     * This is a queue on the client side that holds all of the tasks that
     * are awaiting a consumer to pick them up
     */
    protected final Queue<Runnable> _awaitingConsumer = 
        new ConcurrentLinkedQueue<Runnable>();
    
    /**
     * This is a map on the client side showing for all of the current pending
     * requests
     */
    protected final ConcurrentMap<Runnable, Long> _requestId = 
        new ConcurrentHashMap<Runnable, Long>();
    
    /**
     * This is essentially a set on the consumer side of id's of all the threads 
     * currently running as consumers.  This is basically a set, but since
     * there is no ConcurrentHashSet we use a phoney value
     */
    protected final ConcurrentMap<Long, Object> _consumerId = 
        new ConcurrentHashMap<Long, Object>();

    protected final ConcurrentMap<Future<?>, ExecutorNotification> notifiers = 
        new ConcurrentHashMap<Future<?>, ExecutorNotification>();
    
    /**
     * This is a map on the server side that shows which owner is currently
     * tied to the runnable so we can return to them the results
     */
    protected final Map<Runnable, Owner> _running;
    
    /**
     * This is a map on the client side that shows for which 
     * owner(consumer, request) the runnable they are currently using.  This 
     * also allows us to set the values on a future when finished.
     */
    protected final Map<Owner, Runnable> _awaitingReturn;
    
    /**
     * This is a server side queue of all the tasks to pass off.  Currently 
     * there will never be tasks waiting to put in.  If a task is put in and doesn't have a 
     * respective take at the same time that task is rejected.
     */
    protected BlockingQueue<Runnable> _tasks = new SynchronousQueue<Runnable>();
    
    /**
     * This is a server side map to show which threads are running for a
     * given runnable.  This is used to interrupt those threads if needed.
     */
    protected final ConcurrentMap<Runnable, Thread> _runnableThreads = 
        new ConcurrentHashMap<Runnable, Thread>();
    
    /**
     * This lock is to protect the incoming run requests and the incoming
     * consumer queues
     */
    protected Lock _consumerLock = new ReentrantLock();
    
    /**
     * This is stored on the coordinator side.  This queue holds all of the
     * addresses that currently want to run something.  If this queue has
     * elements the consumer queue must be empty.
     */
    protected Queue<Owner> _runRequests = new ArrayDeque<Owner>();
    
    /**
     * This is stored on the coordinator side.  This queue holds all of the
     * addresses that currently are able to run something.  If this queue has
     * elements the run request queue must be empty.
     */
    protected Queue<Owner> _consumersAvailable = new ArrayDeque<Owner>();
    
    protected static enum Type {
        RUN_REQUEST,            // request to coordinator from client to tell of a new task request
        CONSUMER_READY,         // request to coordinator from server to tell of a new consumer ready
        CONSUMER_UNREADY,       // request to coordinator from server to tell of a consumer stopping
        CONSUMER_FOUND,         // response to client from coordinator of the consumer to send the task to
        RUN_SUBMITTED,          // request to consumer from client the task to run
        RUN_REJECTED,           // response to client from the consumer due to the consumer being gone (usually because the runner was stopped)
        RESULT_EXCEPTION,       // response to client from the consumer when an exception was encountered
        RESULT_SUCCESS,         // response to client from the consumer when a value is returned
        INTERRUPT_RUN,          // request to consumer from client to interrupt the task
        CREATE_RUN_REQUEST,     // request to backups from coordinator to create a new task request. Used by CENTRAL_LOCKING
        CREATE_CONSUMER_READY,  // request to backups from coordinator to create a new consumer ready. Used by CENTRAL_LOCKING
        DELETE_RUN_REQUEST,     // request to backups from coordinator to delete a task request. Used by CENTRAL_LOCKING
        DELETE_CONSUMER_READY   // request to backups from coordinator to delete a consumer ready. Used by CENTRAL_LOCKING
    }
    
    public Executing() {
        _awaitingReturn = Collections.synchronizedMap(new HashMap<Owner, Runnable>());
        _running = Collections.synchronizedMap(new HashMap<Runnable, Owner>());
    }


    public boolean getBypassBundling() {
        return bypass_bundling;
    }

    public void setBypassBundling(boolean bypass_bundling) {
        this.bypass_bundling=bypass_bundling;
    }

    public void addExecutorListener(Future<?> future,
                                    ExecutorNotification listener) {
        if(listener != null)
            notifiers.put(future, listener);
    }

    @ManagedAttribute
    public String getAddress() {
        return local_addr != null? local_addr.toString() : null;
    }

    @ManagedAttribute
    public String getView() {
        return view != null? view.toString() : null;
    }
   

    public Object down(Event evt) {
        switch(evt.getType()) {
            case ExecutorEvent.TASK_SUBMIT:
                Runnable runnable = (Runnable)evt.getArg();
                _awaitingConsumer.add(runnable);
                // We are limited to a number of concurrent request id's
                // equal to 2^63-1.  This is quite large and if it 
                // overflows it will still be positive
                long requestId = Math.abs(counter.getAndIncrement());
                _requestId.put(runnable, requestId);
                sendToCoordinator(Type.RUN_REQUEST, requestId, local_addr);
                break;
            case ExecutorEvent.CONSUMER_READY:
                Thread currentThread = Thread.currentThread();
                long id = currentThread.getId();
                _consumerId.put(id, new Object());
                sendToCoordinator(Type.CONSUMER_READY, id, local_addr);
                try {
                    // Unfortunately we can't start taking before we send
                    // a message, therefore we have to do a timed poll on
                    // _tasks below to make sure that we have time to call take
                    runnable = _tasks.take();
                    _runnableThreads.put(runnable, currentThread);
                    return runnable;
                }
                catch (InterruptedException e) {
                    sendToCoordinator(Type.CONSUMER_UNREADY, id, local_addr);
                    Thread.currentThread().interrupt();
                }
                finally {
                    _consumerId.remove(id);
                }
                break;
            case ExecutorEvent.TASK_COMPLETE:
                Object arg = evt.getArg();
                Throwable throwable = null;
                if (arg instanceof Object[]) {
                    Object[] array = (Object[])arg;
                    runnable = (Runnable)array[0];
                    throwable = (Throwable)array[1];
                }
                else {
                    runnable = (Runnable)arg;
                }
                Owner owner = _running.remove(runnable);
                // This won't remove anything if owner doesn't come back
                _runnableThreads.remove(runnable);

                Object value = null;
                boolean exception = false;
                if (throwable != null) {
                    // InterruptedException is special telling us that
                    // we interrupted the thread while waiting but still got
                    // a task therefore we have to reject it.
                    if (throwable instanceof InterruptedException) {
                        sendRequest(owner.address, Type.RUN_REJECTED, owner.requestId, null);
                        break;
                    }
                    value = throwable;
                    exception = true;
                }
                else if (runnable instanceof RunnableFuture<?>) {
                    RunnableFuture<?> future = (RunnableFuture<?>)runnable;
                    
                    boolean interrupted = false;
                    boolean gotValue = false;
                    
                    // We have the value, before we interrupt at least get it!
                    while (!gotValue) {
                        try {
                            value = future.get();
                            gotValue = true;
                        }
                        catch (InterruptedException e) {
                            interrupted = true;
                        }
                        catch (ExecutionException e) {
                            value = e.getCause();
                            exception = true;
                            gotValue = true;
                        }
                    }
                    
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                if (owner != null) {
                    final Type type;
                    final Object valueToSend;
                    if (value == null) {
                        type = Type.RESULT_SUCCESS;
                        valueToSend = value;
                    }
                    // Both serializable values and exceptions would go in here
                    else if (value instanceof Serializable || 
                            value instanceof Externalizable || 
                            value instanceof Streamable) {
                        type = exception ? Type.RESULT_EXCEPTION : Type.RESULT_SUCCESS;
                        valueToSend = value;
                    }
                    // This would happen if the value wasn't serializable,
                    // so we have to send back to the client that the class
                    // wasn't serializable
                    else {
                        type = Type.RESULT_EXCEPTION;
                        valueToSend = new NotSerializableException(
                            value.getClass().getName());
                    }
                    
                    if (local_addr.equals(owner.getAddress())) {
                        if(log.isTraceEnabled())
                            log.trace("[redirect] <--> [" + local_addr + "] "
                                    + type.name() + " [" + value
                                    + (owner.requestId != -1 ? " request id: " + 
                                            owner.requestId : "")
                                    + "]");
                        final Owner finalOwner = owner;
                        if (type == Type.RESULT_SUCCESS) {
                            handleValueResponse(local_addr, 
                                finalOwner.requestId, valueToSend);
                        }
                        else if (type == Type.RESULT_EXCEPTION){
                            handleExceptionResponse(local_addr, 
                                finalOwner.requestId, (Throwable)valueToSend);
                        }
                    }
                    else {
                        sendRequest(owner.getAddress(), type, owner.requestId, 
                            valueToSend);
                    }
                }
                else {
                    if (log.isTraceEnabled()) {
                        log.trace("Could not return result - most likely because it was interrupted");
                    }
                }
                break;
            case ExecutorEvent.TASK_CANCEL:
                Object[] array = (Object[])evt.getArg();
                runnable = (Runnable)array[0];
                
                if (_awaitingConsumer.remove(runnable)) {
                    _requestId.remove(runnable);
                    if (log.isTraceEnabled())
                        log.trace("Cancelled task " + runnable + " before it was picked up");
                    return Boolean.TRUE;
                }
                // This is guaranteed to not be null so don't take cost of auto unboxing
                else if (array[1] == Boolean.TRUE) {
                    owner = removeKeyForValue(_awaitingReturn, runnable);
                    if (owner != null) {
                        Long requestIdValue = _requestId.remove(runnable);
                        // We only cancel if the requestId is still available
                        // this means the result hasn't been returned yet and
                        // we still have a chance to interrupt
                        if (requestIdValue != null) {
                            if (requestIdValue != owner.getRequestId()) {
                                log.warn("Cancelling requestId didn't match waiting");
                            }
                            sendRequest(owner.getAddress(), Type.INTERRUPT_RUN, 
                                owner.getRequestId(), null);
                        }
                    }
                    else {
                        if (log.isTraceEnabled())
                            log.warn("Couldn't interrupt server task: " + runnable);
                    }
                    ExecutorNotification notification = notifiers.remove(runnable);
                    if (notification != null) {
                        notification.interrupted(runnable);
                    }
                    return Boolean.TRUE;
                }
                else {
                    return Boolean.FALSE;
                }
            case ExecutorEvent.ALL_TASK_CANCEL:
                array = (Object[])evt.getArg();
                
                // This is a RunnableFuture<?> so this cast is okay
                @SuppressWarnings("unchecked")
                Set<Runnable> runnables = (Set<Runnable>)array[0];
                Boolean booleanValue = (Boolean)array[1];
                
                List<Runnable> notRan = new ArrayList<Runnable>();
                
                for (Runnable cancelRunnable : runnables) {
                    // Removed from the consumer
                    if (!_awaitingConsumer.remove(cancelRunnable) && 
                            booleanValue == Boolean.TRUE) {
                        synchronized (_awaitingReturn) {
                            owner = removeKeyForValue(_awaitingReturn, cancelRunnable);
                            if (owner != null) {
                                Long requestIdValue = _requestId.remove(cancelRunnable);
                                if (requestIdValue != owner.getRequestId()) {
                                    log.warn("Cancelling requestId didn't match waiting");
                                }
                                sendRequest(owner.getAddress(), Type.INTERRUPT_RUN, 
                                    owner.getRequestId(), null);
                            }
                            ExecutorNotification notification = notifiers.remove(cancelRunnable);
                            if (notification != null) {
                                log.trace("Notifying listener");
                                notification.interrupted(cancelRunnable);
                            }
                        }
                    }
                    else {
                        _requestId.remove(cancelRunnable);
                        notRan.add(cancelRunnable);
                    }
                }
                return notRan;
            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address)evt.getArg();
                break;

            case Event.VIEW_CHANGE:
                handleView((View)evt.getArg());
                break;
        }
        return down_prot.down(evt);
    }
    
    protected static <V, K> V removeKeyForValue(Map<V, K> map, K value) {
        synchronized (map) {
            Iterator<Entry<V, K>> iter = 
                map.entrySet().iterator();
            while (iter.hasNext()) {
                Entry<V, K> entry = iter.next();
                if (entry.getValue().equals(value)) {
                    iter.remove();
                    return entry.getKey();
                }
            }
        }
        
        return null;
    }

    public Object up(Event evt) {
        switch(evt.getType()) {
            case Event.MSG:
                Message msg=(Message)evt.getArg();
                ExecutorHeader hdr=(ExecutorHeader)msg.getHeader(id);
                if(hdr == null)
                    break;

                Request req=(Request)msg.getObject();
                if(log.isTraceEnabled())
                    log.trace("[" + local_addr + "] <-- [" + msg.getSrc() + "] " + req);
                switch(req.type) {
                    case RUN_REQUEST:
                        handleTaskRequest(req.request, (Address)req.object);
                        break;
                    case CONSUMER_READY:
                        handleConsumerReadyRequest(req.request, (Address)req.object);
                        break;
                    case CONSUMER_UNREADY:
                        handleConsumerUnreadyRequest(req.request, (Address)req.object);
                        break;
                    case CONSUMER_FOUND:
                        handleConsumerFoundResponse(req.request, (Address)req.object);
                        break;
                    case RUN_SUBMITTED:
                        Object objectToRun = req.object;
                        Runnable runnable;
                        if (objectToRun instanceof Runnable) {
                            runnable = (Runnable)objectToRun;
                        }
                        else if (objectToRun instanceof Callable) {
                            @SuppressWarnings("unchecked")
                            Callable<Object> callable = (Callable<Object>)objectToRun;
                            runnable = new FutureTask<Object>(callable);
                        }
                        else {
                            log.error("Request of type " + req.type + 
                                " sent an object of " + objectToRun + " which is invalid");
                            break;
                        }
                        
                        handleTaskSubmittedRequest(runnable, msg.getSrc(), 
                            req.request);
                        break;
                    case RUN_REJECTED:
                        // We could make requests local for this, but is it really worth it
                        handleTaskRejectedResponse(msg.getSrc(), req.request);
                        break;
                    case RESULT_SUCCESS:
                        handleValueResponse(msg.getSrc(), req.request, req.object);
                        break;
                    case RESULT_EXCEPTION:
                        handleExceptionResponse(msg.getSrc(), req.request, 
                            (Throwable)req.object);
                        break;
                    case INTERRUPT_RUN:
                        // We could make requests local for this, but is it really worth it
                        handleInterruptRequest(msg.getSrc(), req.request);
                        break;
                    case CREATE_CONSUMER_READY:
                        Owner owner = new Owner((Address)req.object, req.request);
                        handleNewConsumer(owner);
                        break;
                    case CREATE_RUN_REQUEST:
                        owner = new Owner((Address)req.object, req.request);
                        handleNewRunRequest(owner);
                        break;
                    case DELETE_CONSUMER_READY:
                        owner = new Owner((Address)req.object, req.request);
                        handleRemoveConsumer(owner);
                        break;
                    case DELETE_RUN_REQUEST:
                        owner = new Owner((Address)req.object, req.request);
                        handleRemoveRunRequest(owner);
                        break;
                    default:
                        log.error("Request of type " + req.type + " not known");
                        break;
                }
                return null;

            case Event.VIEW_CHANGE:
                handleView((View)evt.getArg());
                break;
        }
        return up_prot.up(evt);
    }

    protected void handleView(View view) {
        this.view=view;
        if(log.isDebugEnabled())
            log.debug("view=" + view);
        List<Address> members=view.getMembers();
        
        _consumerLock.lock();
        try {
            // This removes the consumers that were registered that are now gone
            Iterator<Owner> iterator = _consumersAvailable.iterator();
            while (iterator.hasNext()) {
                Owner owner = iterator.next();
                if (!members.contains(owner.getAddress())) {
                    iterator.remove();
                    sendRemoveConsumerRequest(owner);
                }
            }
            
            // This removes the tasks that those requestors are gone
            iterator = _runRequests.iterator();
            while (iterator.hasNext()) {
                Owner owner = iterator.next();
                if (!members.contains(owner.getAddress())) {
                    iterator.remove();
                    sendRemoveRunRequest(owner);
                }
            }
            
            for (Entry<Owner, Runnable> entry :_awaitingReturn.entrySet()) {
                // The person currently servicing our request has gone down
                // without completing so we have to keep our request alive by
                // sending ours back to the coordinator
                Owner owner = entry.getKey();
                if (!members.contains(owner.getAddress())) {
                    sendToCoordinator(Type.RUN_REQUEST, owner.getRequestId(), 
                        owner.getAddress());
                    Runnable runnable = entry.getValue();
                    _requestId.put(runnable, owner.getRequestId());
                    _awaitingConsumer.add(runnable);
                }
            }
        }
        finally {
            _consumerLock.unlock();
        }
    }

    abstract protected void sendToCoordinator(Type type, long requestId, Address address);
    abstract protected void sendNewRunRequest(Owner source);
    abstract protected void sendRemoveRunRequest(Owner source);
    abstract protected void sendNewConsumerRequest(Owner source);
    abstract protected void sendRemoveConsumerRequest(Owner source);

    protected void handleTaskRequest(long requestId, Address address) {
        final Owner consumer;
        Owner source = new Owner(address, requestId);
        _consumerLock.lock();
        try {
            consumer = _consumersAvailable.poll();
            // We don't add duplicate run requests - this allows for resubmission
            // if it is thought the message may have been dropped
            if (consumer == null && !_runRequests.contains(source)) {
                _runRequests.add(source);
            }
        }
        finally {
            _consumerLock.unlock();
        }
        
        if (consumer != null) {
            sendRequest(source.getAddress(), Type.CONSUMER_FOUND, 
                consumer.getRequestId(), consumer.getAddress());
            sendRemoveConsumerRequest(consumer);
        }
        else {
            sendNewRunRequest(source);
        }
    }

    protected void handleConsumerReadyRequest(long requestId, Address address) {
        Owner requestor;
        final Owner source = new Owner(address, requestId);
        _consumerLock.lock();
        try {
            requestor = _runRequests.poll();
            // We don't add duplicate consumers - this allows for resubmission
            // if it is thought the message may have been dropped
            if (requestor == null && !_consumersAvailable.contains(source)) {
                _consumersAvailable.add(source);
            }
        }
        finally {
            _consumerLock.unlock();
        }
        
        if (requestor != null) {
            sendRequest(requestor.getAddress(), Type.CONSUMER_FOUND, 
                source.getRequestId(), source.getAddress());
            sendRemoveRunRequest(requestor);
        }
        else {
            sendNewConsumerRequest(source);
        }
    }
    
    protected void handleConsumerUnreadyRequest(long requestId, Address address) {
        Owner consumer = new Owner(address, requestId);
        _consumersAvailable.remove(consumer);
        sendRemoveConsumerRequest(consumer);
    }

    protected void handleConsumerFoundResponse(long request, Address address) {
        final Runnable runnable = _awaitingConsumer.poll();
        // This is a representation of the server side owner running our task.
        Owner owner = new Owner(address, request);
        if (runnable == null) {
            // For some reason we don't have a runnable anymore
            // so we have to send back to the coordinator that
            // the consumer is still available.  The runnable
            // would be removed on a cancel
            sendToCoordinator(Type.CONSUMER_READY, owner.getRequestId(), 
                owner.getAddress());
        }
        else {
            final Long requestId = _requestId.get(runnable);
            owner = new Owner(address, requestId);
            _awaitingReturn.put(owner, runnable);
            // If local we pass along without serializing
            if (local_addr.equals(owner.getAddress())) {
                handleTaskSubmittedRequest(runnable, local_addr, requestId);
            }
            else {
                if (runnable instanceof DistributedFuture) {
                    Callable<?> callable = ((DistributedFuture<?>)runnable).getCallable();
                    sendRequest(owner.getAddress(), Type.RUN_SUBMITTED, 
                        requestId, callable);
                }
                else {
                    sendRequest(owner.getAddress(), Type.RUN_SUBMITTED, 
                        requestId, runnable);
                }
            }
        }
    }

    protected void handleTaskSubmittedRequest(Runnable runnable, Address source, 
                                              long requestId) {
        // We store in our map so that when that task is
        // finished so that we can send back to the owner
        // with the results
        _running.put(runnable, new Owner(source, requestId));
        // We give the task to the thread that is now waiting for it to be returned
        // If we can't offer then we have to respond back to
        // caller that we can't handle it.  They must have
        // gotten our address when we had a consumer, but
        // they went away between then and now.
        boolean received = false;
        try {
            /** 
             * We offer it a while before rejecting it.  This is required
             * in case if the _tasks.take() call isn't registered quick
             * enough after sending the Type.CONSUMER_READY message
             */
            received = _tasks.offer(runnable, 1000, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Interrupted while handing off");
        }
        
        if (!received) {
            // If we couldn't hand off the task we have to tell the client
            // and also reupdate the coordinator that our consumer is ready
            sendRequest(source, Type.RUN_REJECTED, requestId, null);
            _running.remove(runnable);
        }
    }
    
    protected void handleTaskRejectedResponse(Address source, long requestId) {
        Runnable runnable = _awaitingReturn.remove(new Owner(
            source, requestId));
        if (runnable != null) {
            _awaitingConsumer.add(runnable);
            Long taskRequestId = _requestId.get(runnable);
            if (taskRequestId != requestId) {
                log.warn("Task Request Id doesn't match in rejection");
            }
            sendToCoordinator(Type.RUN_REQUEST, taskRequestId, local_addr);
        }
        else {
            log.error("error resubmitting task for request-id: " + requestId);
        }
    }

    protected void handleValueResponse(Address source, long requestId, Object value) {
        Runnable runnable = _awaitingReturn.remove(
            new Owner(source, requestId));
        
        if (runnable != null) {
            _requestId.remove(runnable);
        }
        // We can only notify of success if it was a future
        if (runnable instanceof RunnableFuture<?>) {
            RunnableFuture<?> future = (RunnableFuture<?>)runnable;
            ExecutorNotification notifier = notifiers.remove(future);
            if (notifier != null) {
                notifier.resultReturned(value);
            }
        }
        else {
            log.warn("Runnable was not found in awaiting");
        }
    }

    protected void handleExceptionResponse(Address source, long requestId, Throwable throwable) {
        Runnable runnable = _awaitingReturn.remove(
            new Owner(source, requestId));
        
        if (runnable != null) {
            _requestId.remove(runnable);
        }
        // We can only notify of exception if it was a future
        if (runnable instanceof RunnableFuture<?>) {
            RunnableFuture<?> future = (RunnableFuture<?>)runnable;
            ExecutorNotification notifier = notifiers.remove(future);
            if (notifier != null) {
                notifier.throwableEncountered(throwable);
            }
        }
        else {
            // All we can do is log the error since their is no
            // way to return this to the user since they don't
            // have a future object.
            log.error("Runtime Error encountered from "
                    + "Cluster execute(Runnable) method",
                    throwable);
        }
    }

    protected void handleInterruptRequest(Address source, long requestId) {
        Owner owner = new Owner(source, requestId);
        Runnable runnable = removeKeyForValue(_running, owner);
        if (runnable != null) {
            Thread thread = _runnableThreads.remove(runnable);
            thread.interrupt();
        }
        else {
            if (log.isTraceEnabled())
                log.trace("Message could not be interrupted due to it already returned");
        }
    }

    protected void handleNewRunRequest(Owner sender) {
        _consumerLock.lock();
        try {
            if (!_runRequests.contains(sender)) {
                _runRequests.add(sender);
            }
        }
        finally {
            _consumerLock.unlock();
        }
    }

    protected void handleRemoveRunRequest(Owner sender) {
        _consumerLock.lock();
        try {
            _runRequests.remove(sender);
        }
        finally {
            _consumerLock.unlock();
        }
    }
    
    protected void handleNewConsumer(Owner sender) {
        _consumerLock.lock();
        try {
            if (!_consumersAvailable.contains(sender)) {
                _consumersAvailable.add(sender);
            }
        }
        finally {
            _consumerLock.unlock();
        }
    }

    protected void handleRemoveConsumer(Owner sender) {
        _consumerLock.lock();
        try {
            _consumersAvailable.remove(sender);
        }
        finally {
            _consumerLock.unlock();
        }
    }
    
    protected void sendRequest(Address dest, Type type, long requestId, Object object) {
        Request req=new Request(type, object, requestId);
        Message msg=new Message(dest, null, req);
        msg.putHeader(id, new ExecutorHeader());
        if(bypass_bundling)
            msg.setFlag(Message.DONT_BUNDLE);
        if(log.isTraceEnabled())
            log.trace("[" + local_addr + "] --> [" + (dest == null? "ALL" : dest) + "] " + req);
        try {
            down_prot.down(new Event(Event.MSG, msg));
        }
        catch(Exception ex) {
            log.error("failed sending " + type + " request: " + ex);
        }  
    }
    
    /**
     * This keeps track of all the requests we send.  This is used so that
     * the response doesn't have to send back the future but instead the counter
     * We just let this roll over
     */
    protected static final AtomicLong counter = new AtomicLong();

    protected static class Request implements Streamable {
        protected Type    type;
        protected Object  object;
        protected long   request;
        
        public Request() {
        }

        public Request(Type type, Object object, long request) {
            this.type=type;
            this.object=object;
            this.request=request;
        }

        public void writeTo(DataOutputStream out) throws IOException {
            out.writeByte(type.ordinal());
            // We can't use Util.writeObject since it's size is limited to 2^15-1
            try {
                if (object instanceof Streamable) {
                    out.writeShort(-1);
                    Util.writeGenericStreamable((Streamable)object, out);
                }
                else {
                    byte[] bytes = Util.objectToByteBuffer(object);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
            catch (IOException e) {
                throw e;
            }
            catch (Exception e) {
                throw new IOException("Exception encountered while serializing execution request", e);
            }
            out.writeLong(request);
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
            type=Type.values()[in.readByte()];
            // We can't use Util.readObject since it's size is limited to 2^15-1
            try {
                short first = in.readShort();
                if (first == -1) {
                    object = Util.readGenericStreamable(in);
                }
                else {
                    ByteBuffer bb = ByteBuffer.allocate(4);
                    bb.putShort(first);
                    bb.putShort(in.readShort());
                    
                    int size = bb.getInt(0);
                    byte[] bytes = new byte[size];
                    in.readFully(bytes, 0, size);
                    object = Util.objectFromByteBuffer(bytes);
                }
            }
            catch (IOException e) {
                throw e;
            }
            catch (Exception e) {
                throw new IOException("Exception encountered while serializing execution request", e);
            }
            request=in.readLong();
        }

        public String toString() {
            return type.name() + " [" + object + (request != -1 ? " request id: " + request : "") + "]";
        }
    }


    public static class ExecutorHeader extends Header {

        public ExecutorHeader() {
        }

        public int size() {
            return 0;
        }

        public void writeTo(DataOutputStream out) throws IOException {
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
        }
    }
    
    public static class Owner {
        protected final Address address;
        protected final long requestId;
        
        public Owner(Address address, long requestId) {
            this.address=address;
            this.requestId=requestId;
        }

        public Address getAddress() {
            return address;
        }

        public long getRequestId() {
            return requestId;
        }
        
        // @see java.lang.Object#hashCode()
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result
                    + ((address == null) ? 0 : address.hashCode());
            result = prime * result + (int) (requestId ^ (requestId >>> 32));
            return result;
        }

        // @see java.lang.Object#equals(java.lang.Object)
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            Owner other = (Owner) obj;
            if (address == null) {
                if (other.address != null) return false;
            }
            else if (!address.equals(other.address)) return false;
            if (requestId != other.requestId) return false;
            return true;
        }

        public String toString() {
            return address + "::" + requestId;
        }
    }
}

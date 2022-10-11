package org.jgroups.blocks;


import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.protocols.FRAG;
import org.jgroups.protocols.FRAG2;
import org.jgroups.protocols.TP;
import org.jgroups.stack.Protocol;
import org.jgroups.tests.ChannelTestBase;
import org.jgroups.util.*;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A collection of tests to test the RpcDispatcher.
 * 
 * NOTE on processing return values: 
 * 
 * The method RspDispatcher.callRemoteMethods(...) returns an RspList, containing one Rsp
 * object for each group member receiving the RPC call. Rsp.getValue() returns the 
 * value returned by the RPC call from the corresponding member. Rsp.getValue() may
 * contain several classes of values, depending on what happened during the call:
 * 
 * (i) a value of the expected return data type, if the RPC call completed successfully 
 * (ii) null, if the RPC call timed out before the value could be returned
 * (iii) an object of type java.lang.Throwable, if an exception (e.g. lava.lang.OutOfMemoryException) 
 * was raised during the processing of the call 
 * 
 * It is wise to check for such cases when processing RpcDispatcher calls.
 * 
 * This also applies to the return value of callRemoteMethod(...).
 * 
 * @author Bela Ban
 */
@Test(groups=Global.STACK_DEPENDENT,sequential=true)
public class RpcDispatcherTest extends ChannelTestBase {
    RpcDispatcher disp1, disp2, disp3;
    JChannel c1, c2, c3;

    // specify return values sizes which should work correctly with 64Mb heap
    final static int[] SIZES={10000, 20000, 40000, 80000, 100000, 200000, 400000, 800000,
        1000000, 2000000, 5000000};
    // specify return value sizes which may generate timeouts or OOMEs with 64Mb heap
    final static int[] HUGESIZES={10000000, 20000000};

    @BeforeMethod
    protected void setUp() throws Exception {
        c1=createChannel(true, 3);
        c1.setName("A");
        final String GROUP="RpcDispatcherTest";
        disp1=new RpcDispatcher(c1, null, null, new ServerObject(1));
        c1.connect(GROUP);

        c2=createChannel(c1);
        c2.setName("B");
        disp2=new RpcDispatcher(c2, null, null, new ServerObject(2));
        c2.connect(GROUP);

        c3=createChannel(c1);
        c3.setName("C");
        disp3=new RpcDispatcher(c3, null, null, new ServerObject(3));
        c3.connect(GROUP);

        System.out.println("c1.view=" + c1.getView() + "\nc2.view=" + c2.getView() + "\nc3.view=" + c3.getView());
        View view=c3.getView();
        assert view.size() == 3 : "view=" + view;
    }

    @AfterMethod
    protected void tearDown() throws Exception {
        disp3.stop();
        disp2.stop();
        disp1.stop();
        Util.close(c3, c2, c1);
    }

    public void testEmptyConstructor() throws Exception {
        RpcDispatcher d1=new RpcDispatcher(), d2=new RpcDispatcher();
        JChannel channel1=null, channel2=null;

        final String GROUP=getUniqueClusterName("RpcDispatcherTest");
        try {
            channel1=createChannel(true, 2);
            channel2=createChannel(channel1);
            d1.setChannel(channel1);
            d2.setChannel(channel2);
            d1.setServerObject(new ServerObject(1));
            d2.setServerObject(new ServerObject(2));
            d1.start();
            d2.start();
            channel1.connect(GROUP);
            channel2.connect(GROUP);

            Util.sleep(500);

            View view=channel2.getView();
            System.out.println("view channel 2= " + view);

            view=channel1.getView();
            System.out.println("view channel 1= " + view);

            assert view.size() == 2;
            RspList rsps=d1.callRemoteMethods(null, "foo", null, null, new RequestOptions(Request.GET_ALL, 5000));
            System.out.println("rsps:\n" + rsps);
            assert rsps.size() == 2;
            for(Rsp rsp: rsps.values()) {
                assert rsp.wasReceived();
                assert !rsp.wasSuspected();
                assert rsp.getValue() != null;
            }


            Object server_object=new Object() {
                public long foobar() {
                    return System.currentTimeMillis();
                }
            };
            d1.setServerObject(server_object);
            d2.setServerObject(server_object);

            rsps=d2.callRemoteMethods(null, "foobar", null, null, new RequestOptions(Request.GET_ALL, 5000));
            System.out.println("rsps:\n" + rsps);
            assert rsps.size() == 2;
            for(Rsp rsp: rsps.values()) {
                assert rsp.wasReceived();
                assert !rsp.wasSuspected();
                assert rsp.getValue() != null;
            }
        }
        finally {
            d2.stop();
            d1.stop();
            Util.close(channel2, channel1);
        }
    }


    /**
     * Test the response filter mechanism which can be used to filter responses received with
     * a call to RpcDispatcher.
     * 
     * The test filters requests based on the id of the server object they were received
     * from, and only accept responses from servers with id > 1. 
     * 
     * The expected behaviour is that the response from server 1 is rejected, but the responses 
     * from servers 2 and 3 are accepted.
     *
     */
    public void testResponseFilter() {
    	
    	final long timeout = 10 * 1000 ;

        RequestOptions options=new RequestOptions(Request.GET_ALL, timeout, false,
                                                  new RspFilter() {
                                                      int num=0;
                                                      public boolean isAcceptable(Object response, Address sender) {
                                                          boolean retval=((Integer)response).intValue() > 1;
                                                          if(retval)
                                                              num++;
                                                          return retval;
                                                      }

                                                      public boolean needMoreResponses() {
                                                          return num < 2;
                                                      }
                                                  });
    	
        RspList rsps=disp1.callRemoteMethods(null, "foo", null, null, options);
        System.out.println("responses are:\n" + rsps);
        assertEquals("there should be three response values", 3, rsps.size());
        assertEquals("number of responses received should be 2", 2, rsps.numReceived());
    }


    public void testFuture() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{1000L}, new Class[]{long.class});
        Future<RspList> future;
        future=disp1.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(Request.GET_ALL, 5000L, false, null));
        assert !future.isDone();
        assert !future.isCancelled();
        try {
            future.get(300, TimeUnit.MILLISECONDS);
            assert false : "we should not get here, get(300) should have thrown a TimeoutException";
        }
        catch(TimeoutException e) {
            System.out.println("got TimeoutException - as expected");
        }
        
        assert !future.isDone();

        RspList result=future.get(6000L, TimeUnit.MILLISECONDS);
        System.out.println("result:\n" + result);
        assert result != null;
        assert result.size() == 3;
        assert future.isDone();
    }


    public void testNotifyingFuture() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{1000L}, new Class[]{long.class});
        NotifyingFuture<RspList> future;
        MyFutureListener<RspList> listener=new MyFutureListener<RspList>();
        future=disp1.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(Request.GET_ALL, 5000L, false, null));
        future.setListener(listener);
        assert !future.isDone();
        assert !future.isCancelled();
        assert !listener.isDone();
        Util.sleep(2000);
        assert listener.isDone();
        RspList result=future.get(1L, TimeUnit.MILLISECONDS);
        System.out.println("result:\n" + result);
        assert result != null;
        assert result.size() == 3;
        assert future.isDone();
    }

    public void testNotifyingFutureWithDelayedListener() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{1000L}, new Class[]{long.class});
        NotifyingFuture<RspList> future;
        MyFutureListener<RspList> listener=new MyFutureListener<RspList>();
        future=disp1.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(Request.GET_ALL, 5000L, false, null));
        assert !future.isDone();
        assert !future.isCancelled();

        Util.sleep(2000);
        future.setListener(listener);
        assert listener.isDone();
        RspList result=future.get(1L, TimeUnit.MILLISECONDS);
        System.out.println("result:\n" + result);
        assert result != null;
        assert result.size() == 3;
        assert future.isDone();
    }


    public void testMultipleFutures() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{100L}, new Class[]{long.class});
        List<Future<RspList>> futures=new ArrayList<Future<RspList>>();
        long target=System.currentTimeMillis() + 30000L;

        Future<RspList> future;
        RequestOptions options=new RequestOptions(Request.GET_ALL, 30000L, false, null);
        for(int i=0; i < 10; i++) {
            future=disp1.callRemoteMethodsWithFuture(null, sleep, options);
            futures.add(future);
        }

        List<Future<RspList>> rsps=new ArrayList<Future<RspList>>();
        while(!futures.isEmpty() && System.currentTimeMillis() < target) {
            for(Iterator<Future<RspList>> it=futures.iterator(); it.hasNext();) {
                future=it.next();
                if(future.isDone()) {
                    it.remove();
                    rsps.add(future);
                }
            }
            System.out.println("pending responses: " + futures.size());
            Util.sleep(200);
        }
        System.out.println("\n" + rsps.size() + " responses:\n");
        for(Future<RspList> tmp: rsps) {
            System.out.println(tmp);
        }
    }

    public void testMultipleNotifyingFutures() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{100L}, new Class[]{long.class});
        List<MyFutureListener> listeners=new ArrayList<MyFutureListener>();
        RequestOptions options=new RequestOptions(Request.GET_ALL, 30000L, false, null);
        for(int i=0; i < 10; i++) {
            MyFutureListener<RspList> listener=new MyFutureListener<RspList>();
            listeners.add(listener);
            disp1.callRemoteMethodsWithFuture(null, sleep, options).setListener(listener);
        }

        Util.sleep(1000);
        for(int i=0; i < 10; i++) {
            boolean all_done=true;
            for(MyFutureListener listener: listeners) {
                boolean done=listener.isDone();
                System.out.print(done? "+ " : "- ");
                if(!listener.isDone())
                    all_done=false;
            }
            if(all_done)
                break;
            Util.sleep(500);
            System.out.println("");
        }
        
        for(MyFutureListener listener: listeners) {
            assert listener.isDone();
        }

    }




    public void testFutureCancel() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{1000L}, new Class[]{long.class});
        Future<RspList> future;
        future=disp1.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(Request.GET_ALL, 5000L));
        assert !future.isDone();
        assert !future.isCancelled();
        future.cancel(true);
        assert future.isDone();
        assert future.isCancelled();

        future=disp1.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(Request.GET_ALL, 0));
        assert !future.isDone();
        assert !future.isCancelled();
        future.cancel(true);
        assert future.isDone();
        assert future.isCancelled();
    }


    /**
     * Test the ability of RpcDispatcher to handle large argument and return values
     * with multicast RPC calls.
     * 
     * The test sends requests for return values (byte arrays) having increasing sizes,
     * which increase the processing time for requests as well as the amount of memory
     * required to process requests.
     * 
     * The expected behaviour is that all RPC requests complete successfully.
     *
     */
    public void testLargeReturnValue() {
        setProps(c1, c2, c3);
        for(int i=0; i < SIZES.length; i++) {
            _testLargeValue(SIZES[i]);
        }
    }
    
    /**
     * Test the ability of RpcDispatcher to handle huge argument and return values
     * with multicast RPC calls.
     * 
     * The test sends requests for return values (byte arrays) having increasing sizes,
     * which increase the processing time for requests as well as the amount of memory
     * required to process requests.
     * 
     * The expected behaviour is that RPC requests either timeout or trigger out of 
     * memory exceptions. Huge return values extend the processing time required; but
     * the length of time depends upon the speed of the machine the test runs on. 
     *
     */
    /*@Test(groups="first")
    public void testHugeReturnValue() {
        setProps(c1, c2, c3);
        for(int i=0; i < HUGESIZES.length; i++) {
            _testHugeValue(HUGESIZES[i]);
        }
    }*/
    

    /**
     * Tests a method call to {A,B,C} where C left *before* the call. http://jira.jboss.com/jira/browse/JGRP-620
     */
    public void testMethodInvocationToNonExistingMembers() {
    	
    	final int timeout = 5 * 1000 ;
    	
    	// get the current membership, as seen by C
        View view=c3.getView();
        Vector<Address> members=view.getMembers();
        System.out.println("list is " + members);

        // cause C to leave the group and close its channel
        System.out.println("closing c3");
        c3.close();

        Util.sleep(1000);
        
        // make an RPC call using C's now outdated view of membership
        System.out.println("calling method foo() in " + members + " (view=" + c2.getView() + ")");
        RspList rsps=disp1.callRemoteMethods(members, "foo", null, null, new RequestOptions(Request.GET_ALL, timeout));
        
        // all responses 
        System.out.println("responses:\n" + rsps);
        for(Map.Entry<Address,Rsp> entry: rsps.entrySet()) {
            Rsp rsp=entry.getValue();
            assertTrue("response from " + entry.getKey() + " was not received", rsp.wasReceived());
            assertFalse(rsp.wasSuspected());
        }
    }


    /**
     * Test the ability of RpcDispatcher to handle large argument and return values
     * with unicast RPC calls.
     * 
     * The test sends requests for return values (byte arrays) having increasing sizes,
     * which increase the processing time for requests as well as the amount of memory
     * required to process requests.
     * 
     * The expected behaviour is that all RPC requests complete successfully.
     *
     */
    public void testLargeReturnValueUnicastCall() throws Throwable {
        setProps(c1, c2, c3);
        for(int i=0; i < SIZES.length; i++) {
            _testLargeValueUnicastCall(c1.getAddress(), SIZES[i]);
        }
    }


    private static void setProps(JChannel... channels) {
        for(JChannel ch: channels) {
            Protocol prot=ch.getProtocolStack().findProtocol("FRAG2");
            if(prot != null) {
                ((FRAG2)prot).setFragSize(12000);
            }
            prot=ch.getProtocolStack().findProtocol("FRAG");
            if(prot != null) {
                ((FRAG)prot).setFragSize(12000);
            }

            prot=ch.getProtocolStack().getTransport();
            if(prot != null)
                ((TP)prot).setMaxBundleSize(14000);
        }
    }

    /**
     * Helper method to perform a RPC call on server method "returnValue(int size)" for 
     * all group members.
     * 
     * The method checks that each returned value is non-null and has the correct size. 
     *    
     */
    void _testLargeValue(int size) {
    	
    	// 20 second timeout 
    	final long timeout = 20 * 1000 ;
    		
        System.out.println("\ntesting with " + size + " bytes");
        RspList rsps=disp1.callRemoteMethods(null, "largeReturnValue", new Object[]{size}, new Class[]{int.class},
                                             new RequestOptions(Request.GET_ALL, timeout));
        System.out.println("rsps:");
        assert rsps.size() == 3 : "there should be three responses to the RPC call but only " + rsps.size() +
                " were received: " + rsps;
        
        for(Map.Entry<Address,Rsp> entry: rsps.entrySet()) {
        	
        	// its possible that an exception was raised in processing
        	Object obj = entry.getValue().getValue() ;
        	
        	// this should not happen
        	assert !(obj instanceof Throwable) : "exception was raised in processing reasonably sized argument";
        	
            byte[] val=(byte[]) obj;
            assert val != null;
            System.out.println(val.length + " bytes from " + entry.getValue().getSender());
            assert val.length == size : "return value does not match required size";
        }
    }
    
    /**
     * Helper method to perform a RPC call on server method "returnValue(int size)" for 
     * all group members.
     * 
     * This method need to take into account that RPC calls can timeout with huge values,
     * and they can also trigger OOMEs. But if we are lucky, they can also return
     * reasonable values. 
     * 
     */
    void _testHugeValue(int size) {
    	
    	// 20 second timeout 
    	final long timeout = 20 * 1000 ;
    	
        System.out.println("\ntesting with " + size + " bytes");
        RspList rsps=disp1.callRemoteMethods(null, "largeReturnValue", new Object[]{size}, new Class[]{int.class},
                                             new RequestOptions(Request.GET_ALL, timeout));
        System.out.println("rsps:");
        assert rsps != null;
        assert rsps.size() == 3 : "there should be three responses to the RPC call but only " + rsps.size() +
                " were received: " + rsps;

        // in checking the return values, we need to take account of timeouts (i.e. when
        // a null value is returned) and exceptions 
        for(Map.Entry<Address,Rsp> entry: rsps.entrySet()) {

        	Object obj = entry.getValue().getValue() ;

        	// its possible that an exception was raised
        	if (obj instanceof java.lang.Throwable) {
        		Throwable t = (Throwable) obj ;
        		
        		System.out.println(t.toString() + " exception was raised processing argument from " +
        							entry.getValue().getSender() + " -this is expected") ;
        		continue ;
        	}        	
        	
        	// its possible that the request timed out before the serve could reply 
        	if (obj == null) {
        		System.out.println("request timed out processing argument from " + 
        							entry.getValue().getSender() + " - this is expected") ;
        		continue ;       	
        	}
        	
        	// if we reach here, we sould have a reasobable value
        	byte[] val=(byte[]) obj;
            System.out.println(val.length + " bytes from " + entry.getValue().getSender());
            assert val.length == size : "return value does not match required size";
        }
    }

    /**
     * Helper method to perform a RPC call on server method "returnValue(int size)" for 
     * an individual group member. 
     * 
     * The method checks that the returned value is non-null and has the correct size. 
     * 
     * @param dst the group member
     * @param size the size of the byte array to be returned
     * @throws Throwable
     */
    void _testLargeValueUnicastCall(Address dst, int size) throws Throwable {
    	
    	// 20 second timeout
    	final long timeout = 20 * 1000 ;
    	
        System.out.println("\ntesting unicast call with " + size + " bytes");
        assertNotNull(dst);
        
        Object retval=disp1.callRemoteMethod(dst, "largeReturnValue", new Object[]{size}, new Class[]{int.class},
                                             new RequestOptions(Request.GET_ALL, timeout));

    	// it's possible that an exception was raised
        if (retval instanceof java.lang.Throwable) {
        	throw (Throwable)retval;
        }
        
        byte[] val=(byte[])retval;
        
        // check value is not null, otherwise fail the test
        assertNotNull("return value should be non-null", val);
        System.out.println("rsp: " + val.length + " bytes");
        
        // returned value should have requested size
        assertEquals("return value does not match requested size", size, val.length);
    }

    /**
     * This class serves as a server obect to turn requests into replies.
     * It is initialised with an integer id value.
     * 
     * It implements two functions:
     * function foo() returns the id of the server
     * function largeReturnValue(int size) returns a byte array of size 'size'
     *  
     */
    private static class ServerObject {
        int i;
        public ServerObject(int i) {
            this.i=i;
        }
        public int foo() {return i;}
        
        public static long sleep(long timeout) {
            // System.out.println("sleep()");
            long start=System.currentTimeMillis();
            Util.sleep(timeout);
            return System.currentTimeMillis() - start;
        }


        public static byte[] largeReturnValue(int size) {
            return new byte[size];
        }
    }

    private static class MyFutureListener<T> implements FutureListener<T> {
        private boolean done;

        public void futureDone(Future<T> future) {
            done=true;
        }

        public boolean isDone() {return done;}
    }


}
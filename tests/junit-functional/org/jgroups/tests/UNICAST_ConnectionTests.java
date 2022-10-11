package org.jgroups.tests;

import org.jgroups.*;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.protocols.UNICAST;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.ArrayList;

/**
 * Tests unilateral closings of UNICAST connections. The test scenarios are described in doc/design.UNICAST.new.txt.
 * @author Bela Ban
 */
@Test(groups=Global.FUNCTIONAL,sequential=true)
public class UNICAST_ConnectionTests {
    private JChannel a, b;
    private Address a_addr, b_addr;
    private MyReceiver r1, r2;
    private UNICAST u1, u2;
    private static final String props="SHARED_LOOPBACK:UNICAST";
    private static final String CLUSTER="UNICAST_ConnectionTests";


    @BeforeMethod
    void start() throws Exception {
        r1=new MyReceiver("A");
        r2=new MyReceiver("B");
        a=new JChannel(props);
        a.connect(CLUSTER);
        a_addr=a.getAddress();
        a.setReceiver(r1);
        u1=(UNICAST)a.getProtocolStack().findProtocol(UNICAST.class);
        b=new JChannel(props);
        b.connect(CLUSTER);
        b_addr=b.getAddress();
        b.setReceiver(r2);
        u2=(UNICAST)b.getProtocolStack().findProtocol(UNICAST.class);
        System.out.println("A=" + a_addr + ", B=" + b_addr);
    }


    @AfterMethod void stop() {Util.close(b, a);}


    /**
     * Tests cases #1 and #2 of UNICAST.new.txt
     * @throws Exception
     */
    public void testRegularMessageReception() throws Exception {
        sendAndCheck(a, b_addr, 100, r2);
        sendAndCheck(b, a_addr,  50, r1);
    }


    /**
     * Tests case #3 of UNICAST.new.txt
     */
    public void testBothChannelsClosing() throws Exception {
        sendToEachOtherAndCheck(10);
        
        // now close the connections to each other
        System.out.println("==== Closing the connections on both sides");
        u1.removeConnection(b_addr);
        u2.removeConnection(a_addr);
        r1.clear(); r2.clear();

        // causes new connection establishment
        sendToEachOtherAndCheck(10);
    }


    /**
     * Scenario #4 (A closes the connection unilaterally (B keeps it open), then reopens it and sends messages)
     */
    public void testAClosingUnilaterally() throws Exception {
        sendToEachOtherAndCheck(10);

        // now close connection on A unilaterally
        System.out.println("==== Closing the connection on A");
        u1.removeConnection(b_addr);

        // then send messages from A to B
        sendAndCheck(a, b_addr, 10, r2);
    }

    /**
     * Scenario #5 (B closes the connection unilaterally (A keeps it open), then A sends messages to B)
     */
    public void testBClosingUnilaterally() throws Exception {
        sendToEachOtherAndCheck(10);

        // now close connection on A unilaterally
        System.out.println("==== Closing the connection on B");
        u2.removeConnection(a_addr);

        // then send messages from A to B
        sendAndCheck(a, b_addr, 10, r2);
    }


    /**
     * Scenario #6 (A closes the connection unilaterally (B keeps it open), then reopens it and sends messages,
     * but loses the first message
     */
    public void testAClosingUnilaterallyButLosingFirstMessage() throws Exception {
        sendToEachOtherAndCheck(10);

        // now close connection on A unilaterally
        System.out.println("==== Closing the connection on A");
        u1.removeConnection(b_addr);

        // add a Drop protocol to drop the first unicast message
        Drop drop=new Drop(true);
        a.getProtocolStack().insertProtocol(drop, ProtocolStack.BELOW, UNICAST.class);

        // then send messages from A to B
        sendAndCheck(a, b_addr, 10, r2);
    }



    /**
     * Send num unicasts on both channels and verify the other end received them
     * @param num
     * @throws Exception
     */
    private void sendToEachOtherAndCheck(int num) throws Exception {
        for(int i=1; i <= num; i++) {
            a.send(b_addr, null, "m" + i);
            b.send(a_addr, null, "m" + i);
        }
        List<Message> l1=r1.getMessages();
        List<Message> l2=r2.getMessages();
        for(int i=0; i < 10; i++) {
            if(l1.size()  == num && l2.size() == num)
                break;
            Util.sleep(500);
        }
        System.out.println("l1 = " + print(l1));
        System.out.println("l2 = " + print(l2));
        assert l1.size() == num;
        assert l2.size() == num;
    }

    private static void sendAndCheck(JChannel channel, Address dest, int num, MyReceiver receiver) throws Exception {
        receiver.clear();
        for(int i=1; i <= num; i++)
            channel.send(dest, null, "m" + i);
        List<Message> list=receiver.getMessages();
        for(int i=0; i < 10; i++) {
            if(list.size() == num)
                break;
            Util.sleep(500);
        }
        System.out.println("list = " + print(list));
        int size=list.size();
        assert size == num : "list has " + size + " elements";
    }


    private static String print(List<Message> list) {
        List<String> tmp=new ArrayList<String>(list.size());
        for(Message msg: list)
            tmp.add((String)msg.getObject());
        return Util.printListWithDelimiter(tmp, " ");
    }


    private static class MyReceiver extends ReceiverAdapter {
        final String name;
        final List<Message> msgs=new ArrayList<Message>(20);

        public MyReceiver(String name) {
            this.name=name;
        }

        public void receive(Message msg) {
            msgs.add(msg);
        }

        public List<Message> getMessages() { return msgs; }
        public void clear() {msgs.clear();}
        public int size() {return msgs.size();}

        public String toString() {
            return name;
        }
    }

    private static class Drop extends Protocol {
        private volatile boolean drop_next=false;

        private Drop(boolean drop_next) {
            this.drop_next=drop_next;
        }

        public String getName() {
            return "Drop";
        }

        public void dropNext() {
            drop_next=true;
        }

        public Object down(Event evt) {
            if(drop_next && evt.getType() == Event.MSG) {
                drop_next=false;
                return null;
            }
            return super.down(evt);
        }
    }
}

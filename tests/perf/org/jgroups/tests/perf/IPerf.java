package org.jgroups.tests.perf;

import org.jgroups.*;
import org.jgroups.util.Util;
import org.jgroups.util.Tuple;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.net.InetAddress;
import java.text.NumberFormat;

/**
 * Tests sending large messages from one sender to multiple receivers. The following messages are exchanged:
 * <pre>
 * | START (1 byte) |
 * | DATA (1 byte) | size (int, 4 bytes) | byte[] buf |
 * | STOP (1 byte) |
 * | RESULT (1 byte) | total time (long, 8 bytes) | total_bytes (long, 8 bytes)|
 * | REGISTER (1 byte) |
 *
 * </pre>
 * @author Bela Ban
 */
public class IPerf implements Receiver {
    private final Configuration config;
    private final ConcurrentMap<Object,Entry> receiver_table=new ConcurrentHashMap<Object,Entry>();
    private Transport transport=null;
    private ResultSet results=null;
    private final Set<Object> members=new HashSet<Object>();
    private final static NumberFormat f;


    static {
        f=NumberFormat.getNumberInstance();
        f.setGroupingUsed(false);
        f.setMaximumFractionDigits(2);
    }


    public IPerf(Configuration config) {
        this.config=config;
    }

    public void start() throws Exception {
        transport=(Transport)Class.forName(config.getTransport()).newInstance();
        transport.create(config);
        transport.setReceiver(this);
        transport.start();

        members.add(transport.getLocalAddress());
        byte[] buf=createRegisterMessage();
        transport.send(null, buf, false);


        if(config.isSender()) {
            send();
            transport.stop();
            transport.destroy();
        }
        else {
            System.out.println("Transport " + transport.getClass().getName() + " started at " + new Date());
            System.out.println("Listening on " + transport.getLocalAddress());
        }
    }


    public void receive(Object sender, byte[] payload) {
        ByteBuffer buf=ByteBuffer.wrap(payload);
        byte b=buf.get();
        Type type=Type.getType(b);
        switch(type) {
            case START:
                receiver_table.remove(sender);
                receiver_table.putIfAbsent(sender, new Entry());
                break;
            case DATA:
                Entry entry=receiver_table.get(sender);
                if(entry == null) {
                    err("entry for " + sender + " not found");
                    return;
                }
                int length=buf.getInt();
                entry.total_bytes+=length;
                break;
            case STOP:
                entry=receiver_table.get(sender);
                if(entry == null) {
                    err("entry for " + sender + " not found");
                    return;
                }
                if(entry.stop_time == 0) {
                    entry.stop_time=System.currentTimeMillis();
                }
                System.out.println("result for " + sender + ": " + entry);
                sendResult(sender, entry);
                break;
            case RESULT:
                long total_time=buf.getLong(), total_bytes=buf.getLong();
                results.add(sender, total_time, total_bytes);
                break;
            case REGISTER:
                members.add(sender);
                break;
        }
    }




    private void send() throws Exception {
        int size=config.getSize();
        long time=config.getTime();
        int chunk_size=config.getChunkSize();

        results=new ResultSet(members, size);

        byte[] buf=createStartMessage();
        transport.send(null, buf, false);

        if(time > 0) {
            ByteBuffer buffer=createDataBuffer(chunk_size);
            buffer.put(Type.DATA.getByte());
            buffer.putInt(chunk_size);
            buf=buffer.array();

            long end_time=System.currentTimeMillis() + time;
            while(System.currentTimeMillis() < end_time) {
                transport.send(null, buf, false);
            }
        }
        else {
            int sent=0;
            int to_send, remaining;

            while(sent < size) {
                remaining=size - sent;
                to_send=Math.min(remaining, chunk_size);
                buf=createDataMessage(to_send);
                transport.send(null, buf, false);
                sent+=to_send;
            }
        }

        buf=createStopMessage();
        transport.send(null, buf, false);

        boolean rc=results.block(30000L);
        if(rc)
            log("got all results");
        else
            err("didnt get all results");
        System.out.println("\nResults:\n" + results);
        results.reset();
    }

    
    private static byte[] createRegisterMessage() {
        return createMessage(Type.REGISTER);
    }


    private void sendResult(Object destination, Entry entry) {
        ByteBuffer buf=ByteBuffer.allocate(Global.BYTE_SIZE + Global.LONG_SIZE * 2);
        buf.put(Type.RESULT.getByte());
        buf.putLong(entry.stop_time - entry.start_time);
        buf.putLong(entry.total_bytes);
        try {
            transport.send(destination, buf.array(), false);
        }
        catch(Exception e) {
            err(e.toString());
        }
    }


    private static byte[] createStartMessage() {
        return createMessage(Type.START);
    }


    private static byte[] createDataMessage(int length) {
        ByteBuffer buf=ByteBuffer.allocate(Global.BYTE_SIZE + length + Global.INT_SIZE);
        buf.put(Type.DATA.getByte());
        buf.putInt(length);
        return buf.array();
    }

    private static ByteBuffer createDataBuffer(int length) {
        ByteBuffer buf=ByteBuffer.allocate(Global.BYTE_SIZE + length + Global.INT_SIZE);
        buf.put(Type.DATA.getByte());
        buf.putInt(length);
        return buf;
    }


    private static byte[] createStopMessage() {
        return createMessage(Type.STOP);
    }


    private static byte[] createMessage(Type type) {
        ByteBuffer buf=ByteBuffer.allocate(Global.BYTE_SIZE);
        buf.put(type.getByte());
        return buf.array();
    }


    public static void main(String[] args) throws Exception {
        Configuration config=new Configuration();

        List<String> unused_args=new ArrayList<String>(args.length);

        for(int i=0; i < args.length; i++) {
            String tmp=args[i];
            if(tmp.equalsIgnoreCase("-sender")) {
                config.setSender(true);
                continue;
            }
            if(tmp.equalsIgnoreCase("-size")) {
                config.setSize(Integer.parseInt(args[++i]));
                continue;
            }
            if(tmp.equals("-time")) {
                config.setTime(Long.parseLong(args[++i]));
                continue;
            }
            if(tmp.equals("-chunk_size")) {
                config.setChunkSize(Integer.parseInt(args[++i]));
                continue;
            }
            if(tmp.equals("-transport")) {
                config.setTransport(args[++i]);
                continue;
            }
            if(tmp.equals("-bind_addr")) {
                config.setBindAddress(InetAddress.getByName(args[++i]));
                continue;
            }
            if(tmp.equals("-h") || tmp.equals("-help")) {
                help(config.getTransport());
                return;
            }
            unused_args.add(tmp);
        }

        if(!unused_args.isEmpty()) {
            String[] tmp=new String[unused_args.size()];
            for(int i=0; i < unused_args.size(); i++)
                tmp[i]=unused_args.get(i);
            config.setTransportArgs(tmp);
        }

        new IPerf(config).start();
    }

    static void help(String transport) {
        StringBuilder sb=new StringBuilder();
        sb.append("IPerf [-sender] [-bind_addr <addr>] [-transport <class name>] [-size <bytes> | -time <ms>] [-chunk_size <bytes>]");
        try {
            Transport tp=(Transport)Class.forName(transport).newInstance();
            String tmp=tp.help();
            if(tmp != null && tmp.length() > 0)
                sb.append("\nTransport specific options for " + tp.getClass().getName() + ":\n" + tp.help());
        }
        catch(Exception e) {
            e.printStackTrace();
        }
        System.out.println(sb);
    }

    private static void log(String msg) {
        System.out.println(msg);
    }

    private static void err(String msg) {
        System.err.println(msg);
    }



    public static enum Type {
        START(1),
        DATA(2),
        STOP(3),
        RESULT(4),
        REGISTER(5);

        final byte b;

        Type(int i) {
            b=(byte)i;
        }

        public byte getByte() {
            return b;
        }

        public static Type getType(byte input) {
            switch(input) {
                case 1: return START;
                case 2: return DATA;
                case 3: return STOP;
                case 4: return RESULT;
                case 5: return REGISTER;
            }
            throw new IllegalArgumentException("type " + input + " is not valid");
        }
    }

    private static class Entry {
        private final long start_time;
        private long stop_time=0;
        private long total_bytes=0;

        public Entry() {
            this.start_time=System.currentTimeMillis();
        }

        public String toString() {
            double throughput=total_bytes / ((stop_time - start_time) / 1000.0);
            return stop_time - start_time + " ms for " + Util.printBytes(total_bytes) +
                    " (" + Util.printBytes(throughput) + " / sec)";
        }
    }

    private static class ResultSet {
        private final Set<Object> not_heard_from;
        private final ConcurrentMap<Object, Tuple<Long,Long>> results=new ConcurrentHashMap<Object,Tuple<Long,Long>>();
        private final Lock lock=new ReentrantLock();
        private final Condition cond=lock.newCondition();
        private long  expected_bytes=0;
        

        public ResultSet(Collection<Object> not_heard_from, long expected_bytes) {
            this.not_heard_from=new HashSet<Object>(not_heard_from); // make a copy
            this.expected_bytes=expected_bytes;

        }

        public boolean add(Object sender, long time, long total_bytes) {
            results.putIfAbsent(sender, new Tuple<Long,Long>(time, total_bytes));
            lock.lock();
            try {
                if(not_heard_from.remove(sender))
                    cond.signalAll();
                return not_heard_from.isEmpty();
            }
            finally {
                lock.unlock();
            }
        }

        public boolean block(long timeout) {
            long target=System.currentTimeMillis() + timeout;
            long curr_time;
            lock.lock();
            try {

                while((curr_time=System.currentTimeMillis()) < target && !not_heard_from.isEmpty()) {
                    long wait_time=target - curr_time;
                    try {
                        cond.await(wait_time, TimeUnit.MILLISECONDS);
                    }
                    catch(InterruptedException e) {
                        ;
                    }
                }
                return not_heard_from.isEmpty();
            }
            finally {
                lock.unlock();
            }
        }

        public int size() {
            return results.size();
        }

        public void reset() {
            lock.lock();
            try {
                not_heard_from.clear();
                results.clear();
                cond.signalAll();
            }
            finally {
                lock.unlock();;
            }
        }

        public String toString() {
            StringBuilder sb=new StringBuilder();
            for(Map.Entry<Object,Tuple<Long,Long>> entry: results.entrySet()) {
                Tuple<Long, Long> val=entry.getValue();
                sb.append(entry.getKey()).append(" time=" + val.getVal1() + " ms for " + Util.printBytes(val.getVal2()));
                if(expected_bytes > 0) {
                    long total_received_bytes=val.getVal2();
                    long missing=expected_bytes - total_received_bytes;
                    double loss_rate=100.0 / expected_bytes * missing;
                    double throughput=val.getVal2() / (val.getVal1() / 1000.0);
                    sb.append(" (" + Util.printBytes(throughput) + " / sec, loss rate=" + f.format(loss_rate) + "%)");
                }
                sb.append("\n");
            }
            if(!not_heard_from.isEmpty())
                sb.append("(not heard from " + not_heard_from + ")\n");
            return sb.toString();
        }
    }
    


}

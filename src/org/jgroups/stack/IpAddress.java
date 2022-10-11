
package org.jgroups.stack;

import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.PhysicalAddress;
import org.jgroups.util.Util;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.Inet6Address;


/**
 * Network-dependent address (Internet). Generated by the bottommost layer of the protocol
 * stack (UDP). Contains an InetAddress and port.
 * @author Bela Ban
 */
public class IpAddress implements PhysicalAddress {

	private static final long serialVersionUID = 2592301708270771474L;

    private InetAddress             ip_addr=null;
    private int                     port=0;
    private byte[]                  additional_data;
    protected static final Log      log=LogFactory.getLog(IpAddress.class);
    static boolean                  resolve_dns=false;
    transient int                   size=-1;

    static {
        /* Trying to get value of resolve_dns. PropertyPermission not granted if
        * running in an untrusted environment  with JNLP */
        try {
            String tmp=Util.getProperty(new String[]{Global.RESOLVE_DNS, "resolve.dns"}, null, null, false, "false"); 
            resolve_dns=Boolean.valueOf(tmp).booleanValue();
        }
        catch (SecurityException ex){
            resolve_dns=false;
        }
    }



    // Used only by Externalization
    public IpAddress() {
    }

    public IpAddress(String i, int p) throws UnknownHostException {
        port=p;
        ip_addr=InetAddress.getByName(i);
    }



    public IpAddress(InetAddress i, int p) {
        ip_addr=i; port=p;
        if(this.ip_addr == null)
            setAddressToLocalHost();
    }


    private void setAddressToLocalHost() {
        try {
            ip_addr=InetAddress.getLocalHost();  // get first NIC found (on multi-homed systems)
            // size=size();
        }
        catch(Exception e) {
            if(log.isWarnEnabled()) log.warn("exception: " + e);
        }
    }

    public IpAddress(int port) {
        this(port, true);
    }

    public IpAddress(int port, boolean set_default_host) {
        this.port=port;
        if(set_default_host)
            setAddressToLocalHost();
    }


    public IpAddress(InetSocketAddress sock_addr) {
        port=sock_addr.getPort();
        ip_addr=sock_addr.getAddress();
    }



    public final InetAddress  getIpAddress()               {return ip_addr;}
    public final int          getPort()                    {return port;}

    public final boolean      isMulticastAddress() {
        return ip_addr != null && ip_addr.isMulticastAddress();
    }

    /**
     * Returns the additional_data.
     * @return byte[]
     */
    public final byte[] getAdditionalData() {
        return additional_data;
    }

    /**
     * Sets the additional_data.
     * @param additional_data The additional_data to set
     */
    public final void setAdditionalData(byte[] additional_data) {
        this.additional_data=additional_data;
        size=-1;  // changed May 13 2006 bela (suggested by Bruce Schuchardt)
        size=size();
    }


    /**
     * Establishes an order between 2 addresses. Assumes other contains non-null IpAddress.
     * Excludes channel_name from comparison.
     * @return 0 for equality, value less than 0 if smaller, greater than 0 if greater.
     * @deprecated Use {@link #compareTo(org.jgroups.Address)} instead
     */
    public final int compare(IpAddress other) {
        return compareTo(other);
    }


    /**
     * implements the java.lang.Comparable interface
     * @see java.lang.Comparable
     * @param o - the Object to be compared
     * @return a negative integer, zero, or a positive integer as this object is less than,
     *         equal to, or greater than the specified object.
     * @exception java.lang.ClassCastException - if the specified object's type prevents it
     *            from being compared to this Object.
     */
    public final int compareTo(Address o) {
        int   h1, h2, rc; // added Nov 7 2005, makes sense with canonical addresses

        if(this == o) return 0;
        if(!(o instanceof IpAddress))
            throw new ClassCastException("comparison between different classes: the other object is " +
                    (o != null? o.getClass() : o));
        IpAddress other = (IpAddress) o;
        if(ip_addr == null)
            if (other.ip_addr == null) return port < other.port ? -1 : (port > other.port ? 1 : 0);
            else return -1;

        h1=ip_addr.hashCode();
        h2=other.ip_addr.hashCode();
        rc=h1 < h2? -1 : h1 > h2? 1 : 0;
        return rc != 0 ? rc : port < other.port ? -1 : (port > other.port ? 1 : 0);
    }


    /**
     * This method compares both addresses' dotted-decimal notation in string format if the hashcode and ports are
     * identical. Ca 30% slower than {@link #compareTo(Object)} if used excessively.
     * @param o
     * @return
     * @deprecated Use {@link #compareTo(org.jgroups.Address)} instead
     */
    public final int compareToUnique(Object o) {
        int   h1, h2, rc; // added Nov 7 2005, makes sense with canonical addresses

        if(this == o) return 0;
        if ((o == null) || !(o instanceof IpAddress))
            throw new ClassCastException("comparison between different classes: the other object is " +
                    (o != null? o.getClass() : o));
        IpAddress other = (IpAddress) o;
        if(ip_addr == null)
            if (other.ip_addr == null) return port < other.port ? -1 : (port > other.port ? 1 : 0);
            else return -1;

        h1=ip_addr.hashCode();
        h2=other.ip_addr.hashCode();
        rc=h1 < h2? -1 : h1 > h2? 1 : 0;

        if(rc != 0)
            return rc;

        rc=port < other.port ? -1 : (port > other.port ? 1 : 0);

        if(rc != 0)
            return rc;

        // here we have the same addresses hash codes and ports, now let's compare the dotted-decimal addresses

        String addr1=ip_addr.getHostAddress(), addr2=other.ip_addr.getHostAddress();
        return addr1.compareTo(addr2);
    }



    public final boolean equals(Object obj) {
        if(this == obj) return true; // added Nov 7 2005, makes sense with canonical addresses

        if(!(obj instanceof IpAddress))
            return false;
        IpAddress other=(IpAddress)obj;
        boolean sameIP;
        if(this.ip_addr != null)
            sameIP=this.ip_addr.equals(other.ip_addr);
        else
            sameIP=(other.ip_addr == null);
        return sameIP && (this.port == other.port);
    }




    public final int hashCode() {
        return ip_addr != null ? ip_addr.hashCode() + port : port;
    }




    public String toString() {
        StringBuilder sb=new StringBuilder();

        if(ip_addr == null)
            sb.append("<null>");
        else {
            if(ip_addr.isMulticastAddress())
                sb.append(ip_addr.getHostAddress());
            else {
                String host_name;
                if(resolve_dns) {
                    host_name=ip_addr.getHostName();
                }
                else {
                    host_name=ip_addr.getHostAddress();
                }
                sb.append(host_name);
            }
        }
        sb.append(":").append(port);
        return sb.toString();
    }



    public void writeExternal(ObjectOutput out) throws IOException {
        if(ip_addr != null) {
            byte[] address=ip_addr.getAddress();
            out.writeByte(address.length); // 1 byte
            out.write(address, 0, address.length);
        }
        else {
            out.writeByte(0);
        }
        out.writeShort(port);
        if(additional_data != null) {
            out.writeBoolean(true);
            out.writeShort(additional_data.length);
            out.write(additional_data, 0, additional_data.length);
        }
        else
            out.writeBoolean(false);
    }




    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        int len=in.readByte();
        if(len > 0) {
            //read the four bytes
            byte[] a = new byte[len];
            //in theory readFully(byte[]) should be faster
            //than read(byte[]) since latter reads
            // 4 bytes one at a time
            in.readFully(a);
            //look up an instance in the cache
            this.ip_addr=InetAddress.getByAddress(a);
        }
        //then read the port
        port=in.readUnsignedShort();

        if(in.readBoolean() == false)
            return;
        len=in.readShort();
        if(len > 0) {
            additional_data=new byte[len];
            in.readFully(additional_data, 0, additional_data.length);
        }
    }

    public void writeTo(DataOutputStream out) throws IOException {
        if(ip_addr != null) {
            byte[] address=ip_addr.getAddress();  // 4 bytes (IPv4) or 16 bytes (IPv6)
            out.writeByte(address.length); // 1 byte
            out.write(address, 0, address.length);
            if(ip_addr instanceof Inet6Address)
                out.writeInt(((Inet6Address)ip_addr).getScopeId());
        }
        else {
            out.writeByte(0);
        }
        out.writeShort(port);
        if(additional_data != null) {
            out.writeBoolean(true); // 1 byte
            out.writeShort(additional_data.length);
            out.write(additional_data, 0, additional_data.length);
        }
        else {
            out.writeBoolean(false);
        }
    }

    public void readFrom(DataInputStream in) throws IOException {
        int len=in.readByte();
        if(len > 0 && (len != Global.IPV4_SIZE && len != Global.IPV6_SIZE))
            throw new IOException("length has to be " + Global.IPV4_SIZE + " or " + Global.IPV6_SIZE + " bytes (was " +
                    len + " bytes)");
        byte[] a = new byte[len]; // 4 bytes (IPv4) or 16 bytes (IPv6)
        in.readFully(a);
        if(len == Global.IPV6_SIZE) {
            int scope_id=in.readInt();
            this.ip_addr=Inet6Address.getByAddress(null, a, scope_id);
        }
        else {
            this.ip_addr=InetAddress.getByAddress(a);
        }

        // changed from readShort(): we need the full 65535, with a short we'd only get up to 32K !
        port=in.readUnsignedShort();

        if(in.readBoolean() == false)
            return;
        len=in.readUnsignedShort();
        if(len > 0) {
            additional_data=new byte[len];
            in.readFully(additional_data, 0, additional_data.length);
        }
    }

    public int size() {
        if(size >= 0)
            return size;
        // length (1 bytes) + 4 bytes for port + 1 for additional_data available
        int tmp_size=Global.BYTE_SIZE+ Global.SHORT_SIZE + Global.BYTE_SIZE;
        if(ip_addr != null) {
            tmp_size+=ip_addr.getAddress().length; // 4 bytes for IPv4
            if(ip_addr instanceof Inet6Address)
                tmp_size+=Global.INT_SIZE;
        }
        if(additional_data != null)
            tmp_size+=additional_data.length+Global.SHORT_SIZE;
        size=tmp_size;
        return tmp_size;
    }

    public Object clone() throws CloneNotSupportedException {
        IpAddress ret=new IpAddress(ip_addr, port);
        if(additional_data != null) {
            ret.additional_data=new byte[additional_data.length];
            System.arraycopy(additional_data, 0, ret.additional_data, 0, additional_data.length);
        }
        return ret;
    }



}
